package com.smcscanner.model;

public enum SetupPhase {
    IDLE,
    SWEEP_DETECTED,
    DISPLACEMENT_DETECTED,
    FVG_DETECTED,
    RETEST_DETECTED,
    INVALID_NO_DISP,
    INVALID_NO_FVG,
    INVALID_NO_RETEST,
    LOW_VOLATILITY,
    OUTSIDE_SESSION;

    public String phaseMsg() {
        return switch (this) {
            case IDLE                  -> "Waiting for breakout + sweep...";
            case SWEEP_DETECTED        -> "Liquidity sweep detected";
            case DISPLACEMENT_DETECTED -> "Displacement candle found";
            case FVG_DETECTED          -> "FVG created — waiting for retest";
            case RETEST_DETECTED       -> "FVG retest confirmed — setup valid";
            case INVALID_NO_DISP       -> "No displacement after sweep";
            case INVALID_NO_FVG        -> "No FVG after displacement";
            case INVALID_NO_RETEST     -> "No retest of FVG";
            case LOW_VOLATILITY        -> "Low volatility — skipped";
            case OUTSIDE_SESSION       -> "Outside session — skipped";
        };
    }
}
