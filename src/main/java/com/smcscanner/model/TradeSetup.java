package com.smcscanner.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class TradeSetup {
    private final String ticker, direction, session, volatility;
    private final double entry, stopLoss, takeProfit, atr, fvgTop, fvgBottom;
    private final int confidence;
    private final boolean hasBos, hasChoch;
    private final LocalDateTime timestamp;

    // ── Options fields (populated by OptionsFlowAnalyzer) ───────────────────
    private final String optionsContract;     // e.g. "O:AAPL260404C00255000"
    private final String optionsType;         // "call" | "put"
    private final double optionsStrike;
    private final String optionsExpiry;
    private final double optionsPremium;      // estimated entry premium
    private final double optionsDelta;
    private final double optionsIV;
    private final int    optionsIVPct;        // IV percentile 0-100
    private final double optionsBreakEven;
    private final double optionsProfitPer;    // profit per contract at TP
    private final double optionsLossPer;      // loss per contract at SL
    private final double optionsRR;           // options risk/reward
    private final int    optionsSuggested;    // suggested # of contracts
    private final String optionsFlowLabel;    // e.g. "🟢 UNUSUAL CALL SWEEP 3.2:1"
    private final String optionsFlowDir;      // "BULLISH" | "BEARISH" | "NEUTRAL"
    private final double optionsMaxPain;

    private final String optionsGreeksWarning; // risk warnings from Greeks analysis

    // ── Performance intelligence fields ─────────────────────────────────────
    private final String factorBreakdown;   // e.g. "news+8 | RS-5 | regime-8 | corr+0"
    private final String convictionTier;    // "🔥 HIGH (2 contracts)" | "✅ STANDARD (1)" | "🟡 LITE (1)"
    private final String riskTier;          // position-sizing guidance based on confidence

    private TradeSetup(Builder b) {
        this.ticker = b.ticker; this.direction = b.direction; this.session = b.session;
        this.volatility = b.volatility; this.entry = b.entry; this.stopLoss = b.stopLoss;
        this.takeProfit = b.takeProfit; this.atr = b.atr; this.fvgTop = b.fvgTop;
        this.fvgBottom = b.fvgBottom; this.confidence = b.confidence;
        this.hasBos = b.hasBos; this.hasChoch = b.hasChoch;
        this.timestamp = b.timestamp != null ? b.timestamp : LocalDateTime.now();
        // Options
        this.optionsContract = b.optionsContract; this.optionsType = b.optionsType;
        this.optionsStrike = b.optionsStrike; this.optionsExpiry = b.optionsExpiry;
        this.optionsPremium = b.optionsPremium; this.optionsDelta = b.optionsDelta;
        this.optionsIV = b.optionsIV; this.optionsIVPct = b.optionsIVPct;
        this.optionsBreakEven = b.optionsBreakEven;
        this.optionsProfitPer = b.optionsProfitPer; this.optionsLossPer = b.optionsLossPer;
        this.optionsRR = b.optionsRR; this.optionsSuggested = b.optionsSuggested;
        this.optionsFlowLabel = b.optionsFlowLabel; this.optionsFlowDir = b.optionsFlowDir;
        this.optionsMaxPain = b.optionsMaxPain; this.optionsGreeksWarning = b.optionsGreeksWarning;
        this.factorBreakdown = b.factorBreakdown;
        this.convictionTier  = b.convictionTier;
        this.riskTier        = b.riskTier;
    }

    public String        getTicker()     { return ticker; }
    public String        getDirection()  { return direction; }
    public String        getSession()    { return session; }
    public String        getVolatility() { return volatility; }
    public double        getEntry()      { return entry; }
    public double        getStopLoss()   { return stopLoss; }
    public double        getTakeProfit() { return takeProfit; }
    public double        getAtr()        { return atr; }
    public double        getFvgTop()     { return fvgTop; }
    public double        getFvgBottom()  { return fvgBottom; }
    public int           getConfidence() { return confidence; }
    public boolean       isHasBos()      { return hasBos; }
    public boolean       isHasChoch()    { return hasChoch; }
    public LocalDateTime getTimestamp()  { return timestamp; }

    // Options getters
    public String getOptionsContract()   { return optionsContract; }
    public String getOptionsType()       { return optionsType; }
    public double getOptionsStrike()     { return optionsStrike; }
    public String getOptionsExpiry()     { return optionsExpiry; }
    public double getOptionsPremium()    { return optionsPremium; }
    public double getOptionsDelta()      { return optionsDelta; }
    public double getOptionsIV()         { return optionsIV; }
    public int    getOptionsIVPct()      { return optionsIVPct; }
    public double getOptionsBreakEven()  { return optionsBreakEven; }
    public double getOptionsProfitPer()  { return optionsProfitPer; }
    public double getOptionsLossPer()    { return optionsLossPer; }
    public double getOptionsRR()         { return optionsRR; }
    public int    getOptionsSuggested()  { return optionsSuggested; }
    public String getOptionsFlowLabel()  { return optionsFlowLabel; }
    public String getOptionsFlowDir()    { return optionsFlowDir; }
    public String getOptionsGreeksWarning() { return optionsGreeksWarning; }
    public String getFactorBreakdown()   { return factorBreakdown; }
    public String getConvictionTier()    { return convictionTier; }
    public String getRiskTier()          { return riskTier; }
    public double getOptionsMaxPain()    { return optionsMaxPain; }
    public boolean hasOptionsData()      { return optionsContract != null && optionsPremium > 0; }

    public double rrRatio() {
        double risk   = Math.abs(entry - stopLoss);
        double reward = Math.abs(takeProfit - entry);
        return risk > 0 ? reward / risk : 0.0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("ticker",      ticker);
        m.put("direction",   direction);
        m.put("entry",       entry);
        m.put("stop_loss",   stopLoss);
        m.put("take_profit", takeProfit);
        m.put("confidence",  confidence);
        m.put("session",     session);
        m.put("volatility",  volatility);
        m.put("atr",         atr);
        m.put("has_bos",     hasBos);
        m.put("fvg_top",     fvgTop);
        m.put("fvg_bottom",  fvgBottom);
        m.put("rr",          Math.round(rrRatio() * 10.0) / 10.0);
        m.put("timestamp",   timestamp != null ? timestamp.toString() : "");
        // Options fields
        if (optionsContract != null) {
            m.put("options_contract",   optionsContract);
            m.put("options_type",       optionsType);
            m.put("options_strike",     optionsStrike);
            m.put("options_expiry",     optionsExpiry);
            m.put("options_premium",    optionsPremium);
            m.put("options_delta",      optionsDelta);
            m.put("options_iv",         optionsIV);
            m.put("options_iv_pct",     optionsIVPct);
            m.put("options_break_even", optionsBreakEven);
            m.put("options_profit_per", optionsProfitPer);
            m.put("options_loss_per",   optionsLossPer);
            m.put("options_rr",         optionsRR);
            m.put("options_suggested",  optionsSuggested);
            m.put("options_flow_label", optionsFlowLabel);
            m.put("options_flow_dir",   optionsFlowDir);
            m.put("options_max_pain",   optionsMaxPain);
        }
        return m;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String ticker, direction, session, volatility;
        private double entry, stopLoss, takeProfit, atr, fvgTop, fvgBottom;
        private int confidence;
        private boolean hasBos, hasChoch;
        private LocalDateTime timestamp;
        // Options
        private String optionsContract, optionsType, optionsExpiry, optionsFlowLabel, optionsFlowDir;
        private double optionsStrike, optionsPremium, optionsDelta, optionsIV, optionsBreakEven;
        private double optionsProfitPer, optionsLossPer, optionsRR, optionsMaxPain;
        private int optionsIVPct, optionsSuggested;
        private String optionsGreeksWarning;
        private String factorBreakdown, convictionTier, riskTier;

        public Builder ticker(String v)        { this.ticker = v;      return this; }
        public Builder direction(String v)     { this.direction = v;   return this; }
        public Builder session(String v)       { this.session = v;     return this; }
        public Builder volatility(String v)    { this.volatility = v;  return this; }
        public Builder entry(double v)         { this.entry = v;       return this; }
        public Builder stopLoss(double v)      { this.stopLoss = v;    return this; }
        public Builder takeProfit(double v)    { this.takeProfit = v;  return this; }
        public Builder atr(double v)           { this.atr = v;         return this; }
        public Builder fvgTop(double v)        { this.fvgTop = v;      return this; }
        public Builder fvgBottom(double v)     { this.fvgBottom = v;   return this; }
        public Builder confidence(int v)       { this.confidence = v;  return this; }
        public Builder hasBos(boolean v)       { this.hasBos = v;      return this; }
        public Builder hasChoch(boolean v)     { this.hasChoch = v;    return this; }
        public Builder timestamp(LocalDateTime v){ this.timestamp = v; return this; }
        // Options builder methods
        public Builder optionsContract(String v)   { this.optionsContract = v;   return this; }
        public Builder optionsType(String v)       { this.optionsType = v;       return this; }
        public Builder optionsStrike(double v)     { this.optionsStrike = v;     return this; }
        public Builder optionsExpiry(String v)     { this.optionsExpiry = v;     return this; }
        public Builder optionsPremium(double v)    { this.optionsPremium = v;    return this; }
        public Builder optionsDelta(double v)      { this.optionsDelta = v;      return this; }
        public Builder optionsIV(double v)         { this.optionsIV = v;         return this; }
        public Builder optionsIVPct(int v)         { this.optionsIVPct = v;      return this; }
        public Builder optionsBreakEven(double v)  { this.optionsBreakEven = v;  return this; }
        public Builder optionsProfitPer(double v)  { this.optionsProfitPer = v;  return this; }
        public Builder optionsLossPer(double v)    { this.optionsLossPer = v;    return this; }
        public Builder optionsRR(double v)         { this.optionsRR = v;        return this; }
        public Builder optionsSuggested(int v)     { this.optionsSuggested = v;  return this; }
        public Builder optionsFlowLabel(String v)  { this.optionsFlowLabel = v;  return this; }
        public Builder optionsFlowDir(String v)    { this.optionsFlowDir = v;    return this; }
        public Builder optionsMaxPain(double v)    { this.optionsMaxPain = v;    return this; }
        public Builder optionsGreeksWarning(String v) { this.optionsGreeksWarning = v; return this; }
        public Builder factorBreakdown(String v)   { this.factorBreakdown = v;   return this; }
        public Builder convictionTier(String v)    { this.convictionTier = v;    return this; }
        public Builder riskTier(String v)          { this.riskTier = v;          return this; }
        public TradeSetup build() {
            // Sanitize: replace NaN/Infinity with 0.0 to prevent propagation
            this.entry      = sanitize(this.entry);
            this.stopLoss   = sanitize(this.stopLoss);
            this.takeProfit = sanitize(this.takeProfit);
            this.atr        = sanitize(this.atr);
            this.fvgTop     = sanitize(this.fvgTop);
            this.fvgBottom  = sanitize(this.fvgBottom);
            this.optionsStrike    = sanitize(this.optionsStrike);
            this.optionsPremium   = sanitize(this.optionsPremium);
            this.optionsBreakEven = sanitize(this.optionsBreakEven);
            return new TradeSetup(this);
        }

        private static double sanitize(double v) {
            return Double.isFinite(v) ? v : 0.0;
        }
    }
}
