package com.smcscanner.model;

public class OHLCV {
    private final String timestamp;
    private final double open, high, low, close, volume;

    private OHLCV(Builder b) {
        this.timestamp = b.timestamp;
        this.open = b.open; this.high = b.high; this.low = b.low;
        this.close = b.close; this.volume = b.volume;
    }

    public String getTimestamp() { return timestamp; }
    public double getOpen()      { return open; }
    public double getHigh()      { return high; }
    public double getLow()       { return low; }
    public double getClose()     { return close; }
    public double getVolume()    { return volume; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String timestamp; private double open, high, low, close, volume;
        public Builder timestamp(String v) { this.timestamp = v; return this; }
        public Builder open(double v)      { this.open = v;      return this; }
        public Builder high(double v)      { this.high = v;      return this; }
        public Builder low(double v)       { this.low = v;       return this; }
        public Builder close(double v)     { this.close = v;     return this; }
        public Builder volume(double v)    { this.volume = v;    return this; }
        public OHLCV build()               { return new OHLCV(this); }
    }
}
