package com.smcscanner.strategy;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Swing trade scanner: runs on daily bars after market close (4:30 PM ET).
 * Uses the same SetupDetector as intraday but on 1d timeframe.
 * Alerts go to a dedicated Discord swing channel.
 * Dedup cooldown: 72 hours (one setup per ticker per 3 days).
 */
@Service
public class SwingScannerService {
    private static final Logger log = LoggerFactory.getLogger(SwingScannerService.class);
    private static final int    SWING_COOLDOWN_MIN = 72 * 60;  // 3 days
    private static final String SWING_KEY_PREFIX   = "swing_"; // separates from intraday dedup keys

    private final ScannerConfig      config;
    private final PolygonClient      client;
    private final SetupDetector      setupDetector;
    private final DiscordAlertService discord;
    private final AlertDedup         dedup;

    public SwingScannerService(ScannerConfig config, PolygonClient client,
                                SetupDetector setupDetector, DiscordAlertService discord,
                                AlertDedup dedup) {
        this.config = config; this.client = client; this.setupDetector = setupDetector;
        this.discord = discord; this.dedup = dedup;
    }

    public void scanAll(List<String> tickers) {
        log.info("=== SWING SCAN starting: {} tickers ===", tickers.size());
        int alerts = 0;
        for (String ticker : tickers) {
            try {
                if (scanTicker(ticker)) alerts++;
                Thread.sleep(400);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
              catch (Exception e) { log.error("Swing scan error {}: {}", ticker, e.getMessage()); }
        }
        log.info("=== SWING SCAN complete: {}/{} tickers fired alerts ===", alerts, tickers.size());
    }

    /** Returns true if an alert was sent. */
    public boolean scanTicker(String ticker) {
        if (ticker.startsWith("X:")) return false; // crypto doesn't use daily bars

        List<OHLCV> bars = client.getBars(ticker, "1d", 100);
        if (bars == null || bars.size() < 30) {
            log.debug("Swing {}: insufficient daily bars ({})", ticker, bars == null ? 0 : bars.size());
            return false;
        }

        // HTF bias: recent 5-day avg vs prior 15-day avg
        String htfBias = "neutral";
        if (bars.size() >= 20) {
            double recent = bars.subList(bars.size()-5,  bars.size()).stream().mapToDouble(OHLCV::getClose).average().orElse(0);
            double prior  = bars.subList(bars.size()-20, bars.size()-5).stream().mapToDouble(OHLCV::getClose).average().orElse(0);
            if (prior > 0) htfBias = recent > prior * 1.01 ? "bullish" : recent < prior * 0.99 ? "bearish" : "neutral";
        }

        // backtestMode=true bypasses the intraday session filter (we intentionally run after hours)
        // dailyAtr=0 → SetupDetector uses curAtr*4 fallback (which is the daily ATR since we're on 1d bars)
        SetupDetector.DetectResult result = setupDetector.detectSetups(bars, htfBias, ticker, false, 0.0, true);
        if (result.setups().isEmpty()) {
            log.debug("Swing {}: no setup (phase={})", ticker, result.state().getPhase());
            return false;
        }

        TradeSetup s = result.setups().get(0);
        if (s.getConfidence() < config.getMinConfidence()) {
            log.debug("Swing {}: LOW_CONF conf={}", ticker, s.getConfidence());
            return false;
        }

        String dedupKey = SWING_KEY_PREFIX + ticker;
        if (dedup.isDuplicate(dedupKey, s.getDirection(), s.getEntry(), SWING_COOLDOWN_MIN)) {
            log.debug("Swing {}: dedup suppressed (same setup within 72h)", ticker);
            return false;
        }

        log.info("SWING ALERT {} {} conf={} entry={} sl={} tp={}",
                ticker, s.getDirection().toUpperCase(), s.getConfidence(),
                s.getEntry(), s.getStopLoss(), s.getTakeProfit());
        discord.sendSwingAlert(s);
        dedup.markSent(dedupKey, s.getDirection(), s.getEntry());
        return true;
    }
}
