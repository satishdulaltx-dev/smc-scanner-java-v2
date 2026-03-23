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

    private TradeSetup(Builder b) {
        this.ticker = b.ticker; this.direction = b.direction; this.session = b.session;
        this.volatility = b.volatility; this.entry = b.entry; this.stopLoss = b.stopLoss;
        this.takeProfit = b.takeProfit; this.atr = b.atr; this.fvgTop = b.fvgTop;
        this.fvgBottom = b.fvgBottom; this.confidence = b.confidence;
        this.hasBos = b.hasBos; this.hasChoch = b.hasChoch;
        this.timestamp = b.timestamp != null ? b.timestamp : LocalDateTime.now();
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
        return m;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String ticker, direction, session, volatility;
        private double entry, stopLoss, takeProfit, atr, fvgTop, fvgBottom;
        private int confidence;
        private boolean hasBos, hasChoch;
        private LocalDateTime timestamp;
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
        public TradeSetup build()              { return new TradeSetup(this); }
    }
}
