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
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Fetches options chain data from Polygon.io /v3/snapshot/options/{underlyingAsset}.
 * Returns raw contract-level data (strike, expiry, volume, OI, greeks, IV).
 * Caches results for 5 minutes per ticker.
 */
@Service
public class OptionsDataService {
    private static final Logger log = LoggerFactory.getLogger(OptionsDataService.class);
    private static final String BASE = "https://api.polygon.io/v3/snapshot/options/";

    private final ScannerConfig config;
    private final ObjectMapper  mapper = new ObjectMapper();
    private final OkHttpClient  http   = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    // Simple TTL cache: ticker → (data, timestamp)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    public OptionsDataService(ScannerConfig config) {
        this.config = config;
    }

    /**
     * Fetches near-the-money options chain for the given ticker.
     *
     * @param ticker         underlying symbol (e.g. "AAPL")
     * @param currentPrice   latest underlying price (to filter ATM ±range)
     * @param strikeRange    how far from ATM in dollars (e.g. 15.0)
     * @param minDte         minimum days to expiration (e.g. 5)
     * @param maxDte         maximum days to expiration (e.g. 21)
     * @return list of contract data maps, or empty list on error
     */
    public List<ContractData> getOptionsChain(String ticker, double currentPrice,
                                               double strikeRange, int minDte, int maxDte) {
        // Check cache
        CacheEntry cached = cache.get(ticker);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.data;
        }

        String apiKey = config.getPolygonApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No Polygon API key for options data");
            return List.of();
        }

        double minStrike = Math.max(0, currentPrice - strikeRange);
        double maxStrike = currentPrice + strikeRange;
        LocalDate minExp = LocalDate.now(ZoneOffset.UTC).plusDays(minDte);
        LocalDate maxExp = LocalDate.now(ZoneOffset.UTC).plusDays(maxDte);

        List<ContractData> allContracts = new ArrayList<>();
        String url = String.format("%s%s?strike_price.gte=%.0f&strike_price.lte=%.0f" +
                        "&expiration_date.gte=%s&expiration_date.lte=%s&limit=250&apiKey=%s",
                BASE, ticker, minStrike, maxStrike, minExp, maxExp, apiKey);

        try {
            allContracts.addAll(fetchPage(url));

            // Follow pagination — Polygon returns next_url for large chains
            // (limit to 3 pages max = 750 contracts to avoid rate limits)
            String nextUrl = null;
            for (int page = 0; page < 2; page++) {
                // Re-parse the last response to get next_url
                // Actually let's track it inline
                break; // We'll handle pagination below
            }
        } catch (Exception e) {
            log.error("Options chain fetch failed for {}: {}", ticker, e.getMessage());
        }

        // Try pagination if the API returned next_url
        try {
            String pageUrl = url;
            for (int page = 0; page < 3; page++) {
                String nextUrl = fetchPageWithNext(pageUrl, allContracts);
                if (nextUrl == null) break;
                pageUrl = nextUrl + "&apiKey=" + apiKey;
            }
        } catch (Exception e) {
            log.debug("Options pagination stopped: {}", e.getMessage());
        }

        // Deduplicate by contract ticker
        Map<String, ContractData> dedupe = new LinkedHashMap<>();
        for (ContractData c : allContracts) {
            dedupe.putIfAbsent(c.contractTicker(), c);
        }
        List<ContractData> result = new ArrayList<>(dedupe.values());

        cache.put(ticker, new CacheEntry(result, System.currentTimeMillis()));
        log.debug("Options chain for {}: {} contracts (strikes {}-{}, exp {}-{})",
                ticker, result.size(), minStrike, maxStrike, minExp, maxExp);
        return result;
    }

    /** Fetches a page and returns the next_url (or null). Adds contracts to the provided list. */
    private String fetchPageWithNext(String url, List<ContractData> out) {
        try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JsonNode root = mapper.readTree(resp.body().string());

            if (!"OK".equals(root.path("status").asText())) {
                log.warn("Options API status: {}", root.path("status").asText());
                return null;
            }

            JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode node : results) {
                    ContractData cd = parseContract(node);
                    if (cd != null) out.add(cd);
                }
            }

            JsonNode nextUrl = root.get("next_url");
            return nextUrl != null && !nextUrl.isNull() ? nextUrl.asText() : null;
        } catch (Exception e) {
            log.error("Options page fetch error: {}", e.getMessage());
            return null;
        }
    }

    private List<ContractData> fetchPage(String url) {
        List<ContractData> result = new ArrayList<>();
        fetchPageWithNext(url, result);
        return result;
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

            long volume = day != null ? day.path("volume").asLong(0) : 0;
            double close = day != null ? day.path("close").asDouble(0) : 0;
            double dayHigh = day != null ? day.path("high").asDouble(0) : 0;
            double dayLow  = day != null ? day.path("low").asDouble(0) : 0;
            double vwap    = day != null ? day.path("vwap").asDouble(0) : 0;

            long oi = node.path("open_interest").asLong(0);
            double iv = node.path("implied_volatility").asDouble(0);

            double delta = greeks != null ? greeks.path("delta").asDouble(0) : 0;
            double gamma = greeks != null ? greeks.path("gamma").asDouble(0) : 0;
            double theta = greeks != null ? greeks.path("theta").asDouble(0) : 0;
            double vega  = greeks != null ? greeks.path("vega").asDouble(0) : 0;

            // Underlying price from the snapshot
            double underlyingPrice = node.path("underlying_asset").path("price").asDouble(0);

            return new ContractData(contractTicker, contractType, strike, expDate,
                    sharesPerContract, volume, close, dayHigh, dayLow, vwap,
                    oi, iv, delta, gamma, theta, vega, underlyingPrice);
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
            double close,           // last traded price
            double dayHigh,
            double dayLow,
            double vwap,
            long   openInterest,
            double iv,              // implied volatility (decimal)
            double delta,
            double gamma,
            double theta,
            double vega,
            double underlyingPrice
    ) {
        /** Days to expiration from today. */
        public int dte() {
            try {
                return (int) java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.now(ZoneOffset.UTC),
                        LocalDate.parse(expirationDate));
            } catch (Exception e) { return 0; }
        }
    }

    private record CacheEntry(List<ContractData> data, long timestamp) {}
}
