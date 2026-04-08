package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects the current market regime from 5m OHLCV bars.
 *
 * Regime is used to:
 *  1. Validate that the active strategy is appropriate (SMC on a ranging day = noise)
 *  2. Suggest a fallback strategy when the primary returns no setups
 *  3. Adjust SL width and confidence for volatile conditions
 *  4. Gate out low-liquidity environments entirely (RVOL < 0.8)
 *
 * A 15-minute cooldown cache prevents re-computing every scan tick (live only).
 * Backtest callers use detectForBacktest() which bypasses the cache.
 */
@Service
public class MarketRegimeDetector {
    private static final Logger log = LoggerFactory.getLogger(MarketRegimeDetector.class);

    public enum Regime {
        TRENDING,       // ADX > 25 — follow momentum, use SMC / breakout
        RANGING,        // ADX < 20 — mean-reversion, use KeyLevel / VWAP
        SQUEEZE,        // BB inside Keltner — coiling, wait for breakout
        VOLATILE,       // ATR > 1.8× 20-bar avg — widen SL, reduce size
        LOW_LIQUIDITY   // RVOL < 0.8 — no trades, price is directionless
    }

    private static final long COOLDOWN_MS    = 15 * 60 * 1000L; // 15 min live cooldown
    private static final int  ADX_PERIOD     = 14;
    private static final double RVOL_MIN     = 0.8;   // below this = LOW_LIQUIDITY
    private static final double ATR_VOL_MUL  = 1.8;   // ATR > 1.8× avg = VOLATILE
    private static final double ADX_TREND    = 25.0;  // ADX > 25 = TRENDING
    private static final double ADX_RANGE    = 20.0;  // ADX < 20 = RANGING

    private record CachedRegime(Regime regime, long computedAtMs) {}
    private final Map<String, CachedRegime> cache = new ConcurrentHashMap<>();

    // ── Live entry point (with 15-min cooldown) ──────────────────────────────
    public Regime detect(List<OHLCV> bars, String ticker) {
        CachedRegime cached = cache.get(ticker);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.computedAtMs()) < COOLDOWN_MS) {
            return cached.regime();
        }
        Regime r = compute(bars);
        cache.put(ticker, new CachedRegime(r, now));
        log.debug("{} REGIME: {} (ADX={} RVOL={} atrPctile={})",
                ticker, r,
                String.format("%.1f", computeAdx(bars, ADX_PERIOD)),
                String.format("%.2f", computeRvol(bars, 20)),
                String.format("%.2f", computeAtrPercentile(bars)));
        return r;
    }

    // ── Backtest entry point (no cache — bar-by-bar replay) ──────────────────
    public Regime detectForBacktest(List<OHLCV> bars) {
        return compute(bars);
    }

    // ── Core computation ──────────────────────────────────────────────────────
    private Regime compute(List<OHLCV> bars) {
        if (bars == null || bars.size() < 20) return Regime.RANGING;

        // 1. Low liquidity gate — no participation, signals are noise
        double rvol = computeRvol(bars, 20);
        if (rvol < RVOL_MIN) return Regime.LOW_LIQUIDITY;

        // 2. High volatility — ATR expanded beyond normal range
        double atrPctile = computeAtrPercentile(bars);
        if (atrPctile > ATR_VOL_MUL) return Regime.VOLATILE;

        // 3. Squeeze — BB inside Keltner channel, coiling before breakout
        if (detectSqueeze(bars)) return Regime.SQUEEZE;

        // 4. ADX trend strength
        double adx = computeAdx(bars, ADX_PERIOD);
        if (adx > ADX_TREND) return Regime.TRENDING;
        if (adx < ADX_RANGE) return Regime.RANGING;

        // ADX 20–25: use whichever regime is stronger contextually
        return rvol > 1.3 ? Regime.TRENDING : Regime.RANGING;
    }

    /**
     * Given the current regime and the profile's preferred strategy, return
     * a better-suited strategy. Returns null if no trade should be taken.
     * Used as fallback when primary strategy returns no setups.
     */
    public String suggestStrategy(Regime regime, String profileStrategy) {
        return switch (regime) {
            case TRENDING      -> "smc";
            case RANGING       -> "keylevel";
            case SQUEEZE       -> "vsqueeze";
            case VOLATILE      -> profileStrategy; // trust tuned profile in chaos
            case LOW_LIQUIDITY -> null;             // no trades
        };
    }

    /**
     * Confidence delta based on strategy-regime alignment.
     * e.g. running SMC (trend-following) in a RANGING day gets -8.
     * Running KeyLevel in a RANGING day gets +5.
     */
    public int computeStrategyAlignment(Regime regime, String strategyType) {
        if (strategyType == null) return 0;
        return switch (regime) {
            case TRENDING -> switch (strategyType) {
                case "smc", "breakout" -> +5;   // aligned with trend
                case "keylevel"         -> -5;   // fighting the trend
                case "vwap", "vwap3d"   -> -8;   // mean-reversion into trend
                default                 -> 0;
            };
            case RANGING -> switch (strategyType) {
                case "keylevel", "vwap", "vwap3d" -> +5;  // aligned with range
                case "smc", "breakout"             -> -5;  // momentum strats in range
                default                            -> 0;
            };
            case SQUEEZE -> switch (strategyType) {
                case "vsqueeze" -> +8;  // designed for this
                case "breakout" -> +3;  // also works
                default         -> -5;  // stale setups likely
            };
            case VOLATILE -> -5;           // all strategies harder in chaos
            case LOW_LIQUIDITY -> -20;    // should have been blocked already
        };
    }

    /**
     * In a VOLATILE regime, multiply the risk (entry-to-SL) by this factor.
     * Returns 1.0 for all other regimes (no change).
     */
    public double slExpansionFactor(Regime regime) {
        return regime == Regime.VOLATILE ? 1.5 : 1.0;
    }

    // ── ADX computation (Wilder's smoothing, 14-period) ───────────────────────
    public double computeAdx(List<OHLCV> bars, int period) {
        int n = bars.size();
        if (n < period + 1) return 0.0;

        // Seed with simple averages for first period
        double smoothTR = 0, smoothPDM = 0, smoothMDM = 0;
        for (int i = n - period - period + 1; i <= n - period; i++) {
            if (i <= 0) continue;
            OHLCV cur = bars.get(i), prev = bars.get(i - 1);
            smoothTR  += trueRange(cur, prev);
            smoothPDM += plusDM(cur, prev);
            smoothMDM += minusDM(cur, prev);
        }

        double adxSum = 0;
        int adxCount = 0;
        double prevDx = 0;

        int start = Math.max(1, n - period * 2);
        for (int i = start; i < n; i++) {
            OHLCV cur = bars.get(i), prev = bars.get(i - 1);
            double tr  = trueRange(cur, prev);
            double pdm = plusDM(cur, prev);
            double mdm = minusDM(cur, prev);

            // Wilder smoothing
            smoothTR  = smoothTR  - (smoothTR  / period) + tr;
            smoothPDM = smoothPDM - (smoothPDM / period) + pdm;
            smoothMDM = smoothMDM - (smoothMDM / period) + mdm;

            if (smoothTR == 0) continue;
            double pdi = 100.0 * smoothPDM / smoothTR;
            double mdi = 100.0 * smoothMDM / smoothTR;
            double sum = pdi + mdi;
            double dx  = sum == 0 ? 0 : 100.0 * Math.abs(pdi - mdi) / sum;

            adxSum += dx;
            adxCount++;
        }
        return adxCount == 0 ? 0 : adxSum / adxCount;
    }

    // ── RVOL (relative volume vs 20-bar average) ──────────────────────────────
    public double computeRvol(List<OHLCV> bars, int avgPeriod) {
        int n = bars.size();
        if (n < 2) return 1.0;
        double curVol = bars.get(n - 1).getVolume();
        int lookback = Math.min(avgPeriod, n - 1);
        double avgVol = 0;
        for (int i = n - 1 - lookback; i < n - 1; i++) {
            avgVol += bars.get(i).getVolume();
        }
        avgVol /= lookback;
        return avgVol > 0 ? curVol / avgVol : 1.0;
    }

    // ── ATR percentile: current ATR vs 20-bar average ATR ────────────────────
    public double computeAtrPercentile(List<OHLCV> bars) {
        int n = bars.size();
        if (n < 21) return 1.0;
        // Current ATR (last bar)
        OHLCV cur = bars.get(n - 1), prev = bars.get(n - 2);
        double curAtr = trueRange(cur, prev);
        // Average of prior 20 ATRs
        double sumAtr = 0;
        for (int i = n - 21; i < n - 1; i++) {
            sumAtr += trueRange(bars.get(i + 1), bars.get(i));
        }
        double avgAtr = sumAtr / 20.0;
        return avgAtr > 0 ? curAtr / avgAtr : 1.0;
    }

    // ── Squeeze: Bollinger Bands inside Keltner Channel ───────────────────────
    public boolean detectSqueeze(List<OHLCV> bars) {
        int n = bars.size();
        if (n < 20) return false;
        List<OHLCV> slice = bars.subList(n - 20, n);

        // BB: 2σ around 20-bar SMA of close
        double sma = slice.stream().mapToDouble(OHLCV::getClose).average().orElse(0);
        double variance = slice.stream().mapToDouble(b -> Math.pow(b.getClose() - sma, 2)).average().orElse(0);
        double bbWidth = 2 * Math.sqrt(variance);

        // Keltner: 1.5 * ATR around SMA
        double sumAtr = 0;
        for (int i = 1; i < slice.size(); i++) {
            sumAtr += trueRange(slice.get(i), slice.get(i - 1));
        }
        double keltnerWidth = 1.5 * (sumAtr / (slice.size() - 1));

        return bbWidth < keltnerWidth;
    }

    private double trueRange(OHLCV cur, OHLCV prev) {
        return Math.max(cur.getHigh() - cur.getLow(),
               Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                        Math.abs(cur.getLow()  - prev.getClose())));
    }

    private double plusDM(OHLCV cur, OHLCV prev) {
        double up   = cur.getHigh()  - prev.getHigh();
        double down = prev.getLow()  - cur.getLow();
        return (up > down && up > 0) ? up : 0;
    }

    private double minusDM(OHLCV cur, OHLCV prev) {
        double up   = cur.getHigh()  - prev.getHigh();
        double down = prev.getLow()  - cur.getLow();
        return (down > up && down > 0) ? down : 0;
    }
}
