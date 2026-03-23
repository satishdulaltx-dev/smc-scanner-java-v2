package com.smcscanner.model;

public class TickerStatus {
    private final String ticker, status, direction, phaseMsg;
    private final int confidence;

    private TickerStatus(Builder b) {
        this.ticker = b.ticker; this.status = b.status; this.direction = b.direction;
        this.phaseMsg = b.phaseMsg; this.confidence = b.confidence;
    }

    public String getTicker()     { return ticker; }
    public String getStatus()     { return status; }
    public String getDirection()  { return direction; }
    public String getPhaseMsg()   { return phaseMsg; }
    public int    getConfidence() { return confidence; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String ticker, status, direction, phaseMsg; private int confidence;
        public Builder ticker(String v)     { this.ticker = v;     return this; }
        public Builder status(String v)     { this.status = v;     return this; }
        public Builder direction(String v)  { this.direction = v;  return this; }
        public Builder phaseMsg(String v)   { this.phaseMsg = v;   return this; }
        public Builder confidence(int v)    { this.confidence = v; return this; }
        public TickerStatus build()         { return new TickerStatus(this); }
    }
}
