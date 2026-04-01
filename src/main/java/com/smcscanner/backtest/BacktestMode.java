package com.smcscanner.backtest;

/**
 * Backtest strategy mode — controls which setups are detected and
 * how long positions are held before timing out.
 */
public enum BacktestMode {
    /** 5m bars, intraday setups, max ~4h hold (48 bars) */
    INTRADAY(48, "Intraday (5m)"),

    /** Daily/hourly bars, swing setups, max 2 trading days of forward bars */
    SWING(Integer.MAX_VALUE, "Swing (daily)"),

    /** Same as intraday but focuses on options P&L metrics */
    OPTIONS(48, "Options (5m + options P&L)"),

    /** Legacy: no mode filter, uses all forward bars (default) */
    ALL(Integer.MAX_VALUE, "All");

    private final int maxForwardBars;
    private final String label;

    BacktestMode(int maxForwardBars, String label) {
        this.maxForwardBars = maxForwardBars;
        this.label = label;
    }

    public int maxForwardBars() { return maxForwardBars; }
    public String label() { return label; }

    public static BacktestMode fromString(String s) {
        if (s == null || s.isBlank()) return ALL;
        try { return valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return ALL; }
    }
}
