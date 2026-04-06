package com.smcscanner.market;

/**
 * Market context snapshot used to gate trade signals with two orthogonal filters:
 *
 * 1. Relative Strength (RS):
 *    rsScore = ticker 5-day return − SPY 5-day return
 *    Positive = stock outperforming SPY (momentum is UP relative to market).
 *    Example: SNAP up +13% while SPY up +2% → rsScore ≈ +0.11 → risky to SHORT.
 *
 * 2. VIX Regime:
 *    vixLevel = CBOE VIX closing value.
 *    - "volatile" (VIX > 25): trending/gapping market; VWAP reversion is unreliable
 *    - "normal"   (VIX 15-25): balanced; all strategies OK
 *    - "calm"     (VIX < 15):  low-range market; ORB breakouts have no follow-through
 *
 * Both produce a signed confidence delta (same pattern as NewsSentiment) so they
 * compose additively: totalAdj = newsAdj + contextAdj.
 */
public record MarketContext(
        String ticker,
        double rsScore,      // range roughly -0.15 .. +0.15 for normal stocks
        double vixLevel,     // 0.0 = unavailable; use 20 as neutral fallback
        String vixRegime     // "volatile" | "normal" | "calm"
) {
    /** Returned when market data is unavailable or not applicable (crypto, SPY itself). */
    public static final MarketContext NONE = new MarketContext("", 0.0, 20.0, "normal");

    // ── Relative Strength ─────────────────────────────────────────────────────

    /**
     * True when RS strongly opposes the trade direction.
     * Threshold >3%: stock has meaningful momentum against our signal.
     * e.g. stock outperforming SPY by >3% → risky to short it.
     */
    public boolean isRsConflicting(String direction) {
        if ("short".equals(direction) && rsScore >  0.03) return true;
        if ("long".equals(direction)  && rsScore < -0.03) return true;
        return false;
    }

    /**
     * VWAP-specific RS conflict check — tighter threshold (±1.5% vs ±3%).
     * VWAP is mean-reversion: a slight RS headwind means the pullback may be a real trend,
     * not a temporary dip. Learned from SMCI losses: RS=-2.1% and RS=+2.4% both produced
     * losses that passed the standard ±3% filter.
     */
    public boolean isRsConflictingVwap(String direction) {
        if ("short".equals(direction) && rsScore >  0.015) return true;
        if ("long".equals(direction)  && rsScore < -0.015) return true;
        return false;
    }

    /** True when RS momentum supports the trade direction. */
    public boolean isRsAligned(String direction) {
        if ("long".equals(direction)  && rsScore >  0.02) return true;
        if ("short".equals(direction) && rsScore < -0.02) return true;
        return false;
    }

    // ── VIX Regime ────────────────────────────────────────────────────────────

    /**
     * True when VIX regime is a poor environment for the given strategy.
     * - High VIX + VWAP: gaps blow through mean-reversion entries
     * - Low VIX  + ORB:  range too narrow for breakout follow-through
     * SMC and KeyLevel are relatively regime-agnostic (structure-based).
     */
    public boolean isVixConflicting(String strategyType) {
        if ("volatile".equals(vixRegime) && "vwap".equals(strategyType))     return true;
        if ("calm".equals(vixRegime)     && "breakout".equals(strategyType)) return true;
        return false;
    }

    // ── Confidence delta ──────────────────────────────────────────────────────

    /**
     * Combined RS + VIX confidence adjustment.
     * Follows the same magnitude pattern as NewsSentiment.confidenceDelta():
     *   RS strong conflict (>6% divergence): −12   RS moderate conflict (3-6%): −6
     *   RS strong alignment:                 +6    RS moderate alignment:       +3
     *   VIX regime mismatch:                 −8    (stacks with RS delta)
     *
     * VWAP special: uses tighter ±1.5% RS conflict threshold and returns -999 (hard block)
     * because VWAP is mean-reversion — even slight RS headwind = real trend, not a dip.
     *
     * @param direction    "long" | "short"
     * @param strategyType "smc" | "vwap" | "breakout" | "keylevel"
     */
    public int confidenceDelta(String direction, String strategyType) {
        int delta = 0;

        if ("vwap".equals(strategyType) && isRsConflictingVwap(direction)) {
            return -999; // hard block — VWAP RS conflict is a disqualifier, not just a penalty
        }

        if (isRsConflicting(direction)) {
            delta += Math.abs(rsScore) >= 0.06 ? -12 : -6;
        } else if (isRsAligned(direction)) {
            delta += Math.abs(rsScore) >= 0.05 ? +6 : +3;
        }

        if (isVixConflicting(strategyType)) delta -= 8;

        return delta;
    }

    // ── Labels for Discord / UI ───────────────────────────────────────────────

    /** Returns a human-readable RS label, or null when RS is neutral (no display). */
    public String rsLabel() {
        if (rsScore >  0.06) return "📈 Strong RS: +" + pct(rsScore) + "% vs SPY";
        if (rsScore >  0.02) return "↗️ +RS: +"      + pct(rsScore) + "% vs SPY";
        if (rsScore < -0.06) return "📉 Weak RS: "   + pct(rsScore) + "% vs SPY";
        if (rsScore < -0.02) return "↘️ −RS: "       + pct(rsScore) + "% vs SPY";
        return null; // neutral — omit from embed
    }

    /** Returns a human-readable VIX label, or null when vixLevel is unknown. */
    public String vixLabel() {
        if (vixLevel <= 0) return null;
        String icon = "volatile".equals(vixRegime) ? "🔴" : "calm".equals(vixRegime) ? "🟢" : "🟡";
        return icon + " VIX " + String.format("%.1f", vixLevel)
             + " (" + vixRegime + ")";
    }

    private String pct(double v) { return String.format("%.1f", v * 100); }
}
