package com.smcscanner.vision;

public record VisionSetup(
        String ticker,
        String direction,
        double entry,
        double stopLoss,
        double takeProfit,
        int    score,
        String pattern,
        String reason
) {
    public double rrRatio() {
        double risk   = Math.abs(entry - stopLoss);
        double reward = Math.abs(takeProfit - entry);
        return risk > 0 ? reward / risk : 0;
    }
}
