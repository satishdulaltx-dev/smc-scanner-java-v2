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
    private static final long TTL_SECONDS = 60L;

    private record CachedEntry(List<OHLCV> bars, Instant fetchedAt) {
        boolean isExpired() { return Instant.now().isAfter(fetchedAt.plusSeconds(TTL_SECONDS)); }
    }

    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public List<OHLCV> get(String ticker, String timeframe) {
        CachedEntry e = cache.get(ticker + ":" + timeframe);
        return (e == null || e.isExpired()) ? null : e.bars();
    }
    public void put(String ticker, String timeframe, List<OHLCV> bars) {
        cache.put(ticker + ":" + timeframe, new CachedEntry(bars, Instant.now()));
    }
    public void invalidate(String ticker) { cache.keySet().removeIf(k -> k.startsWith(ticker + ":")); }
    public void clear() { cache.clear(); }
}
