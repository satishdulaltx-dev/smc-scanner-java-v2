package com.smcscanner.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataCacheTest {
    @Test
    void fiveMinuteBarsExpireJustAfterTheNextCandleClose() {
        Instant fetched = Instant.parse("2026-06-01T14:03:12Z");
        assertEquals(Instant.parse("2026-06-01T14:05:03Z"), DataCache.expiresAt("5m", fetched));
    }

    @Test
    void higherTimeframesDoNotRefreshEveryMinute() {
        Instant fetched = Instant.parse("2026-06-01T14:03:12Z");
        assertEquals(Instant.parse("2026-06-01T14:15:03Z"), DataCache.expiresAt("15m", fetched));
        assertEquals(Instant.parse("2026-06-01T15:00:03Z"), DataCache.expiresAt("60m", fetched));
        assertEquals(Instant.parse("2026-06-01T20:03:12Z"), DataCache.expiresAt("1d", fetched));
    }
}
