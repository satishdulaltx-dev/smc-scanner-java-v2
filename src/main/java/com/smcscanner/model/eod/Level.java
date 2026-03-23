package com.smcscanner.model.eod;

public class Level {
    private final double price;
    private final String label;
    private final Double high, low;
    private final double distancePts;   // distance from current price (signed: + = above, - = below)
    private final double distancePct;
    private final String strength;      // "IMMEDIATE" (<1 ATR), "NEAR" (1-2 ATR), "WATCH" (2-4 ATR)

    private Level(Builder b) {
        this.price = b.price; this.label = b.label;
        this.high = b.high; this.low = b.low;
        this.distancePts = b.distancePts; this.distancePct = b.distancePct;
        this.strength = b.strength;
    }

    public double getPrice()       { return price; }
    public String getLabel()       { return label; }
    public Double getHigh()        { return high; }
    public Double getLow()         { return low; }
    public double getDistancePts() { return distancePts; }
    public double getDistancePct() { return distancePct; }
    public String getStrength()    { return strength; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private double price; private String label; private Double high, low;
        private double distancePts, distancePct; private String strength = "WATCH";
        public Builder price(double v)       { this.price = v;       return this; }
        public Builder label(String v)       { this.label = v;       return this; }
        public Builder high(Double v)        { this.high = v;        return this; }
        public Builder low(Double v)         { this.low = v;         return this; }
        public Builder distancePts(double v) { this.distancePts = v; return this; }
        public Builder distancePct(double v) { this.distancePct = v; return this; }
        public Builder strength(String v)    { this.strength = v;    return this; }
        public Level build()                 { return new Level(this); }
    }
}
