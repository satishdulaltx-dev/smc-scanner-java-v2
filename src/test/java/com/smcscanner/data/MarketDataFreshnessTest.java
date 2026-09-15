package com.smcscanner.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketDataFreshnessTest {
    @Test
    void permitsLatestCompletedBarAndSmallPublicationDelay() {
        long now=1_800_000_000_000L, interval=300_000L;
        assertTrue(MarketDataFreshness.isFreshCompletedBar(now-interval,now,interval));
        assertTrue(MarketDataFreshness.isFreshCompletedBar(now-2*interval-30_000,now,interval));
        assertFalse(MarketDataFreshness.isFreshCompletedBar(now-2*interval-30_001,now,interval));
    }

    @Test
    void rejectsDelayedYesterdayAndUnfinishedBars() {
        long now=1_800_000_000_000L, interval=300_000L;
        assertFalse(MarketDataFreshness.isFreshCompletedBar(now-15*60_000,now,interval));
        assertFalse(MarketDataFreshness.isFreshCompletedBar(now-86_400_000,now,interval));
        assertFalse(MarketDataFreshness.isFreshCompletedBar(now-interval+1,now,interval));
        assertFalse(MarketDataFreshness.isFreshCompletedBar(now+1,now,interval));
    }
}
