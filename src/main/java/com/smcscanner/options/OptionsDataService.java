package com.smcscanner.options;

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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;

import java.util.concurrent.TimeUnit;

/**
 * Fetches options chain data from Polygon.io /v3/snapshot/options/{underlyingAsset}.
 * Returns raw contract-level data (strike, expiry, volume, OI, greeks, IV).
 * Caches complete query-specific snapshots for 15 seconds; recommendation quotes expire separately.
 */
@Service
public class OptionsDataService {
    private static final Logger log = LoggerFactory.getLogger(OptionsDataService.class);
    private static final String BASE = "https://api.polygon.io/v3/snapshot/options/";

    private final ScannerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final Clock clock;
    private long providerRetryAtMs;
    private static final long CACHE_TTL_MS = 15_000L;
    private static final long FAILURE_TTL_MS = 60_000L;
    private static final int MAX_PAGES = 10;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 256;
        }
    };

    @org.springframework.beans.factory.annotation.Autowired
    public OptionsDataService(ScannerConfig config) {
        this(config, new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).build(), Clock.systemUTC());
    }

    OptionsDataService(ScannerConfig config, OkHttpClient http, Clock clock) {
        this.config = config; this.http = http; this.clock = clock;
    }

    /** Fetch a complete requested chain. Different strike/expiry windows never share a cache entry. */
    public synchronized List<ContractData> getOptionsChain(String ticker, double currentPrice,
                                                          double strikeRange, int minDte, int maxDte) {
        if (ticker == null || ticker.isBlank() || !Double.isFinite(currentPrice) || currentPrice <= 0
                || !Double.isFinite(strikeRange) || strikeRange < 0 || minDte < 0 || maxDte < minDte)
            return List.of();
        String apiKey = config.getPolygonApiKey();
        if (apiKey == null || apiKey.isBlank()) return List.of();
        double minStrike = Math.max(0, currentPrice - strikeRange);
        double maxStrike = currentPrice + strikeRange;
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        okhttp3.HttpUrl initial = Objects.requireNonNull(okhttp3.HttpUrl.parse(BASE)).newBuilder()
                .addPathSegment(ticker)
                .addQueryParameter("strike_price.gte", Double.toString(minStrike))
                .addQueryParameter("strike_price.lte", Double.toString(maxStrike))
                .addQueryParameter("expiration_date.gte", today.plusDays(minDte).toString())
                .addQueryParameter("expiration_date.lte", today.plusDays(maxDte).toString())
                .addQueryParameter("limit", "250").build();
        String cacheKey = initial.toString();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && clock.millis() < cached.expiresAtMs) return cached.data;
        if (clock.millis() < providerRetryAtMs) return List.of();
        Map<String, ContractData> contracts = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String pageUrl = cacheKey;
        try {
            while (pageUrl != null) {
                okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(pageUrl);
                if (parsed == null || !"https".equals(parsed.scheme())
                        || !"api.polygon.io".equals(parsed.host()) || !seen.add(pageUrl) || seen.size() > MAX_PAGES)
                    throw new IllegalStateException("Invalid or incomplete options pagination");
                // Credentials stay in the header, including on provider pagination URLs.
                okhttp3.HttpUrl safeUrl = parsed.newBuilder().removeAllQueryParameters("apiKey").build();
                Request request = new Request.Builder().url(safeUrl)
                        .header("Authorization", "Bearer " + apiKey).build();
                try (Response response = http.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        if (response.code() == 429) providerRetryAtMs = clock.millis() + FAILURE_TTL_MS;
                        if (response.code() == 401 || response.code() == 403)
                            providerRetryAtMs = clock.millis() + 5 * FAILURE_TTL_MS;
                        throw new IllegalStateException("Options provider HTTP " + response.code());
                    }
                    JsonNode root = mapper.readTree(response.body().string());
                    if (!Set.of("OK", "DELAYED").contains(root.path("status").asText()))
                        throw new IllegalStateException("Options provider did not return a successful snapshot");
                    JsonNode results = root.get("results");
                    if (results != null && !results.isArray())
                        throw new IllegalStateException("Invalid options snapshot results");
                    if (results != null) for (JsonNode node : results) {
                        ContractData contract = parseContract(node);
                        if (contract != null) contracts.putIfAbsent(contract.contractTicker(), contract);
                    }
                    JsonNode next = root.get("next_url");
                    pageUrl = next == null || next.isNull() ? null : next.asText();
                }
            }
            List<ContractData> result = List.copyOf(contracts.values());
            cache.put(cacheKey, new CacheEntry(result, clock.millis() + CACHE_TTL_MS));
            return result;
        } catch (Exception e) {
            // Never return a partial chain as a complete universe, or log URLs containing credentials.
            log.warn("Options chain unavailable for {} ({})", ticker, e.getClass().getSimpleName());
            cache.put(cacheKey, new CacheEntry(List.of(), clock.millis() + FAILURE_TTL_MS));
            return List.of();
        }
    }

    private ContractData parseContract(JsonNode node) {
        try {
            JsonNode details = node.get("details");
            JsonNode day     = node.get("day");
            JsonNode greeks  = node.get("greeks");
            if (details == null) return null;

            String contractTicker = details.path("ticker").asText(null);
            String contractType   = details.path("contract_type").asText("unknown");
            double strike         = details.path("strike_price").asDouble(0);
            String expDate        = details.path("expiration_date").asText(null);
            int    sharesPerContract = details.path("shares_per_contract").asInt(100);
            if (contractTicker == null || contractTicker.isBlank() || expDate == null
                    || !Set.of("call", "put").contains(contractType) || strike <= 0 || sharesPerContract <= 0)
                return null;

            long volume = day != null ? day.path("volume").asLong(0) : 0;
            double close = day != null ? day.path("close").asDouble(0) : 0;
            double dayHigh = day != null ? day.path("high").asDouble(0) : 0;
            double dayLow  = day != null ? day.path("low").asDouble(0) : 0;
            double vwap    = day != null ? day.path("vwap").asDouble(0) : 0;

            // Live bid/ask from last_quote — used for marketable limit orders.
            // Polygon snapshot includes last_quote.ask / last_quote.bid for intraday fills.
            JsonNode lastQuote = node.get("last_quote");
            double ask = lastQuote != null ? lastQuote.path("ask").asDouble(0) : 0;
            double bid = lastQuote != null ? lastQuote.path("bid").asDouble(0) : 0;

            long quoteTimestampMs = lastQuote != null ? lastQuote.path("last_updated").asLong(0) / 1_000_000L : 0;
            String quoteTimeframe = lastQuote != null ? lastQuote.path("timeframe").asText("") : "";
            double askSize = lastQuote != null ? lastQuote.path("ask_size").asDouble(0) : 0;
            double bidSize = lastQuote != null ? lastQuote.path("bid_size").asDouble(0) : 0;

            long oi = node.path("open_interest").asLong(0);
            double iv = node.path("implied_volatility").asDouble(0);

            double delta = greeks != null ? greeks.path("delta").asDouble(0) : 0;
            double gamma = greeks != null ? greeks.path("gamma").asDouble(0) : 0;
            double theta = greeks != null ? greeks.path("theta").asDouble(0) : 0;
            double vega  = greeks != null ? greeks.path("vega").asDouble(0) : 0;

            // Underlying price from the snapshot
            double underlyingPrice = node.path("underlying_asset").path("price").asDouble(0);

            return new ContractData(contractTicker, contractType, strike, expDate,
                    sharesPerContract, volume, close, dayHigh, dayLow, vwap, ask, bid,
                    oi, iv, delta, gamma, theta, vega, underlyingPrice, quoteTimestampMs, quoteTimeframe, askSize, bidSize);
        } catch (Exception e) {
            log.debug("Failed to parse contract: {}", e.getMessage());
            return null;
        }
    }

    // ── Data model ──────────────────────────────────────────────────────────────

    public record ContractData(
            String contractTicker,
            String contractType,    // "call" | "put"
            double strike,
            String expirationDate,
            int    sharesPerContract,
            long   volume,
            double close,           // most recent daily bar close; not an executable quote
            double dayHigh,
            double dayLow,
            double vwap,
            double ask,             // live ask from last_quote (0 if unavailable)
            double bid,             // live bid from last_quote (0 if unavailable)
            long   openInterest,
            double iv,              // implied volatility (decimal)
            double delta,
            double gamma,
            double theta,
            double vega,
            double underlyingPrice,
            long quoteTimestampMs,
            String quoteTimeframe,
            double askSize,
            double bidSize
    ) {
        public boolean hasUsableQuote(long nowMs) {
            return Double.isFinite(ask) && Double.isFinite(bid) && bid > 0 && ask >= bid
                    && Double.isFinite(askSize) && askSize > 0 && Double.isFinite(bidSize) && bidSize > 0
                    && "REAL-TIME".equals(quoteTimeframe) && quoteTimestampMs > 0
                    && quoteTimestampMs <= nowMs && nowMs - quoteTimestampMs <= 30_000L;
        }

        public double spreadFraction() {
            return (ask - bid) / ((ask + bid) / 2.0);
        }

        /** Days to expiration from today. */
        public int dte() {
            try {
                return (int) java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.now(ZoneOffset.UTC),
                        LocalDate.parse(expirationDate));
            } catch (Exception e) { return 0; }
        }
    }

    private record CacheEntry(List<ContractData> data, long expiresAtMs) {}
}
