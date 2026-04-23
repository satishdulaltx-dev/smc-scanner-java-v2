package com.smcscanner.strategy;

import org.springframework.stereotype.Service;

/**
 * Quality-graduated SMC setup scorer.
 *
 * Old model: binary flags → always 90+ for any 4-component chain, making minConf meaningless.
 * New model: each factor contributes proportionally to its actual quality, so:
 *   - Textbook setup (equal sweep, large disp, fresh FVG, tight wick): 92–100
 *   - Decent setup (single sweep, moderate disp, aging FVG, ok wick): 76–84
 *   - Borderline setup (weak disp, stale FVG, messy retest): 65–75 → gets filtered by minConf
 *
 * Range: ~60 (bare minimum, no quality bonuses) → 100.
 * All four base SMC components (sweep+disp+fvg+retest) are assumed present — this is
 * called only after the state machine already confirmed them.
 */
@Service
public class ScoringService {

    /**
     * @param equalSweep        true if the sweep took out an equal-high/low cluster (stop orders)
     * @param dispAtrRatio      displacement bar range ÷ ATR — how strong the displacement was
     * @param fvgAgeBars        bars elapsed since FVG formed — freshness matters intraday
     * @param retestWickQuality close position in bar range [0–1]: 1.0 = fully bullish, 0.0 = fully bearish
     * @param bos               BOS or CHOCH structure break detected
     * @param volumeSpike       peak volume in last 10 bars ≥ 1.5× average
     */
    public int scoreSetup(boolean equalSweep, double dispAtrRatio, int fvgAgeBars,
                          double retestWickQuality, boolean bos, boolean volumeSpike) {
        int s = 60; // base: all four SMC components present

        // ── Sweep quality (12 vs 5): equal-level sweep targets real stop clusters ──
        s += equalSweep ? 12 : 5;

        // ── Displacement conviction: bigger relative to ATR = more institutional force ──
        if      (dispAtrRatio >= 3.0) s += 10;
        else if (dispAtrRatio >= 2.0) s += 6;
        else if (dispAtrRatio >= 1.5) s += 3;

        // ── FVG freshness: older FVGs are more likely price-discovered intraday ──
        if      (fvgAgeBars <= 5)  s += 8;
        else if (fvgAgeBars <= 12) s += 4;
        else if (fvgAgeBars <= 20) s += 1;
        // > 20 bars: no freshness bonus (age filter rejects these before we get here)

        // ── Retest wick quality: tight rejection body = cleaner signal ──
        if      (retestWickQuality >= 0.75) s += 5;
        else if (retestWickQuality >= 0.50) s += 2;

        // ── Structure and volume bonuses ──────────────────────────────────────────
        if (bos)         s += 5;
        if (volumeSpike) s += 5;

        return Math.min(100, s);
    }
}
