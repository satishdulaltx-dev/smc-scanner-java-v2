package com.smcscanner.model.indicator;

public class CorrelationResult {
    private final double betaSpy;
    private final double corrSpy;
    private final double betaQqq;
    private final double corrQqq;

    public CorrelationResult(double betaSpy, double corrSpy, double betaQqq, double corrQqq) {
        this.betaSpy = betaSpy; this.corrSpy = corrSpy;
        this.betaQqq = betaQqq; this.corrQqq = corrQqq;
    }
    public double getBetaSpy() { return betaSpy; }
    public double getCorrSpy() { return corrSpy; }
    public double getBetaQqq() { return betaQqq; }
    public double getCorrQqq() { return corrQqq; }
}
