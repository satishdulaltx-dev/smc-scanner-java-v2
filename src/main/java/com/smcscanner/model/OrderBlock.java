package com.smcscanner.model;

public class OrderBlock {
    private final int index;
    private final double high, low;
    private final int openBarIdx;
    private final String direction, timestamp;
    private boolean mitigated;
    private Integer mitigationIndex;
    private int touchCount;

    private OrderBlock(Builder b) {
        this.index = b.index; this.high = b.high; this.low = b.low;
        this.openBarIdx = b.openBarIdx; this.direction = b.direction;
        this.timestamp = b.timestamp; this.mitigated = b.mitigated;
        this.mitigationIndex = b.mitigationIndex; this.touchCount = b.touchCount;
    }

    public int     getIndex()           { return index; }
    public double  getHigh()            { return high; }
    public double  getLow()             { return low; }
    public int     getOpenBarIdx()      { return openBarIdx; }
    public String  getDirection()       { return direction; }
    public String  getTimestamp()       { return timestamp; }
    public boolean isMitigated()        { return mitigated; }
    public Integer getMitigationIndex() { return mitigationIndex; }
    public int     getTouchCount()      { return touchCount; }

    public void setMitigated(boolean v)        { this.mitigated = v; }
    public void setMitigationIndex(Integer v)  { this.mitigationIndex = v; }
    public void setTouchCount(int v)           { this.touchCount = v; }

    public double midpoint() { return (high + low) / 2.0; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int index, openBarIdx, touchCount;
        private double high, low;
        private String direction, timestamp;
        private boolean mitigated;
        private Integer mitigationIndex;
        public Builder index(int v)            { this.index = v;           return this; }
        public Builder high(double v)          { this.high = v;            return this; }
        public Builder low(double v)           { this.low = v;             return this; }
        public Builder openBarIdx(int v)       { this.openBarIdx = v;      return this; }
        public Builder direction(String v)     { this.direction = v;       return this; }
        public Builder timestamp(String v)     { this.timestamp = v;       return this; }
        public Builder mitigated(boolean v)    { this.mitigated = v;       return this; }
        public Builder touchCount(int v)       { this.touchCount = v;      return this; }
        public OrderBlock build()              { return new OrderBlock(this); }
    }

    @Override public String toString() {
        return String.format("OrderBlock(idx=%d, dir=%s, low=%.2f, high=%.2f)", index, direction, low, high);
    }
}
