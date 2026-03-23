package com.smcscanner.model;

public class StructureBreak {
    private final int index;
    private final StructureType breakType;
    private final double price;
    private final int priorSwingIdx;
    private final boolean confirmed;
    private final String timestamp;

    private StructureBreak(Builder b) {
        this.index = b.index; this.breakType = b.breakType; this.price = b.price;
        this.priorSwingIdx = b.priorSwingIdx; this.confirmed = b.confirmed; this.timestamp = b.timestamp;
    }

    public int           getIndex()        { return index; }
    public StructureType getBreakType()    { return breakType; }
    public double        getPrice()        { return price; }
    public int           getPriorSwingIdx(){ return priorSwingIdx; }
    public boolean       isConfirmed()     { return confirmed; }
    public String        getTimestamp()    { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int index; private StructureType breakType; private double price;
        private int priorSwingIdx; private boolean confirmed; private String timestamp;
        public Builder index(int v)              { this.index = v;        return this; }
        public Builder breakType(StructureType v){ this.breakType = v;    return this; }
        public Builder price(double v)           { this.price = v;        return this; }
        public Builder priorSwingIdx(int v)      { this.priorSwingIdx = v;return this; }
        public Builder confirmed(boolean v)      { this.confirmed = v;    return this; }
        public Builder timestamp(String v)       { this.timestamp = v;    return this; }
        public StructureBreak build()            { return new StructureBreak(this); }
    }

    @Override public String toString() {
        return String.format("StructureBreak(idx=%d, type=%s, price=%.4f)", index, breakType, price);
    }
}
