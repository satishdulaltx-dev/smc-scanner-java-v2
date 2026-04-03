package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Detects gap-up and gap-down at market open.
 *
 * Gap = today's first bar open vs yesterday's last bar close.
 *
 * Two tradeable setups:
 * - Gap & Go: large gap (>1x ATR) + strong volume → trade in gap direction
 * - Gap Fill: small gap (<0.5x ATR) + weak volume → fade, price reverts to prev close
 *
 * Signals are fired in the first 1-2 bars after 9:30 ET only.
 */
@Service
public class GapDetector {
    private static final Logger log = LoggerFactory.getLogger(GapDetector.class);

    public enum GapType { GAP_AND_GO, GAP_FILL, NONE }

    public record GapSignal(
            String ticker,
            GapType type,
            String direction,      // "long" or "short"
            double gapPct,         // gap as % of prev close
            double gapAtrRatio,    // gap size relative to daily ATR
            double prevClose,
            double todayOpen,
            double dailyAtr,
            int confidence,
            String note
    ) {}

    /**
     * Detect gap signal from the first bar of today vs yesterday's close.
     *
     * @param todayBars  5m bars from today's session (need at least 1)
     * @param prevBars   5m bars from yesterday's session (need last bar)
     * @param dailyAtr   daily ATR for sizing
     * @param ticker     ticker symbol
     * @return GapSignal or null if no gap detected
     */
    public GapSignal detect(List<OHLCV> todayBars, List<OHLCV> prevBars,
                            double dailyAtr, String ticker) {
        if (todayBars == null || todayBars.isEmpty()) return null;
        if (prevBars == null || prevBars.isEmpty()) return null;
        if (dailyAtr <= 0) return null;

        double prevClose = prevBars.get(prevBars.size() - 1).getClose();
        double todayOpen = todayBars.get(0).getOpen();
        if (prevClose <= 0) return null;

        double gapAbs = todayOpen - prevClose;
        double gapPct = gapAbs / prevClose * 100.0;
        double gapAtrRatio = Math.abs(gapAbs) / dailyAtr;

        // No meaningful gap
        if (Math.abs(gapPct) < 0.3) return null;

        // Volume confirmation: compare first bar volume to average
        double avgVol = prevBars.stream().mapToLong(OHLCV::getVolume).average().orElse(0);
        double firstBarVol = todayBars.get(0).getVolume();
        double volRatio = avgVol > 0 ? firstBarVol / avgVol : 1.0;

        String direction = gapPct > 0 ? "long" : "short";

        // ── Gap & Go: large gap with volume ─────────────────────────────────
        // Strong gap (>1x ATR) + volume surge → trade in gap direction
        if (gapAtrRatio >= 1.0 && volRatio >= 1.5) {
            int conf = 65;
            if (gapAtrRatio >= 2.0) conf += 10;   // very large gap
            if (volRatio >= 2.5)    conf += 8;     // strong volume
            if (gapAtrRatio >= 1.5 && volRatio >= 2.0) conf += 5; // both strong
            conf = Math.min(conf, 90);

            String note = String.format("Gap %s %.1f%% (%.1fx ATR), vol %.1fx avg — momentum likely continues",
                    gapPct > 0 ? "UP" : "DOWN", Math.abs(gapPct), gapAtrRatio, volRatio);
            log.info("GAP_AND_GO {} {} gap={}% atrRatio={} vol={}x conf={}",
                    ticker, direction.toUpperCase(), String.format("%.2f", gapPct),
                    String.format("%.2f", gapAtrRatio), String.format("%.1f", volRatio), conf);
            return new GapSignal(ticker, GapType.GAP_AND_GO, direction, gapPct,
                    gapAtrRatio, prevClose, todayOpen, dailyAtr, conf, note);
        }

        // ── Gap Fill: small gap with weak volume ─────────────────────────────
        // Weak gap (<0.5x ATR) + low volume → fade, expect price to revert to prev close
        if (gapAtrRatio < 0.5 && volRatio < 1.3) {
            // Fade: trade AGAINST the gap direction (short a gap up, long a gap down)
            String fadeDir = gapPct > 0 ? "short" : "long";
            int conf = 62;
            if (volRatio < 0.8) conf += 5;  // very low volume = weak gap, likely to fill
            String note = String.format("Gap %s %.1f%% (%.1fx ATR), vol %.1fx avg — likely to fill back to $%.2f",
                    gapPct > 0 ? "UP" : "DOWN", Math.abs(gapPct), gapAtrRatio, volRatio, prevClose);
            log.info("GAP_FILL {} {} (fade) gap={}% atrRatio={} vol={}x conf={}",
                    ticker, fadeDir.toUpperCase(), String.format("%.2f", gapPct),
                    String.format("%.2f", gapAtrRatio), String.format("%.1f", volRatio), conf);
            return new GapSignal(ticker, GapType.GAP_FILL, fadeDir, gapPct,
                    gapAtrRatio, prevClose, todayOpen, dailyAtr, conf, note);
        }

        return null;
    }
}
