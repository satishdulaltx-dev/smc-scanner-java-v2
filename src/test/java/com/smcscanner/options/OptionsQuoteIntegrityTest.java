package com.smcscanner.options;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptionsQuoteIntegrityTest {
    private static final long NOW = Instant.parse("2026-06-01T14:00:00Z").toEpochMilli();

    private OptionsDataService.ContractData contract(String name, double ask, double bid, double high,
                                                    double low, long timestamp, String timeframe) {
        return new OptionsDataService.ContractData(name, "call", 100,
                LocalDate.now(ZoneOffset.UTC).plusDays(10).toString(), 100,
                200, 1, high, low, 1, ask, bid, 1000, .3, .45, .01, -.01, .01, 100,
                timestamp, timeframe, 10, 10);
    }

    @Test
    void invalidStaleDelayedOrMissingQuotesCannotBecomeRecommendations() {
        long now = System.currentTimeMillis();
        for (var c : List.of(
                contract("missing", 0, 0, 2, 1, now, "REAL-TIME"),
                contract("crossed", 1, 1.1, 2, 1, now, "REAL-TIME"),
                contract("nan", Double.NaN, 1, 2, 1, now, "REAL-TIME"),
                contract("stale", 1.1, 1, 2, 1, now - 60_000, "REAL-TIME"),
                contract("future", 1.1, 1, 2, 1, now + 60_000, "REAL-TIME"),
                contract("delayed", 1.1, 1, 2, 1, now, "DELAYED"),
                contract("no-time", 1.1, 1, 2, 1, 0, "REAL-TIME"))) {
            assertFalse(analyzer(List.of(c)).recommendContract("TEST", "long", 100, 99, 102).hasData(), c.contractTicker());
        }
    }

    @Test
    void tightActualSpreadWinsEvenWhenItsDailyRangeIsWide() {
        long now = System.currentTimeMillis();
        var wideQuote = contract("wide-quote", 1.30, .70, 1.01, 1, now, "REAL-TIME");
        var tightQuote = contract("tight-quote", 1.05, 1.04, 3, .50, now, "REAL-TIME");
        var rec = analyzer(List.of(wideQuote, tightQuote)).recommendContract("TEST", "long", 100, 99, 102);
        assertEquals("tight-quote", rec.contractTicker());
        assertEquals(1.05, rec.estimatedPremium());
    }

    @Test
    void freshnessBoundaryIsMeasuredFromProviderTimestamp() {
        var c = contract("sample", 1.1, 1, 2, 1, NOW, "REAL-TIME");
        assertTrue(c.hasUsableQuote(NOW + 30_000));
        assertFalse(c.hasUsableQuote(NOW + 30_001));
    }

    @Test
    void snapshotDoesNotInventHistoricalIvPercentileOrPositionSize() {
        var rec=analyzer(List.of(contract("sample",1.05,1.04,3,.5,System.currentTimeMillis(),"REAL-TIME")))
                .recommendContract("TEST","long",100,99,102);
        assertTrue(rec.hasData());assertEquals(-1,rec.ivPercentile());assertEquals(0,rec.suggestedContracts());
        assertFalse(String.valueOf(rec.greeksWarning()).contains("selling premium"));
    }

    private OptionsFlowAnalyzer analyzer(List<OptionsDataService.ContractData> contracts) {
        OptionsDataService data = mock(OptionsDataService.class);
        when(data.getOptionsChain(anyString(), anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(contracts);
        return new OptionsFlowAnalyzer(data, mock(PolygonClient.class));
    }

    private String page(String ticker, String next) {
        return """
                {"status":"OK","results":[{"details":{"ticker":"%s","contract_type":"call",
                "strike_price":100,"expiration_date":"2026-06-12","shares_per_contract":100},
                "last_quote":{"ask":1.1,"bid":1,"ask_size":10,"bid_size":10,
                "last_updated":%d,"timeframe":"REAL-TIME"}}]%s}
                """.formatted(ticker, NOW * 1_000_000, next == null ? "" : ",\"next_url\":\"" + next + "\"");
    }

    private OptionsDataService service(Interceptor interceptor, Clock clock) {
        ScannerConfig config = new ScannerConfig();
        config.setPolygonApiKey("test-secret");
        return new OptionsDataService(config, new OkHttpClient.Builder().addInterceptor(interceptor).build(), clock);
    }

    private Response response(Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }

    @Test
    void pagesAreFetchedOnceAndQuoteTimestampIsParsed() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(chain -> {
            assertNull(chain.request().url().queryParameter("apiKey"));
            assertEquals("Bearer test-secret", chain.request().header("Authorization"));
            int n = calls.incrementAndGet();
            return response(chain.request(), 200, page("contract-" + n,
                    n == 1 ? "https://api.polygon.io/next?cursor=two&apiKey=test-secret" : null));
        }, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        var result = service.getOptionsChain("TEST", 100, 8, 7, 21);
        assertEquals(2, calls.get());
        assertEquals(2, result.size());
        assertTrue(result.get(0).hasUsableQuote(NOW));
        service.getOptionsChain("TEST", 100, 8, 7, 21);
        assertEquals(2, calls.get());
    }

    @Test
    void cacheSeparatesStrikeAndExpiryQueriesAndExpires() {
        List<HttpUrl> requests = new ArrayList<>();
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(NOW);
        when(clock.instant()).thenReturn(Instant.ofEpochMilli(NOW));
        when(clock.withZone(ZoneOffset.UTC)).thenReturn(clock);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var service = service(chain -> {
            requests.add(chain.request().url());
            return response(chain.request(), 200, page("contract", null));
        }, clock);
        service.getOptionsChain("TEST", 100, 8, 5, 21);
        service.getOptionsChain("TEST", 100, 8, 7, 21);
        service.getOptionsChain("TEST", 100, 9, 7, 21);
        assertEquals(3, requests.size());
        assertEquals("2026-06-08", requests.get(1).queryParameter("expiration_date.gte"));
        assertEquals("91.0", requests.get(2).queryParameter("strike_price.gte"));
        service.getOptionsChain("TEST", 100, 9, 7, 21);
        assertEquals(3, requests.size());
        when(clock.millis()).thenReturn(NOW + 15_001);
        service.getOptionsChain("TEST", 100, 9, 7, 21);
        assertEquals(4, requests.size());
    }

    @Test
    void failedSecondPageDiscardsIncompleteChainAndCoolsDownOtherTickers() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(chain -> {
            int n = calls.incrementAndGet();
            return response(chain.request(), n == 1 ? 200 : 429,
                    n == 1 ? page("first", "https://api.polygon.io/next") : "{}");
        }, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        assertTrue(service.getOptionsChain("TEST", 100, 8, 7, 21).isEmpty());
        assertTrue(service.getOptionsChain("OTHER", 100, 8, 7, 21).isEmpty());
        assertEquals(2, calls.get());
    }

    @Test
    void paginationCannotForwardCredentialsToAnotherHost() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 200, page("first", "https://example.com/next"));
        }, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        assertTrue(service.getOptionsChain("TEST", 100, 8, 7, 21).isEmpty());
        assertEquals(1, calls.get());
    }
}
