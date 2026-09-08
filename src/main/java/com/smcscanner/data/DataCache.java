package com.smcscanner.data;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DataCache {
    private static final Logger log = LoggerFactory.getLogger(DataCache.class);
    private static final long CLOSE_GRACE_MS = 3_000L;
    private static final Map<String, Long> BAR_INTERVAL_MS = Map.of(
            "1m", 60_000L,
            "5m", 5 * 60_000L,
            "15m", 15 * 60_000L,
            "30m", 30 * 60_000L,
            "60m", 60 * 60_000L,
            "1h", 60 * 60_000L,
            "4h", 4 * 60 * 60_000L,
            "1d", 6 * 60 * 60_000L);

    private record CachedEntry(List<OHLCV> bars, Instant expiresAt) {
        boolean isExpired() { return !Instant.now().isBefore(expiresAt); }
    }

    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public List<OHLCV> get(String ticker, String timeframe) {
        CachedEntry e = cache.get(ticker + ":" + timeframe);
        return (e == null || e.isExpired()) ? null : e.bars();
    }
    public void put(String ticker, String timeframe, List<OHLCV> bars) {
        Instant now = Instant.now();
        cache.put(ticker + ":" + timeframe, new CachedEntry(bars, expiresAt(timeframe, now)));
    }
    public void invalidate(String ticker) { cache.keySet().removeIf(k -> k.startsWith(ticker + ":")); }
    public void clear() { cache.clear(); }

    static Instant expiresAt(String timeframe, Instant fetchedAt) {
        long interval = BAR_INTERVAL_MS.getOrDefault(timeframe.toLowerCase(), 60_000L);
        if ("1d".equalsIgnoreCase(timeframe)) return fetchedAt.plusMillis(interval);
        long fetchedMs = fetchedAt.toEpochMilli();
        long nextClose = (Math.floorDiv(fetchedMs, interval) + 1) * interval;
        return Instant.ofEpochMilli(nextClose + CLOSE_GRACE_MS);
    }
}
