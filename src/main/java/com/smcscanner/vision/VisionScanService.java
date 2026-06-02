package com.smcscanner.vision;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Claude Vision independently every 30 min during market hours to find setups
 * that the rule-based scanner doesn't catch (chart patterns, multi-TF confluence, etc.).
 * Results are tagged [VISION] in Discord so they're distinguishable from rule-based alerts.
 */
@Service
public class VisionScanService {

    private static final Logger log = LoggerFactory.getLogger(VisionScanService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 45);  // skip first 15m noise
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30); // stop 30m before close

    private final ChartVisionService  chartVision;
    private final DiscordAlertService discord;
    private final PolygonClient       client;
    private final ScannerConfig       config;
    private final AlertDedup          dedup;

    public VisionScanService(ChartVisionService chartVision, DiscordAlertService discord,
                              PolygonClient client, ScannerConfig config, AlertDedup dedup) {
        this.chartVision = chartVision;
        this.discord     = discord;
        this.client      = client;
        this.config      = config;
        this.dedup       = dedup;
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000) // every 30 minutes
    public void scan() {
        if (!chartVision.isEnabled()) return;

        ZonedDateTime nowZdt = ZonedDateTime.now(ET);
        int dow = nowZdt.getDayOfWeek().getValue();
        if (dow >= 6) return; // weekend

        LocalTime nowET = nowZdt.toLocalTime();
        if (nowET.isBefore(MARKET_OPEN) || nowET.isAfter(MARKET_CLOSE)) return;

        List<String> tickers = config.loadWatchlist();
        if (tickers.isEmpty()) return;

        log.info("VISION_SCAN starting — {} tickers", tickers.size());

        AtomicInteger alertsFired = new AtomicInteger(0);

        for (String ticker : tickers) {
            if (ticker.startsWith("X:")) continue; // skip crypto for now
            try {
                List<OHLCV> bars = client.getBars(ticker, "5m", 100);
                if (bars == null || bars.size() < 20) continue;

                chartVision.proactiveScan(bars, ticker).ifPresent(vs -> {
                    if (dedup.isDuplicate(ticker, vs.direction(), vs.entry(), 120)) {
                        log.info("VISION_SCAN_DEDUP {} — already alerted", ticker);
                        return;
                    }
                    log.info("VISION_SCAN_ALERT {} {} entry={} score={}", ticker, vs.direction(), vs.entry(), vs.score());
                    discord.sendVisionGeneratedAlert(vs);
                    dedup.markSent(ticker, vs.direction(), vs.entry());
                    alertsFired.incrementAndGet();
                });

                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("VISION_SCAN_ERROR {} — {}", ticker, e.getMessage());
            }
        }

        log.info("VISION_SCAN complete — {} alert(s) fired", alertsFired.get());
    }
}
