package com.smcscanner.model;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Per-ticker detection overrides loaded from ticker-profiles.json.
 * Any field left null falls back to the global default.
 */
public class TickerProfile {
    private static final ZoneId    ET          = ZoneId.of("America/New_York");
    private static final LocalTime ORB_START   = LocalTime.of(9,  30);
    private static final LocalTime ORB_END     = LocalTime.of(10, 30);
    private static final LocalTime LUNCH_START = LocalTime.of(11, 30);
    private static final LocalTime LUNCH_END   = LocalTime.of(13, 30);

    private String  ticker;
    private boolean skip       = false;
    private String  skipReason = null;
    private String  note       = null;

    private String  strategyType  = "smc"; // "smc" | "vwap" | "breakout" | etc.

    // ── Time-of-day strategy routing ─────────────────────────────────────────
    // When set, overrides strategyType for specific time windows.
    // openStrategy:  9:30-10:30 (ORB / opening range breakout time)
    // primeStrategy: 10:30-11:30 and 13:30-15:30 (high-quality prime windows)
    // lunchStrategy: 11:30-13:30 (low volume drift window)
    // If any window strategy is null, falls back to strategyType.
    private String  openStrategy  = null;
    private String  primeStrategy = null;
    private String  lunchStrategy = null;

    // Detection overrides (null = use global default from SetupDetector constants)
    private Integer minConfidence = null; // overrides scanner.min-confidence
    private Integer maxConfidence = null; // upper cap — skip signals above this (blocks over-extended setups)
    private Double  minFvgPct     = null; // overrides MIN_FVG_PCT
    private Double  dispAtrMult   = null; // overrides DISP_ATR_MULT
    private Double  minVolMult    = null; // overrides MIN_VOL_MULT

    // KeyLevel exit overrides — ticker-specific stop/target sizing
    private Double  slAtrMult  = null; // SL = levelPrice ± atr * slAtrMult (default 0.5)
    private Double  tpRrRatio  = null; // TP = entry ± risk * tpRrRatio    (default 1.5)

    // Intraday Relative Strength gate — for mega-caps (AAPL, MSFT, etc.)
    // When true: only fire LONG if ticker outperforms SPY over rolling 30-min window
    //            only fire SHORT if ticker underperforms SPY over rolling 30-min window
    // This filters out noise trades in HFT-dominated stocks — the edge is in divergence from SPY.
    private Boolean intradayRsGate = null; // default false

    public static final TickerProfile DEFAULT = new TickerProfile();

    // ── Getters ───────────────────────────────────────────────────────────────
    public String  getTicker()        { return ticker; }
    public String  getStrategyType()  { return strategyType != null ? strategyType : "smc"; }
    public boolean isSkip()           { return skip; }
    public String  getSkipReason()    { return skipReason != null ? skipReason : "Profile marked skip=true"; }
    public String  getNote()          { return note; }
    public Integer getMinConfidence() { return minConfidence; }
    public Integer getMaxConfidence() { return maxConfidence; }
    public Double  getMinFvgPct()     { return minFvgPct; }
    public Double  getDispAtrMult()   { return dispAtrMult; }
    public Double  getMinVolMult()    { return minVolMult; }
    public Double  getTpRrRatio()     { return tpRrRatio; }

    /** Resolve effective minConfidence: use override if set, else fall back to globalDefault. */
    public int resolveMinConfidence(int globalDefault) {
        return minConfidence != null ? minConfidence : globalDefault;
    }
    /** Resolve effective maxConfidence: use override if set, else 100 (no cap). */
    public int resolveMaxConfidence() {
        return maxConfidence != null ? maxConfidence : 100;
    }
    public double resolveMinFvgPct(double globalDefault)   { return minFvgPct   != null ? minFvgPct   : globalDefault; }
    public double resolveDispAtrMult(double globalDefault) { return dispAtrMult != null ? dispAtrMult : globalDefault; }
    public double resolveMinVolMult(double globalDefault)  { return minVolMult  != null ? minVolMult  : globalDefault; }
    /** SL distance = levelPrice ± ATR * slAtrMult. Default 0.5 (half-ATR buffer above/below level). */
    public double resolveSlAtrMult()  { return slAtrMult != null ? slAtrMult : 0.5; }
    /** TP distance = entry ± risk * tpRrRatio. Default 1.5 (1.5:1 R:R). */
    public double resolveTpRrRatio()  { return tpRrRatio != null ? tpRrRatio : 1.5; }
    /** Whether this ticker requires intraday RS vs SPY divergence to fire signals. */
    public boolean isIntradayRsGate() { return intradayRsGate != null && intradayRsGate; }

    /**
     * Resolve which strategy to run based on the bar's epoch-ms timestamp.
     * Falls back to {@link #getStrategyType()} when no time-window override is configured.
     *
     * Time windows (ET):
     *   9:30–10:30  → openStrategy   (ORB / high-volume opening momentum)
     *   10:30–11:30 → primeStrategy  (institutional re-accumulation)
     *   11:30–13:30 → lunchStrategy  (low-volume drift; VWAP reversion works best)
     *   13:30–close → primeStrategy  (afternoon prime window)
     */
    public String resolveStrategyForTime(long barEpochMs) {
        LocalTime t = Instant.ofEpochMilli(barEpochMs).atZone(ET).toLocalTime();
        String base = getStrategyType();
        if (!t.isBefore(ORB_START) && t.isBefore(ORB_END))     return openStrategy  != null ? openStrategy  : base;
        if (!t.isBefore(LUNCH_START) && t.isBefore(LUNCH_END)) return lunchStrategy != null ? lunchStrategy : base;
        return primeStrategy != null ? primeStrategy : base;
    }

    /** Whether this profile has any time-of-day strategy overrides configured. */
    public boolean hasTimeRouting() {
        return openStrategy != null || primeStrategy != null || lunchStrategy != null;
    }

    // ── Setters (for Jackson) ─────────────────────────────────────────────────
    public void setTicker(String v)          { this.ticker = v; }
    public void setStrategyType(String v)    { this.strategyType = v; }
    public void setSkip(boolean v)           { this.skip = v; }
    public void setSkipReason(String v)      { this.skipReason = v; }
    public void setNote(String v)            { this.note = v; }
    public void setMinConfidence(Integer v)  { this.minConfidence = v; }
    public void setMaxConfidence(Integer v)  { this.maxConfidence = v; }
    public void setMinFvgPct(Double v)       { this.minFvgPct = v; }
    public void setDispAtrMult(Double v)     { this.dispAtrMult = v; }
    public void setMinVolMult(Double v)      { this.minVolMult = v; }
    public void setSlAtrMult(Double v)       { this.slAtrMult = v; }
    public void setTpRrRatio(Double v)       { this.tpRrRatio = v; }
    public void setIntradayRsGate(Boolean v) { this.intradayRsGate = v; }
    public void setOpenStrategy(String v)    { this.openStrategy = v; }
    public void setPrimeStrategy(String v)   { this.primeStrategy = v; }
    public void setLunchStrategy(String v)   { this.lunchStrategy = v; }
}
