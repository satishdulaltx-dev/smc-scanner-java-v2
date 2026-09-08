package com.smcscanner.backtest;

public enum BacktestExitStyle {
    FIXED_R("Fixed stop and 2R target"),
    TRAIL_3R("Experimental: 3R target, trailing arms at 2.5R"),
    CLASSIC("Classic TP/SL + breakeven"),
    HYBRID("Profile target; trail arms at 2R scalp / 2.5R other"),
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
