package com.smcscanner.data;

/** A historical run must never turn transport errors or partial pages into zero trades. */
public class HistoricalDataException extends RuntimeException {
    public HistoricalDataException(String message) { super(message); }
}
