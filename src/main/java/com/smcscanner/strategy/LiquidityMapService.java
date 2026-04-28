package com.smcscanner.strategy;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Computes and caches per-ticker liquidity levels each session.
 *
 * Levels tracked:
 *  - Prior day high / low (PDH / PDL)
 *  - Weekly high / low (last 5 daily bars)
 *  - Opening range high / low (first 3 × 5m bars = first 15 min)
 *  - Session VWAP
 *  - Round numbers (nearest $5 / $1 / $0.5 depending on price)
 *
 * Two gates exposed to ScannerService:
 *  isNearLevel()  — entry must be within 0.30 × dailyATR of a known level
 *  isLevelFresh() — level must not have been tested ≥ 2 times this session
 */
@Service
public class LiquidityMapService {
    private static final Logger log = LoggerFactory.getLogger(LiquidityMapService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final PolygonClient polygon;

    // Cached levels per ticker, refreshed once per day
    private final Map<String, List<Double>> levelCache = new ConcurrentHashMap<>();
    private final Map<String, LocalDate>    levelDate  = new ConcurrentHashMap<>();

    // Session-scoped level-touch counts: ticker → bucketId → touch count
    private final Map<String, Map<Integer, Integer>> touchCounts = new ConcurrentHashMap<>();
    private volatile LocalDate touchDate = LocalDate.MIN;

    public LiquidityMapService(PolygonClient polygon) {
        this.polygon = polygon;
    }

    /**
     * Returns true if the given price is within 0.30 × dailyAtr (min 0.3% of price)
     * of any known liquidity level. Passes through (returns true) when no levels exist.
     */
    public boolean isNearLevel(String ticker, double price, double dailyAtr) {
        ensureLevels(ticker);
        List<Double> levels = levelCache.get(ticker);
        if (levels == null || levels.isEmpty()) return true;
        double threshold = Math.max(dailyAtr * 0.30, price * 0.003);
        for (double lv : levels) {
            if (Math.abs(lv - price) <= threshold) {
                log.debug("LOCATION ✓ {} price={} near level={} (Δ={} threshold={})",
                        ticker,
                        String.format("%.2f", price),
                        String.format("%.2f", lv),
                        String.format("%.2f", Math.abs(lv - price)),
                        String.format("%.2f", threshold));
                return true;
            }
        }
        log.debug("LOCATION ✗ {} price={} not near any level (threshold={}) levels={}",
                ticker, String.format("%.2f", price), String.format("%.2f", threshold),
                levels.stream().map(l -> String.format("%.2f", l)).collect(Collectors.joining(",")));
        return false;
    }

    /**
     * Returns true if this price-level bucket has been tested fewer than 2 times this session.
     * Records the touch on every call that returns true.
     * Protects against "third-push" setups that fail at exhausted levels.
     */
    public boolean isLevelFresh(String ticker, double price, double dailyAtr) {
        LocalDate today = LocalDate.now(ET);
        if (!today.equals(touchDate)) {
            touchCounts.clear();
            touchDate = today;
        }
        // Bucket by ATR/2 increments — levels closer than that count as the same test
        double bucketSize = Math.max(dailyAtr * 0.5, price * 0.005);
        int bucket = (int) Math.round(price / bucketSize);
        Map<Integer, Integer> counts = touchCounts.computeIfAbsent(ticker, k -> new ConcurrentHashMap<>());
        int count = counts.getOrDefault(bucket, 0);
        if (count >= 2) {
            log.info("LEVEL_EXHAUSTED {} price={} touched {}× already — skip (third push filter)",
                    ticker, String.format("%.2f", price), count);
            return false;
        }
        counts.put(bucket, count + 1);
        return true;
    }

    /** Force a cache refresh for a ticker (e.g. after opening range forms). */
    public void invalidate(String ticker) {
        levelCache.remove(ticker);
        levelDate.remove(ticker);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void ensureLevels(String ticker) {
        LocalDate today = LocalDate.now(ET);
        if (today.equals(levelDate.get(ticker))) return;

        List<Double> levels = new ArrayList<>();
        try {
            // ── Daily bars: PDH/PDL + weekly range ───────────────────────────
            List<OHLCV> daily = polygon.getBars(ticker, "day", 10);
            if (daily != null && daily.size() >= 2) {
                OHLCV pd = daily.get(daily.size() - 2);
                levels.add(pd.getHigh());   // PDH
                levels.add(pd.getLow());    // PDL

                int wStart = Math.max(0, daily.size() - 6);
                List<OHLCV> week = daily.subList(wStart, daily.size() - 1);
                double wHigh = week.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
                double wLow  = week.stream().mapToDouble(OHLCV::getLow).min().orElse(0);
                if (wHigh > 0) levels.add(wHigh);
                if (wLow  > 0) levels.add(wLow);

                // Round numbers based on price magnitude
                double lastClose = daily.get(daily.size() - 1).getClose();
                double step = lastClose >= 200 ? 10.0 : lastClose >= 50 ? 5.0 : lastClose >= 20 ? 2.0 : 1.0;
                double base = Math.round(lastClose / step) * step;
                for (int i = -4; i <= 4; i++) {
                    double rn = base + i * step;
                    if (rn > 0) levels.add(rn);
                }
            }

            // ── Intraday: ORH/ORL + VWAP ─────────────────────────────────────
            List<OHLCV> bars5m = polygon.getBars(ticker, "5m", 40);
            if (bars5m != null && !bars5m.isEmpty()) {
                List<OHLCV> todayBars = bars5m.stream()
                        .filter(b -> {
                            ZonedDateTime z = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET);
                            return z.toLocalDate().equals(today)
                                    && !z.toLocalTime().isBefore(LocalTime.of(9, 30));
                        })
                        .sorted(Comparator.comparingLong(OHLCV::getTimestamp))
                        .collect(Collectors.toList());

                if (todayBars.size() >= 3) {
                    List<OHLCV> or = todayBars.subList(0, 3);
                    levels.add(or.stream().mapToDouble(OHLCV::getHigh).max().orElse(0)); // ORH
                    levels.add(or.stream().mapToDouble(OHLCV::getLow).min().orElse(0));  // ORL
                }

                if (!todayBars.isEmpty()) {
                    double sumPV = 0, sumV = 0;
                    for (OHLCV b : todayBars) {
                        double tp = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
                        sumPV += tp * b.getVolume();
                        sumV  += b.getVolume();
                    }
                    if (sumV > 0) levels.add(sumPV / sumV); // VWAP
                }
            }
        } catch (Exception e) {
            log.warn("LiquidityMapService: level build failed for {}: {}", ticker, e.getMessage());
        }

        levelCache.put(ticker, levels);
        levelDate.put(ticker, today);
        log.debug("LIQUIDITY MAP {} — {} levels: {}",
                ticker, levels.size(),
                levels.stream().map(l -> String.format("%.2f", l)).collect(Collectors.joining(", ")));
    }
}
