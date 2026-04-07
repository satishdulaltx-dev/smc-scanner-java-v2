package com.smcscanner.backtest;

public enum BacktestExitStyle {
    CLASSIC("Classic TP/SL + breakeven"),
    LIVE_PARITY("Live-style ATR trailing");

    private final String label;

    BacktestExitStyle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static BacktestExitStyle fromString(String s) {
        if (s == null || s.isBlank()) return CLASSIC;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CLASSIC;
        }
    }
}
