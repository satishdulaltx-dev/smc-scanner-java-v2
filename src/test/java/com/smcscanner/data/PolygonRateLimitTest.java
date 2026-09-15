package com.smcscanner.data;

import com.smcscanner.config.ScannerConfig;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PolygonRateLimitTest {
    @Test
    void oneRejectedTickerPausesOtherTickersAndMicrostructureUntilRetryAfter() {
        verifyCooldown("120", 120_000L);
    }

    @Test
    void httpDateRetryAfterIsHonored() {
        verifyCooldown("Mon, 01 Jun 2026 14:02:00 GMT", 120_000L);
    }

    @Test
    void invalidRetryAfterUsesOneMinuteCooldown() {
        verifyCooldown("invalid", 60_000L);
    }

    private void verifyCooldown(String retryAfter, long delay) {
        long now = Instant.parse("2026-06-01T14:00:00Z").toEpochMilli();
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(now);
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            int code = requests.incrementAndGet() == 1 ? 429 : 200;
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").header("Retry-After", retryAfter)
                    .body(ResponseBody.create("{\"results\":[]}", MediaType.get("application/json"))).build();
        }).build();
        ScannerConfig config = new ScannerConfig();
        config.setPolygonApiKey("test-only");
        PolygonClient client = new PolygonClient(config, new DataCache(), http, clock);
        assertTrue(client.getBars("AMD", "5m", 20).isEmpty());
        assertTrue(client.getBars("TSLA", "5m", 20).isEmpty());
        assertNull(client.getNbbo("AMD"));
        assertTrue(client.getRecentQuotes("AMD", 10).isEmpty());
        assertTrue(client.getRecentTrades("AMD", 10).isEmpty());
        assertEquals(1, requests.get());
        when(clock.millis()).thenReturn(now + delay - 1);
        client.getBars("AMD", "5m", 20);
        assertEquals(1, requests.get());
        when(clock.millis()).thenReturn(now + delay);
        client.getBars("AMD", "5m", 20);
        assertEquals(2, requests.get());
    }
}
