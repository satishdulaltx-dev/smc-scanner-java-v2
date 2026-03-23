package com.smcscanner.model;

public class FairValueGap {
    private final int index;
    private final double high, low;
    private final String direction;
    private final String timestamp;
    private boolean filled;
    private Integer fillIndex;
    private Double fillPrice;

    private FairValueGap(Builder b) {
        this.index = b.index; this.high = b.high; this.low = b.low;
        this.direction = b.direction; this.timestamp = b.timestamp;
        this.filled = b.filled; this.fillIndex = b.fillIndex; this.fillPrice = b.fillPrice;
    }

    public int     getIndex()     { return index; }
    public double  getHigh()      { return high; }
    public double  getLow()       { return low; }
    public String  getDirection() { return direction; }
    public String  getTimestamp() { return timestamp; }
    public boolean isFilled()     { return filled; }
    public Integer getFillIndex() { return fillIndex; }
    public Double  getFillPrice() { return fillPrice; }

    public void setFilled(boolean v)    { this.filled = v; }
    public void setFillIndex(Integer v) { this.fillIndex = v; }
    public void setFillPrice(Double v)  { this.fillPrice = v; }

    public double midpoint() { return (high + low) / 2.0; }
    public double size()     { return Math.abs(high - low); }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int index; private double high, low; private String direction, timestamp;
        private boolean filled; private Integer fillIndex; private Double fillPrice;
        public Builder index(int v)       { this.index = v;      return this; }
        public Builder high(double v)     { this.high = v;       return this; }
        public Builder low(double v)      { this.low = v;        return this; }
        public Builder direction(String v){ this.direction = v;  return this; }
        public Builder timestamp(String v){ this.timestamp = v;  return this; }
        public Builder filled(boolean v)  { this.filled = v;     return this; }
        public FairValueGap build()       { return new FairValueGap(this); }
    }

    @Override public String toString() {
        return String.format("FairValueGap(idx=%d, dir=%s, size=%.4f, filled=%b)", index, direction, size(), filled);
    }
}
