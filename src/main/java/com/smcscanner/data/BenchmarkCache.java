package com.smcscanner.data;

import com.smcscanner.model.OHLCV;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Dedicated cache for benchmark tickers (SPY, QQQ).
 * Uses a 4-hour TTL so bars are fetched once per EOD run and reused
 * across all per-ticker correlation calculations.
 */
@Component
public class BenchmarkCache {
    private static final long TTL_MS = 4 * 3_600_000L;

    private record Entry(List<OHLCV> bars, Instant fetchedAt) {
        boolean isExpired() {
            return Instant.now().toEpochMilli() > fetchedAt.toEpochMilli() + TTL_MS;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public List<OHLCV> getOrFetch(String ticker, Supplier<List<OHLCV>> fetcher) {
        Entry e = cache.get(ticker);
        if (e != null && !e.isExpired()) return e.bars();
        List<OHLCV> bars = fetcher.get();
        if (bars != null && !bars.isEmpty()) cache.put(ticker, new Entry(bars, Instant.now()));
        return bars;
    }

    public void invalidate() { cache.clear(); }
}
