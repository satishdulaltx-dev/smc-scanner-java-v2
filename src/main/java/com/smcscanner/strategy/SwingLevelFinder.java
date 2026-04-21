package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import java.util.List;

/**
 * Finds the nearest structural swing low / swing high to anchor stop-loss placement.
 *
 * A pivot low  (i): bar[i].low  <= bar[i-1].low  && bar[i].low  <= bar[i+1].low
 * A pivot high (i): bar[i].high >= bar[i-1].high && bar[i].high >= bar[i+1].high
 *
 * For a LONG entry we want the highest pivot low below entry — that is the nearest
 * support level. SL is placed just below it (- 0.10 × ATR buffer).
 * For a SHORT entry we want the lowest pivot high above entry. SL just above it.
 *
 * If no structural level is found within the lookback window, the provided
 * {@code fallback} SL is returned unchanged.
 */
public final class SwingLevelFinder {

    private SwingLevelFinder() {}

    /**
     * For a LONG entry: returns an SL anchored at the nearest swing low below entry.
     *
     * @param bars     session bars (index 0 = oldest, last = current)
     * @param entry    entry price — candidate pivot lows must be strictly below this
     * @param atr      current ATR (used for the 0.10 buffer and the 2.0× cap)
     * @param lookback how many completed bars back to scan (recommend 20)
     * @param fallback ATR-based SL to use when no structural pivot is found
     * @return SL < entry, capped so risk never exceeds 2.0 × ATR
     */
    public static double swingLowSl(List<OHLCV> bars, double entry, double atr,
                                    int lookback, double fallback) {
        int n = bars.size();
        // Need at least 3 bars to form a pivot (prev, pivot, next)
        if (n < 3) return fallback;

        // Walk backward from bar n-2 (second-to-last — skip the live bar)
        double bestLow = Double.NEGATIVE_INFINITY;
        for (int i = n - 2; i >= Math.max(1, n - lookback); i--) {
            double lo   = bars.get(i).getLow();
            double prev = bars.get(i - 1).getLow();
            double next = bars.get(i + 1).getLow(); // i+1 is always valid since i <= n-2
            boolean isPivot = lo <= prev && lo <= next;
            if (isPivot && lo < entry) {
                bestLow = Math.max(bestLow, lo); // highest pivot low below entry
            }
        }

        if (bestLow == Double.NEGATIVE_INFINITY) return fallback; // no pivot found

        double sl = Math.round((bestLow - atr * 0.10) * 10_000.0) / 10_000.0;
        // Cap: never risk more than 2× ATR (prevents absurdly wide stops)
        sl = Math.max(sl, entry - atr * 2.0);
        return sl < entry ? sl : fallback;
    }

    /**
     * For a SHORT entry: returns an SL anchored at the nearest swing high above entry.
     *
     * @param bars     session bars
     * @param entry    entry price — candidate pivot highs must be strictly above this
     * @param atr      current ATR
     * @param lookback how many completed bars back to scan (recommend 20)
     * @param fallback ATR-based SL to use when no structural pivot is found
     * @return SL > entry, capped so risk never exceeds 2.0 × ATR
     */
    public static double swingHighSl(List<OHLCV> bars, double entry, double atr,
                                     int lookback, double fallback) {
        int n = bars.size();
        if (n < 3) return fallback;

        double bestHigh = Double.POSITIVE_INFINITY;
        for (int i = n - 2; i >= Math.max(1, n - lookback); i--) {
            double hi   = bars.get(i).getHigh();
            double prev = bars.get(i - 1).getHigh();
            double next = bars.get(i + 1).getHigh();
            boolean isPivot = hi >= prev && hi >= next;
            if (isPivot && hi > entry) {
                bestHigh = Math.min(bestHigh, hi); // lowest pivot high above entry
            }
        }

        if (bestHigh == Double.POSITIVE_INFINITY) return fallback;

        double sl = Math.round((bestHigh + atr * 0.10) * 10_000.0) / 10_000.0;
        // Cap: never risk more than 2× ATR
        sl = Math.min(sl, entry + atr * 2.0);
        return sl > entry ? sl : fallback;
    }
}
