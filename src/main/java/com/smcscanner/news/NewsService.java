package com.smcscanner.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Fetches recent news from Polygon's /v2/reference/news endpoint and
 * extracts per-ticker sentiment scores (Polygon Starter plan includes this).
 *
 * Each article's "insights" array contains:
 *   { "ticker": "SNAP", "sentiment": "negative", "sentiment_reasoning": "..." }
 *
 * Results are cached per ticker for 20 minutes to avoid repeated API calls
 * during the scanner's scan-all loop.
 */
@Service
public class NewsService {
    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    /** How long to cache news per ticker (ms). */
    private static final long CACHE_TTL_MS = 20 * 60 * 1000L;

    /** Max articles to fetch — 10 is plenty for 24-hour window checks. */
    private static final int MAX_ARTICLES = 10;

    private final ScannerConfig config;
    private final ObjectMapper  mapper = new ObjectMapper();
    private final OkHttpClient  http   = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /** Simple TTL cache: ticker → (timestamp, result) */
    private final Map<String, long[]>         cacheTs  = new ConcurrentHashMap<>();
    private final Map<String, NewsSentiment>  cacheVal = new ConcurrentHashMap<>();

    public NewsService(ScannerConfig config) {
        this.config = config;
    }

    /**
     * Returns the news sentiment for a ticker published in the last 48 hours.
     * Used by the live scanner. Result is cached for 20 minutes per ticker.
     */
    public NewsSentiment getSentiment(String ticker) {
        if (ticker.startsWith("X:")) return NewsSentiment.NONE;

        long now = System.currentTimeMillis();
        long[] ts = cacheTs.get(ticker);
        if (ts != null && (now - ts[0]) < CACHE_TTL_MS) {
            return cacheVal.getOrDefault(ticker, NewsSentiment.NONE);
        }

        Instant to   = Instant.ofEpochMilli(now);
        Instant from = Instant.ofEpochMilli(now - 48 * 3600_000L);
        NewsSentiment result = fetchFromPolygon(ticker, from, to);
        return cache(ticker, result);
    }

    /**
     * Returns the news sentiment for a ticker as it existed at a specific point in
     * the past — used by the backtest to simulate what the live scanner would have
     * seen at the moment a trade was triggered.
     *
     * @param ticker     stock symbol
     * @param atEpochMs  epoch-ms of the trade entry bar (end of the backtest window)
     */
    public NewsSentiment getSentimentAt(String ticker, long atEpochMs) {
        if (ticker.startsWith("X:")) return NewsSentiment.NONE;

        // Cache key encodes ticker + day bucket (per-day granularity is fine for backtest)
        String cacheKey = ticker + "_" + (atEpochMs / 86_400_000L);
        long[] ts = cacheTs.get(cacheKey);
        long now = System.currentTimeMillis();
        if (ts != null && (now - ts[0]) < CACHE_TTL_MS) {
            return cacheVal.getOrDefault(cacheKey, NewsSentiment.NONE);
        }

        Instant to   = Instant.ofEpochMilli(atEpochMs);
        Instant from = Instant.ofEpochMilli(atEpochMs - 48 * 3600_000L);
        NewsSentiment result = fetchFromPolygon(ticker, from, to);

        // Cache under the day-bucket key
        cacheTs.put(cacheKey, new long[]{ now });
        cacheVal.put(cacheKey, result);
        return result;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private NewsSentiment parse(String ticker, JsonNode root) {
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return NewsSentiment.NONE;
        }

        int positive = 0, negative = 0, neutral = 0;
        List<String> negativeHeadlines = new ArrayList<>();
        List<String> positiveHeadlines = new ArrayList<>();
        String latestHeadline = null;

        for (JsonNode article : results) {
            if (latestHeadline == null) {
                latestHeadline = article.path("title").asText(null);
            }

            // Each article has an "insights" array with per-ticker sentiment
            JsonNode insights = article.get("insights");
            if (insights == null || !insights.isArray()) continue;

            for (JsonNode insight : insights) {
                String t = insight.path("ticker").asText("");
                if (!ticker.equalsIgnoreCase(t)) continue;

                String sentiment = insight.path("sentiment").asText("neutral");
                String title     = article.path("title").asText("(no title)");

                switch (sentiment.toLowerCase()) {
                    case "positive" -> { positive++; positiveHeadlines.add(title); }
                    case "negative" -> { negative++; negativeHeadlines.add(title); }
                    default         -> neutral++;
                }
            }
        }

        int total = positive + negative + neutral;
        if (total == 0) return NewsSentiment.NONE;

        // Net score: +1.0 = all positive, -1.0 = all negative
        double netScore = total > 0 ? (double)(positive - negative) / total : 0.0;

        // Pick the most relevant headline to show in alerts
        String headline = !negativeHeadlines.isEmpty() ? negativeHeadlines.get(0)
                        : !positiveHeadlines.isEmpty()  ? positiveHeadlines.get(0)
                        : latestHeadline;

        return new NewsSentiment(ticker, positive, negative, neutral, netScore, headline);
    }

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    /**
     * Calls Polygon's /v2/reference/news endpoint and parses sentiment.
     * Shared by getSentiment() (live) and getSentimentAt() (backtest).
     *
     * @param ticker  stock symbol
     * @param from    window start (inclusive)
     * @param to      window end   (inclusive)
     */
    private NewsSentiment fetchFromPolygon(String ticker, Instant from, Instant to) {
        String apiKey = config.getPolygonApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No Polygon API key — skipping news for {}", ticker);
            return NewsSentiment.NONE;
        }

        // ISO-8601 date strings required by Polygon (e.g. 2024-03-21T14:30:00Z)
        DateTimeFormatter iso = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
        String gte = iso.format(from);
        String lte = iso.format(to);

        String url = "https://api.polygon.io/v2/reference/news"
                   + "?ticker="              + ticker
                   + "&published_utc.gte="   + gte
                   + "&published_utc.lte="   + lte
                   + "&limit="               + MAX_ARTICLES
                   + "&apiKey="              + apiKey;

        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("Polygon news {} → HTTP {}", ticker, resp.code());
                    return NewsSentiment.NONE;
                }
                String body = resp.body() != null ? resp.body().string() : "";
                JsonNode root = mapper.readTree(body);
                return parse(ticker, root);
            }
        } catch (Exception e) {
            log.warn("Polygon news fetch error for {}: {}", ticker, e.getMessage());
            return NewsSentiment.NONE;
        }
    }

    private NewsSentiment cache(String ticker, NewsSentiment s) {
        cacheTs.put(ticker, new long[]{ System.currentTimeMillis() });
        cacheVal.put(ticker, s);
        return s;
    }
}
