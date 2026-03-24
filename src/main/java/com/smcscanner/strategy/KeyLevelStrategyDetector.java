package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Key Level Rejection (KLR) strategy detector.
 *
 * Identifies horizontal support/resistance levels that have been tested 2+ times
 * on daily (or hourly) bars, then fires alerts when price touches and rejects
 * from those levels on 5-minute bars.
 *
 * Why these levels work:
 *  - Large institutions place block orders at visible S/R levels to distribute/accumulate
 *  - Every market participant can see the same level → self-fulfilling prophecy
 *  - 3rd or 4th test of the same price is the highest-probability rejection
 *
 * Works best for large-cap, liquid stocks (AAPL, MSFT, META) where there is
 * enough institutional size to create persistent price memory.
 */
@Service
public class KeyLevelStrategyDetector {

    /** Max price distance (%) for two swing points to belong to the same cluster. */
    private static final double LEVEL_TOLERANCE = 0.003;  // 0.3%

    /** Max distance (%) from current price to a level for it to be "in range". */
    private static final double TOUCH_TOLERANCE = 0.004;  // 0.4%

    /** Minimum number of touches for a level to be considered valid. */
    private static final int MIN_TOUCHES = 2;

    /** Number of bars on each side required to confirm a pivot high/low. */
    private static final int PIVOT_LOOKBACK = 2;

    /**
     * Detect key-level rejection setups.
     *
     * @param fiveMinBars  recent 5-minute bars (used for entry signal + ATR)
     * @param htfBars      daily (or hourly) bars used to identify key S/R levels
     * @param ticker       symbol
     * @param dailyAtr     daily ATR for TP sizing
     * @return             0 or 1 detected setup
     */
    public List<TradeSetup> detect(List<OHLCV> fiveMinBars, List<OHLCV> htfBars,
                                    String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (fiveMinBars == null || fiveMinBars.isEmpty()) return result;
        if (htfBars == null || htfBars.size() < 10)       return result;

        OHLCV last     = fiveMinBars.get(fiveMinBars.size() - 1);
        double curClose = last.getClose();
        double curOpen  = last.getOpen();
        double curHigh  = last.getHigh();
        double curLow   = last.getLow();

        // 5m ATR for SL sizing; ensure a sensible floor
        double atr5m = computeAtr(fiveMinBars);
        double atr   = Math.max(atr5m, curClose * 0.001);

        // TP sizing: prefer dailyAtr if meaningful, else scale from 5m ATR
        double effectiveAtr = (dailyAtr > atr * 3) ? dailyAtr : atr * 8;

        // Average 5m volume
        double avgVol = fiveMinBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        // ── Step 1: Identify key levels from HTF bars ─────────────────────────
        // lev[0] = price, lev[1] = touch count, lev[2] = +1 (resistance) or -1 (support)
        List<double[]> levels = findKeyLevels(htfBars, curClose);
        if (levels.isEmpty()) return result;

        // ── Step 2: Check for rejection at the nearest level ──────────────────
        for (double[] lev : levels) {
            double levelPrice   = lev[0];
            int    touches      = (int) lev[1];
            boolean isResistance = lev[2] > 0;

            // Only act on levels that are genuinely close to current price
            double distPct = Math.abs(curClose - levelPrice) / levelPrice;
            if (distPct > TOUCH_TOLERANCE * 3) continue;

            if (isResistance) {
                // ── SHORT: price touched resistance and is rejecting downward ──
                // • Current bar's high reached or poked above the level (it was tested)
                // • Bar closed below the level (rejection, not breakout)
                // • Bearish bar (close < open)
                // • Volume elevated
                boolean touched      = curHigh >= levelPrice * (1 - TOUCH_TOLERANCE);
                boolean rejectedDown = curClose <= levelPrice * (1 + TOUCH_TOLERANCE * 0.3);
                boolean bearishBar   = curClose < curOpen;
                boolean upperWick    = (curHigh - curClose) > atr * 0.15; // significant upper wick
                boolean volConfirmed = last.getVolume() > avgVol * 1.2;

                if (touched && rejectedDown && bearishBar && volConfirmed) {
                    double entry = r4(curClose);
                    double sl    = r4(levelPrice + atr * 0.5);
                    double tp    = r4(entry - effectiveAtr * 0.6);

                    // Safety guards
                    if (sl <= entry) sl = r4(entry + atr * 0.5);
                    if (tp >= entry * 0.999) tp = r4(entry - effectiveAtr * 0.6);

                    if (sl > entry && tp < entry) {
                        double risk   = sl - entry;
                        double reward = entry - tp;
                        double rr     = risk > 0 ? reward / risk : 0.0;

                        if (rr >= 1.2) {
                            int confidence = 65;
                            if (touches >= 3)                         confidence += 10; // 3+ tests = high-quality level
                            if (touches >= 4)                         confidence += 5;  // 4+ = institutional distribution
                            if (upperWick)                            confidence += 5;  // clear rejection wick
                            if (last.getVolume() > avgVol * 2.0)     confidence += 5;  // volume surge at level

                            result.add(TradeSetup.builder()
                                    .ticker(ticker)
                                    .direction("short")
                                    .entry(entry)
                                    .stopLoss(sl)
                                    .takeProfit(tp)
                                    .confidence(confidence)
                                    .session("NYSE")
                                    .volatility("keylevel")    // used as strategy tag
                                    .atr(atr)
                                    .hasBos(false)
                                    .hasChoch(false)
                                    .fvgTop(r4(levelPrice))
                                    .fvgBottom(r4(levelPrice - atr))
                                    .timestamp(LocalDateTime.now())
                                    .build());
                            return result;
                        }
                    }
                }

            } else {
                // ── LONG: price touched support and is bouncing upward ──────────
                boolean touched      = curLow <= levelPrice * (1 + TOUCH_TOLERANCE);
                boolean bouncedUp    = curClose >= levelPrice * (1 - TOUCH_TOLERANCE * 0.3);
                boolean bullishBar   = curClose > curOpen;
                boolean lowerWick    = (curClose - curLow) > atr * 0.15; // significant lower wick
                boolean volConfirmed = last.getVolume() > avgVol * 1.2;

                if (touched && bouncedUp && bullishBar && volConfirmed) {
                    double entry = r4(curClose);
                    double sl    = r4(levelPrice - atr * 0.5);
                    double tp    = r4(entry + effectiveAtr * 0.6);

                    // Safety guards
                    if (sl >= entry) sl = r4(entry - atr * 0.5);
                    if (tp <= entry * 1.001) tp = r4(entry + effectiveAtr * 0.6);

                    if (sl < entry && tp > entry) {
                        double risk   = entry - sl;
                        double reward = tp - entry;
                        double rr     = risk > 0 ? reward / risk : 0.0;

                        if (rr >= 1.2) {
                            int confidence = 65;
                            if (touches >= 3)                         confidence += 10;
                            if (touches >= 4)                         confidence += 5;
                            if (lowerWick)                            confidence += 5;
                            if (last.getVolume() > avgVol * 2.0)     confidence += 5;

                            result.add(TradeSetup.builder()
                                    .ticker(ticker)
                                    .direction("long")
                                    .entry(entry)
                                    .stopLoss(sl)
                                    .takeProfit(tp)
                                    .confidence(confidence)
                                    .session("NYSE")
                                    .volatility("keylevel")
                                    .atr(atr)
                                    .hasBos(false)
                                    .hasChoch(false)
                                    .fvgTop(r4(levelPrice + atr))
                                    .fvgBottom(r4(levelPrice))
                                    .timestamp(LocalDateTime.now())
                                    .build());
                            return result;
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Find key horizontal levels from HTF bars.
     *
     * Algorithm:
     * 1. Scan bars for pivot highs and lows (using PIVOT_LOOKBACK bars each side).
     * 2. Cluster pivots within LEVEL_TOLERANCE of each other.
     * 3. A cluster with MIN_TOUCHES or more members is a valid key level.
     * 4. Returns list of [price, touches, type] sorted by distance from current price.
     *
     * Note: the last PIVOT_LOOKBACK bars are excluded from pivot detection because
     * they don't have confirmed forward bars yet.
     */
    private List<double[]> findKeyLevels(List<OHLCV> htf, double curClose) {
        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows  = new ArrayList<>();

        // Use last 120 bars (≈6 months on daily; ≈3 months on 60m)
        int start = Math.max(0, htf.size() - 120);
        int end   = htf.size() - PIVOT_LOOKBACK; // exclude unconfirmed recent pivots

        for (int i = start + PIVOT_LOOKBACK; i < end; i++) {
            double h = htf.get(i).getHigh();
            double l = htf.get(i).getLow();

            boolean isSwingHigh = true;
            boolean isSwingLow  = true;
            for (int j = 1; j <= PIVOT_LOOKBACK; j++) {
                if (htf.get(i - j).getHigh() >= h || htf.get(i + j).getHigh() >= h) isSwingHigh = false;
                if (htf.get(i - j).getLow()  <= l || htf.get(i + j).getLow()  <= l) isSwingLow  = false;
            }
            if (isSwingHigh) swingHighs.add(h);
            if (isSwingLow)  swingLows.add(l);
        }

        List<double[]> result = new ArrayList<>();
        result.addAll(clusterLevels(swingHighs, curClose, +1.0)); // resistance
        result.addAll(clusterLevels(swingLows,  curClose, -1.0)); // support

        // Sort ascending by distance from current price (nearest first)
        result.sort((a, b) -> Double.compare(
                Math.abs(a[0] - curClose),
                Math.abs(b[0] - curClose)));
        return result;
    }

    /**
     * Cluster a list of price points and return valid levels (min touches met).
     * Each cluster's representative price is the average of its members.
     */
    private List<double[]> clusterLevels(List<Double> points, double curClose, double type) {
        List<double[]> clusters = new ArrayList<>();
        boolean[] used = new boolean[points.size()];

        for (int i = 0; i < points.size(); i++) {
            if (used[i]) continue;
            double p = points.get(i);

            // Only consider levels within 8% of current price (relevant range)
            if (Math.abs(p - curClose) / curClose > 0.08) {
                used[i] = true;
                continue;
            }

            List<Double> cluster = new ArrayList<>();
            cluster.add(p);
            used[i] = true;

            for (int j = i + 1; j < points.size(); j++) {
                if (!used[j] && Math.abs(points.get(j) - p) / p <= LEVEL_TOLERANCE) {
                    cluster.add(points.get(j));
                    used[j] = true;
                }
            }

            if (cluster.size() >= MIN_TOUCHES) {
                double avg = cluster.stream().mapToDouble(Double::doubleValue).average().orElse(p);
                clusters.add(new double[]{avg, cluster.size(), type});
            }
        }
        return clusters;
    }

    /** Compute 14-bar simple ATR. */
    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0.0;
        int start = bars.size() - period - 1;
        double sum = 0.0;
        for (int i = start + 1; i < bars.size(); i++) {
            OHLCV cur  = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr  = Math.max(cur.getHigh() - cur.getLow(),
                         Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                  Math.abs(cur.getLow()  - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    /** Round to 4 decimal places. */
    private double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
