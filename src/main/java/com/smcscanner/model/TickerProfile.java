package com.smcscanner.model;

/**
 * Per-ticker detection overrides loaded from ticker-profiles.json.
 * Any field left null falls back to the global default.
 */
public class TickerProfile {
    private String  ticker;
    private boolean skip       = false;
    private String  skipReason = null;
    private String  note       = null;

    private String  strategyType  = "smc"; // "smc" | "vwap" | "breakout"

    // Detection overrides (null = use global default from SetupDetector constants)
    private Integer minConfidence = null; // overrides scanner.min-confidence
    private Double  minFvgPct     = null; // overrides MIN_FVG_PCT
    private Double  dispAtrMult   = null; // overrides DISP_ATR_MULT
    private Double  minVolMult    = null; // overrides MIN_VOL_MULT

    public static final TickerProfile DEFAULT = new TickerProfile();

    // ── Getters ───────────────────────────────────────────────────────────────
    public String  getTicker()        { return ticker; }
    public String  getStrategyType()  { return strategyType != null ? strategyType : "smc"; }
    public boolean isSkip()           { return skip; }
    public String  getSkipReason()    { return skipReason != null ? skipReason : "Profile marked skip=true"; }
    public String  getNote()          { return note; }
    public Integer getMinConfidence() { return minConfidence; }
    public Double  getMinFvgPct()     { return minFvgPct; }
    public Double  getDispAtrMult()   { return dispAtrMult; }
    public Double  getMinVolMult()    { return minVolMult; }

    /** Resolve effective minConfidence: use override if set, else fall back to globalDefault. */
    public int resolveMinConfidence(int globalDefault) {
        return minConfidence != null ? minConfidence : globalDefault;
    }
    public double resolveMinFvgPct(double globalDefault)   { return minFvgPct   != null ? minFvgPct   : globalDefault; }
    public double resolveDispAtrMult(double globalDefault) { return dispAtrMult != null ? dispAtrMult : globalDefault; }
    public double resolveMinVolMult(double globalDefault)  { return minVolMult  != null ? minVolMult  : globalDefault; }

    // ── Setters (for Jackson) ─────────────────────────────────────────────────
    public void setTicker(String v)        { this.ticker = v; }
    public void setStrategyType(String v)  { this.strategyType = v; }
    public void setSkip(boolean v)         { this.skip = v; }
    public void setSkipReason(String v)    { this.skipReason = v; }
    public void setNote(String v)          { this.note = v; }
    public void setMinConfidence(Integer v){ this.minConfidence = v; }
    public void setMinFvgPct(Double v)     { this.minFvgPct = v; }
    public void setDispAtrMult(Double v)   { this.dispAtrMult = v; }
    public void setMinVolMult(Double v)    { this.minVolMult = v; }
}
