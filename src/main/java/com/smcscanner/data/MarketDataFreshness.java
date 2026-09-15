package com.smcscanner.data;

/** A live decision needs a recently completed bar, regardless of credential validity. */
public final class MarketDataFreshness {
    private MarketDataFreshness() {}

    public static boolean isFreshCompletedBar(long openMs, long nowMs, long intervalMs) {
        if (openMs <= 0 || intervalMs <= 0 || openMs > nowMs) return false;
        long age = nowMs - openMs;
        // A completed bar remains the latest one until the next close. Allow
        // 30 seconds for publication/network delay beyond that boundary.
        return age >= intervalMs && age - intervalMs <= intervalMs + 30_000L;
    }
}
