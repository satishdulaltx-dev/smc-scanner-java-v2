package com.smcscanner.model;

public class SwingPoint {
    private final int index;
    private final double price;
    private final SwingType swingType;
    private final String timestamp;

    private SwingPoint(Builder b) {
        this.index = b.index; this.price = b.price;
        this.swingType = b.swingType; this.timestamp = b.timestamp;
    }

    public int       getIndex()     { return index; }
    public double    getPrice()     { return price; }
    public SwingType getSwingType() { return swingType; }
    public String    getTimestamp() { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int index; private double price;
        private SwingType swingType; private String timestamp;
        public Builder index(int v)          { this.index = v;     return this; }
        public Builder price(double v)       { this.price = v;     return this; }
        public Builder swingType(SwingType v){ this.swingType = v; return this; }
        public Builder timestamp(String v)   { this.timestamp = v; return this; }
        public SwingPoint build()            { return new SwingPoint(this); }
    }

    @Override public String toString() {
        return String.format("SwingPoint(idx=%d, price=%.4f, type=%s)", index, price, swingType);
    }
}
