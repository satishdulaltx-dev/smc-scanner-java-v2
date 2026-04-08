package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects significant multi-day pivot highs and lows from daily OHLCV bars.
 *
 * A pivot high is a daily bar whose high is greater than the bar on each side.
 * A pivot low  is a daily bar whose low  is lower  than the bar on each side.
 *
 * Used to:
 *  1. Detect when a trade is fighting a significant structural level (resistance/support).
 *  2. Adjust confidence: -12 if entry fights the pivot, +8 if entry confirms it.
 *
 * Looks back up to PIVOT_LOOKBACK trading days (default 10) so that only
 * recent structure matters — pivots from 3 months ago are irrelevant intraday.
 */
@Service
public class PivotPointService {
    private static final Logger log = LoggerFactory.getLogger(PivotPointService.class);

    /** How many daily bars to scan (10 = 2 weeks of structure) */
    private static final int PIVOT_LOOKBACK = 10;

    /** Entry must be within this fraction of a pivot to count as "near" */
    private static final double PIVOT_PROXIMITY_PCT = 0.005; // 0.5%

    /**
     * Compute a confidence adjustment based on nearby multi-day pivot levels.
     *
     * @param dailyBars  daily OHLCV bars, most recent last
     * @param entryPrice proposed entry price
     * @param direction  "long" or "short"
     * @return -12 (fighting pivot), +8 (confirming pivot), or 0 (no nearby pivot)
     */
    public int computePivotAdj(List<OHLCV> dailyBars, double entryPrice, String direction) {
        if (dailyBars == null || dailyBars.size() < 5 || entryPrice <= 0) return 0;

        int n     = dailyBars.size();
        // Scan from (n - LOOKBACK - 2) to (n - 2) so each candidate has a left and right neighbor.
        // Exclude the most recent bar (n-1): it is today's incomplete candle.
        int start = Math.max(1, n - PIVOT_LOOKBACK - 2);
        int end   = n - 2;

        List<Double> pivotHighs = new ArrayList<>();
        List<Double> pivotLows  = new ArrayList<>();

        for (int i = start; i <= end; i++) {
            OHLCV prev = dailyBars.get(i - 1);
            OHLCV cur  = dailyBars.get(i);
            OHLCV next = dailyBars.get(i + 1);

            if (cur.getHigh() > prev.getHigh() && cur.getHigh() > next.getHigh()) {
                pivotHighs.add(cur.getHigh());
            }
            if (cur.getLow() < prev.getLow() && cur.getLow() < next.getLow()) {
                pivotLows.add(cur.getLow());
            }
        }

        double tolerance = entryPrice * PIVOT_PROXIMITY_PCT;

        // Check pivot highs — these act as resistance
        for (double ph : pivotHighs) {
            if (Math.abs(entryPrice - ph) <= tolerance) {
                if ("long".equals(direction)) {
                    log.debug("PIVOT_ADJ {} LONG into resistance ${} → -12", direction, String.format("%.2f", ph));
                    return -12;
                } else {
                    log.debug("PIVOT_ADJ {} SHORT confirming resistance ${} → +8", direction, String.format("%.2f", ph));
                    return +8;
                }
            }
        }

        // Check pivot lows — these act as support
        for (double pl : pivotLows) {
            if (Math.abs(entryPrice - pl) <= tolerance) {
                if ("short".equals(direction)) {
                    log.debug("PIVOT_ADJ {} SHORT into support ${} → -12", direction, String.format("%.2f", pl));
                    return -12;
                } else {
                    log.debug("PIVOT_ADJ {} LONG confirming support ${} → +8", direction, String.format("%.2f", pl));
                    return +8;
                }
            }
        }

        return 0;
    }
}
