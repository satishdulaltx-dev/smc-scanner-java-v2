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
     * Returns {@link NewsSentiment#NONE} (no signal) if the API is unreachable or
     * there are no recent articles.
     *
     * Result is cached for 20 minutes per ticker.
     */
    public NewsSentiment getSentiment(String ticker) {
        // Skip crypto tickers — Polygon news uses equity symbols only
        if (ticker.startsWith("X:")) return NewsSentiment.NONE;

        long now = System.currentTimeMillis();
        long[] ts = cacheTs.get(ticker);
        if (ts != null && (now - ts[0]) < CACHE_TTL_MS) {
            return cacheVal.getOrDefault(ticker, NewsSentiment.NONE);
        }

        try {
            // Polygon news: most-recent articles for this ticker, sorted newest-first
            String published_gte = Instant.ofEpochMilli(now - 48 * 3600_000L)
                    .atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

            String url = "https://api.polygon.io/v2/reference/news"
                    + "?ticker=" + ticker
                    + "&limit=" + MAX_ARTICLES
                    + "&order=desc"
                    + "&sort=published_utc"
                    + "&published_utc.gte=" + published_gte
                    + "&apiKey=" + config.getPolygonApiKey();

            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.debug("News API {} for {}: HTTP {}", url, ticker, resp.code());
                    return cache(ticker, NewsSentiment.NONE);
                }
                JsonNode root = mapper.readTree(resp.body().string());
                NewsSentiment result = parse(ticker, root);
                return cache(ticker, result);
            }
        } catch (Exception e) {
            log.debug("News fetch error for {}: {}", ticker, e.getMessage());
            return NewsSentiment.NONE;
        }
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

    private NewsSentiment cache(String ticker, NewsSentiment s) {
        cacheTs.put(ticker, new long[]{ System.currentTimeMillis() });
        cacheVal.put(ticker, s);
        return s;
    }
}
