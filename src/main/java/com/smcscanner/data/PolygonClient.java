package com.smcscanner.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.OHLCV;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class PolygonClient {
    private static final Logger log = LoggerFactory.getLogger(PolygonClient.class);

    private final ScannerConfig config;
    private final DataCache     cache;
    private final ObjectMapper  mapper = new ObjectMapper();
    private final OkHttpClient  http   = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    public PolygonClient(ScannerConfig config, DataCache cache) {
        this.config = config; this.cache = cache;
    }

    private static final Map<String, String[]> TF_MAP = Map.of(
            "1m",  new String[]{"1",  "minute"}, "5m",  new String[]{"5",  "minute"},
            "15m", new String[]{"15", "minute"}, "30m", new String[]{"30", "minute"},
            "60m", new String[]{"60", "minute"}, "1h",  new String[]{"1",  "hour"},
            "4h",  new String[]{"4",  "hour"},   "1d",  new String[]{"1",  "day"});

    private static final Map<String, String> KRAKEN_PAIR = Map.of(
            "X:BTCUSD", "XBTUSD", "X:ETHUSD", "ETHUSD",
            "X:SOLUSD", "SOLUSD", "X:XRPUSD", "XRPUSD");

    private static final Map<String, Integer> KRAKEN_INTERVAL = Map.of(
            "1m", 1, "5m", 5, "15m", 15, "30m", 30, "60m", 60, "1h", 60, "4h", 240, "1d", 1440);

    public List<OHLCV> getBars(String ticker, String timeframe, int limit) {
        List<OHLCV> cached = cache.get(ticker, timeframe);
        if (cached != null) return cached;
        List<OHLCV> bars = ticker.startsWith("X:") ?
                getKrakenBars(ticker, timeframe, limit) : getPolygonBars(ticker, timeframe, limit);
        if (bars != null && !bars.isEmpty()) cache.put(ticker, timeframe, bars);
        return bars != null ? bars : new ArrayList<>();
    }

    private List<OHLCV> getKrakenBars(String ticker, String timeframe, int limit) {
        String pair     = KRAKEN_PAIR.get(ticker);
        int    interval = KRAKEN_INTERVAL.getOrDefault(timeframe.toLowerCase(), 5);
        if (pair == null) { log.warn("No Kraken mapping for {}", ticker); return new ArrayList<>(); }
        String url = "https://api.kraken.com/0/public/OHLC?pair=" + pair + "&interval=" + interval;
        try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return new ArrayList<>();
            JsonNode root   = mapper.readTree(resp.body().string());
            JsonNode result = root.get("result");
            if (result == null) return new ArrayList<>();
            JsonNode data = result.get(pair);
            if (data == null) for (JsonNode v : result) { if (v.isArray()) { data = v; break; } }
            if (data == null) return new ArrayList<>();
            List<OHLCV> bars = new ArrayList<>();
            for (JsonNode row : data) {
                if (row.size() < 7) continue;
                bars.add(OHLCV.builder().timestamp(row.get(0).asText())
                        .open(row.get(1).asDouble()).high(row.get(2).asDouble())
                        .low(row.get(3).asDouble()).close(row.get(4).asDouble())
                        .volume(row.get(6).asDouble()).build());
            }
            if (bars.size() > limit) bars = bars.subList(bars.size() - limit, bars.size());
            return bars;
        } catch (Exception e) { log.error("Kraken error {}: {}", ticker, e.getMessage()); return new ArrayList<>(); }
    }

    /** Fetch bars with a custom lookback (bypasses cache — used by BacktestService). */
    public List<OHLCV> getBarsWithLookback(String ticker, String timeframe, int limit, int lookbackDays) {
        if (ticker.startsWith("X:")) return getKrakenBars(ticker, timeframe, limit);
        String[] tf = TF_MAP.getOrDefault(timeframe.toLowerCase(), new String[]{"5", "minute"});
        return fetchPolygon(ticker, tf, limit, lookbackDays);
    }

    private List<OHLCV> getPolygonBars(String ticker, String timeframe, int limit) {
        String[] tf   = TF_MAP.getOrDefault(timeframe.toLowerCase(), new String[]{"5", "minute"});
        // Lookback window: daily uses 2x limit to ensure enough trading days; hourly=7d; minute=1d
        int lookbackDays = tf[1].equals("day") ? Math.max(90, limit * 2) : (tf[1].equals("hour") ? 7 : 1);
        return fetchPolygon(ticker, tf, limit, lookbackDays);
    }

    private List<OHLCV> fetchPolygon(String ticker, String[] tf, int limit, int lookbackDays) {
        String apiKey = config.getPolygonApiKey();
        if (apiKey == null || apiKey.isBlank()) { log.warn("POLYGON_API_KEY not set for {}", ticker); return new ArrayList<>(); }
        String to   = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(lookbackDays).format(DateTimeFormatter.ISO_LOCAL_DATE);
        // Use sort=desc so Polygon returns the MOST RECENT `limit` bars first,
        // then reverse to restore chronological order. Using sort=asc with a wide
        // lookback window returns the OLDEST bars (e.g. 90-day limit over 180-day
        // window returns bars from 90–180 days ago, not the last 90 days).
        String url  = String.format("https://api.polygon.io/v2/aggs/ticker/%s/range/%s/%s/%s/%s?adjusted=true&sort=desc&limit=%d&apiKey=%s",
                ticker, tf[0], tf[1], from, to, limit, apiKey);
        try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return new ArrayList<>();
            JsonNode root    = mapper.readTree(resp.body().string());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return new ArrayList<>();
            List<OHLCV> bars = new ArrayList<>();
            for (JsonNode bar : results) {
                bars.add(OHLCV.builder().timestamp(String.valueOf(bar.get("t").asLong()))
                        .open(bar.get("o").asDouble()).high(bar.get("h").asDouble())
                        .low(bar.get("l").asDouble()).close(bar.get("c").asDouble())
                        .volume(bar.get("v").asDouble()).build());
            }
            java.util.Collections.reverse(bars); // restore chronological order
            return bars;
        } catch (Exception e) { log.error("Polygon error {}: {}", ticker, e.getMessage()); return new ArrayList<>(); }
    }
}
