package com.smcscanner.model;

import java.util.ArrayList;
import java.util.List;

public class LiquiditySweep {
    private final int index;
    private final String levelType, direction;
    private final double price;
    private final List<SwingPoint> sweptSwings;
    private final String timestamp;

    private LiquiditySweep(Builder b) {
        this.index = b.index; this.levelType = b.levelType; this.direction = b.direction;
        this.price = b.price; this.sweptSwings = b.sweptSwings; this.timestamp = b.timestamp;
    }

    public int              getIndex()       { return index; }
    public String           getLevelType()   { return levelType; }
    public String           getDirection()   { return direction; }
    public double           getPrice()       { return price; }
    public List<SwingPoint> getSweptSwings() { return sweptSwings; }
    public String           getTimestamp()   { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int index; private String levelType, direction, timestamp;
        private double price; private List<SwingPoint> sweptSwings = new ArrayList<>();
        public Builder index(int v)                   { this.index = v;       return this; }
        public Builder levelType(String v)            { this.levelType = v;   return this; }
        public Builder direction(String v)            { this.direction = v;   return this; }
        public Builder price(double v)                { this.price = v;       return this; }
        public Builder sweptSwings(List<SwingPoint> v){ this.sweptSwings = v; return this; }
        public Builder timestamp(String v)            { this.timestamp = v;   return this; }
        public LiquiditySweep build()                 { return new LiquiditySweep(this); }
    }

    @Override public String toString() {
        return String.format("LiquiditySweep(idx=%d, dir=%s, price=%.4f)", index, direction, price);
    }
}
