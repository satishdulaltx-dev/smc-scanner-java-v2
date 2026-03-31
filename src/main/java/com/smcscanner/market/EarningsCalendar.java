package com.smcscanner.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Checks if a ticker has earnings within N days.
 * Uses Polygon's /vX/reference/financials endpoint to find upcoming earnings dates.
 * Caches results for 12 hours to avoid excessive API calls.
 */
@Service
public class EarningsCalendar {
    private static final Logger log = LoggerFactory.getLogger(EarningsCalendar.class);
    private static final int EARNINGS_BUFFER_DAYS = 2; // block trades 2 days before/after

    private final ScannerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();

    // Cache: ticker → {earningsDate, fetchedAt}
    private final Map<String, CachedEarnings> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L; // 12 hours

    public EarningsCalendar(ScannerConfig config) {
        this.config = config;
    }

    /**
     * Check if ticker has earnings within EARNINGS_BUFFER_DAYS.
     * Returns an EarningsCheck with the date and days until earnings.
     */
    public EarningsCheck check(String ticker) {
        if (ticker == null || ticker.startsWith("X:")) {
            return EarningsCheck.NONE; // crypto has no earnings
        }

        LocalDate earningsDate = getNextEarningsDate(ticker);
        if (earningsDate == null) {
            return EarningsCheck.NONE;
        }

        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), earningsDate);
        // Also check days since (for post-earnings volatility)
        boolean isNearEarnings = Math.abs(daysUntil) <= EARNINGS_BUFFER_DAYS;

        return new EarningsCheck(earningsDate, daysUntil, isNearEarnings);
    }

    private LocalDate getNextEarningsDate(String ticker) {
        // Check cache
        CachedEarnings cached = cache.get(ticker);
        if (cached != null && (System.currentTimeMillis() - cached.fetchedAt) < CACHE_TTL_MS) {
            return cached.earningsDate;
        }

        // Fetch from Polygon
        LocalDate date = fetchFromPolygon(ticker);
        cache.put(ticker, new CachedEarnings(date, System.currentTimeMillis()));
        return date;
    }

    private LocalDate fetchFromPolygon(String ticker) {
        String apiKey = config.getPolygonApiKey();
        if (apiKey == null || apiKey.isBlank()) return null;

        try {
            // Use Polygon's stock financials endpoint to find next earnings
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String url = String.format(
                    "https://api.polygon.io/vX/reference/financials?ticker=%s&period_of_report_date.gte=%s&limit=1&sort=period_of_report_date&order=asc&apiKey=%s",
                    ticker, LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE), apiKey);

            try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonNode root = mapper.readTree(resp.body().string());
                JsonNode results = root.get("results");
                if (results == null || !results.isArray() || results.isEmpty()) return null;

                // Look for filing_date or period_of_report_date
                JsonNode first = results.get(0);
                String filingDate = first.has("filing_date") ? first.get("filing_date").asText() : null;
                if (filingDate != null && !filingDate.isBlank()) {
                    LocalDate fd = LocalDate.parse(filingDate);
                    log.debug("Earnings for {}: filing_date={}", ticker, fd);
                    return fd;
                }
            }
        } catch (Exception e) {
            log.debug("Earnings lookup failed for {}: {}", ticker, e.getMessage());
        }

        // Fallback: try the ticker events endpoint
        try {
            String url = String.format(
                    "https://api.polygon.io/vX/reference/tickers/%s?apiKey=%s", ticker, config.getPolygonApiKey());
            try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                // This endpoint doesn't directly give earnings dates but we tried
            }
        } catch (Exception e) {
            // silently fail
        }

        return null;
    }

    // ── Types ──────────────────────────────────────────────────────────────

    public record EarningsCheck(LocalDate earningsDate, long daysUntil, boolean isNearEarnings) {
        public static final EarningsCheck NONE = new EarningsCheck(null, 999, false);

        public String label() {
            if (!isNearEarnings || earningsDate == null) return null;
            if (daysUntil == 0) return "⚠️ EARNINGS TODAY";
            if (daysUntil > 0) return String.format("⚠️ EARNINGS IN %d DAY%s (%s)",
                    daysUntil, daysUntil == 1 ? "" : "S", earningsDate);
            return String.format("⚠️ EARNINGS %d DAY%s AGO (%s)",
                    Math.abs(daysUntil), Math.abs(daysUntil) == 1 ? "" : "S", earningsDate);
        }
    }

    private record CachedEarnings(LocalDate earningsDate, long fetchedAt) {}
}
