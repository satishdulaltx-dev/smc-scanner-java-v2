package com.smcscanner.market;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides market context (SPY relative strength + VIX regime) for both
 * the live scanner and the backtest engine.
 *
 * Live scanner usage: getContext(ticker) — fetches SPY/VIX bars with 30-min cache.
 *
 * Backtest usage: caller fetches SPY and VIX bar lists ONCE at the start via
 * fetchSpyBarsForBacktest() / fetchVixBarsForBacktest(), then calls
 * getContextAt(ticker, tickerDailyBars, spyBars, vixBars, cutoffEpochMs) inside
 * the per-trade loop — zero additional API calls.
 */
@Service
public class MarketContextService {
    private static final Logger log = LoggerFactory.getLogger(MarketContextService.class);

    /** How long to cache SPY and VIX bars for the live scanner. */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    /** Number of trading days used for the RS return calculation. */
    private static final int RS_DAYS = 5;

    private final PolygonClient client;

    // ── Live-scanner caches (thread-safe via synchronized getters) ────────────
    private volatile long        spyCacheTs    = 0;
    private volatile List<OHLCV> spyBarsCache  = null;
    private volatile long        vixCacheTs    = 0;
    private volatile double      vixLevelCache = 0.0;

    public MarketContextService(PolygonClient client) {
        this.client = client;
    }

    // ── Live scanner ──────────────────────────────────────────────────────────

    /**
     * Returns a MarketContext for the live scanner.
     * SPY and VIX are cached for 30 minutes to avoid redundant API calls
     * across the scan-all ticker loop.
     *
     * Returns NONE for crypto, SPY itself, and QQQ (they are their own benchmark).
     */
    public MarketContext getContext(String ticker) {
        if (isBenchmark(ticker)) return MarketContext.NONE;

        List<OHLCV> spy = getLiveSpyBars();
        double vix = getLiveVixLevel();

        try {
            List<OHLCV> tickerBars = client.getBars(ticker, "1d", RS_DAYS + 3);
            double rs = computeRs(tickerBars, spy);
            return new MarketContext(ticker, rs, vix, vixRegime(vix));
        } catch (Exception e) {
            log.debug("MarketContext error for {}: {}", ticker, e.getMessage());
            return new MarketContext(ticker, 0.0, vix, vixRegime(vix));
        }
    }

    // ── Backtest (pure computation — no API calls inside the trade loop) ──────

    /**
     * Computes market context at a historical cutoff timestamp using pre-fetched
     * bar lists. No API calls — all slicing is done in-memory.
     *
     * @param ticker         stock symbol
     * @param tickerDailyBars  all daily bars for the ticker (pre-fetched at backtest start)
     * @param spyBars          all daily SPY bars    (pre-fetched at backtest start)
     * @param vixBars          all daily VIX bars    (pre-fetched at backtest start, may be empty)
     * @param cutoffEpochMs    epoch-ms of the entry bar (exclusive: bars BEFORE this date)
     */
    public MarketContext getContextAt(String ticker,
                                      List<OHLCV> tickerDailyBars,
                                      List<OHLCV> spyBars,
                                      List<OHLCV> vixBars,
                                      long cutoffEpochMs) {
        if (isBenchmark(ticker)) return MarketContext.NONE;

        List<OHLCV> tSlice   = sliceTo(tickerDailyBars, cutoffEpochMs);
        List<OHLCV> spySlice = sliceTo(spyBars,         cutoffEpochMs);
        List<OHLCV> vixSlice = sliceTo(vixBars,         cutoffEpochMs);

        double rs  = computeRs(tSlice, spySlice);
        double vix = vixSlice.isEmpty() ? 0.0 : vixSlice.get(vixSlice.size() - 1).getClose();
        return new MarketContext(ticker, rs, vix, vixRegime(vix));
    }

    // ── Pre-fetch helpers for BacktestService ─────────────────────────────────

    /** Fetches SPY daily bars for the backtest. Returns empty list on error. */
    public List<OHLCV> fetchSpyBarsForBacktest(int limit) {
        try {
            List<OHLCV> bars = client.getBars("SPY", "1d", limit);
            return bars != null ? bars : List.of();
        } catch (Exception e) {
            log.warn("SPY backtest fetch error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches VIX (I:VIX) daily bars for the backtest.
     * Returns empty list gracefully if the index is not available on this plan.
     */
    public List<OHLCV> fetchVixBarsForBacktest(int limit) {
        try {
            List<OHLCV> bars = client.getBars("I:VIX", "1d", limit);
            return bars != null ? bars : List.of();
        } catch (Exception e) {
            log.debug("VIX backtest fetch unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Intraday Relative Strength (5m bars, rolling 30-min window) ───────────

    /** Number of 5-min bars in the rolling RS window (6 bars = 30 minutes). */
    private static final int INTRADAY_RS_BARS = 6;

    // Cache for intraday SPY 5m bars (shared across all tickers in one scan cycle)
    private volatile long        spy5mCacheTs   = 0;
    private volatile List<OHLCV> spy5mBarsCache = null;

    /**
     * Compute intraday relative strength: ticker's 30-min return minus SPY's 30-min return.
     * Positive = ticker outperforming SPY (bullish divergence).
     * Negative = ticker underperforming SPY (bearish divergence).
     *
     * @param tickerBars5m  5-minute bars for the ticker (at least INTRADAY_RS_BARS needed)
     * @return intraday RS score, or 0.0 if insufficient data
     */
    public double computeIntradayRs(List<OHLCV> tickerBars5m) {
        List<OHLCV> spyBars = getLiveSpy5mBars();
        return computeIntradayRsFromBars(tickerBars5m, spyBars);
    }

    /**
     * Pure computation of intraday RS from pre-fetched bars (used by backtest).
     */
    public double computeIntradayRsFromBars(List<OHLCV> tickerBars5m, List<OHLCV> spyBars5m) {
        if (tickerBars5m == null || tickerBars5m.size() < INTRADAY_RS_BARS + 1) return 0.0;
        if (spyBars5m    == null || spyBars5m.size()    < INTRADAY_RS_BARS + 1) return 0.0;

        int tEnd = tickerBars5m.size() - 1;
        int tStart = tEnd - INTRADAY_RS_BARS;
        double tNow  = tickerBars5m.get(tEnd).getClose();
        double tPrev = tickerBars5m.get(tStart).getClose();

        int sEnd = spyBars5m.size() - 1;
        int sStart = sEnd - INTRADAY_RS_BARS;
        double sNow  = spyBars5m.get(sEnd).getClose();
        double sPrev = spyBars5m.get(sStart).getClose();

        if (tPrev <= 0 || sPrev <= 0) return 0.0;
        return (tNow - tPrev) / tPrev - (sNow - sPrev) / sPrev;
    }

    /**
     * Checks if intraday RS supports the trade direction.
     * For LONG: RS must be positive (ticker outperforming SPY = accumulation)
     * For SHORT: RS must be negative (ticker underperforming SPY = distribution)
     *
     * @return true if direction is aligned with intraday RS, false if conflicting
     */
    public boolean isIntradayRsAligned(double intradayRs, String direction) {
        if ("long".equals(direction))  return intradayRs > 0.0;
        if ("short".equals(direction)) return intradayRs < 0.0;
        return true; // unknown direction — let it through
    }

    /**
     * Compute confidence adjustment for intraday RS (soft gate instead of hard block).
     * Also checks absolute trend: ticker must be trending in the trade direction,
     * not just "bleeding slower" than SPY (the Beta-Blindness fix).
     *
     * @param intradayRs   relative return vs SPY
     * @param tickerBars5m ticker's 5m bars (for absolute trend check)
     * @param direction    "long" or "short"
     * @return confidence delta: +10 aligned, -20 conflicting, -30 falling knife
     */
    public int computeIntradayRsDelta(double intradayRs, List<OHLCV> tickerBars5m, String direction) {
        if (tickerBars5m == null || tickerBars5m.size() < INTRADAY_RS_BARS + 1) return 0;

        // Absolute trend: is the ticker itself moving in the trade direction?
        // Use 12-bar SMA (~1 hour) as the anchor
        int smaLen = Math.min(12, tickerBars5m.size());
        double sma = 0;
        for (int i = tickerBars5m.size() - smaLen; i < tickerBars5m.size(); i++) {
            sma += tickerBars5m.get(i).getClose();
        }
        sma /= smaLen;
        double lastClose = tickerBars5m.get(tickerBars5m.size() - 1).getClose();

        boolean tickerTrendingUp   = lastClose > sma;
        boolean tickerTrendingDown = lastClose < sma;
        boolean rsAligned = isIntradayRsAligned(intradayRs, direction);

        // Best case: RS aligned + ticker trending in direction
        if (rsAligned && "long".equals(direction)  && tickerTrendingUp)   return +10;
        if (rsAligned && "short".equals(direction) && tickerTrendingDown) return +10;

        // RS aligned but ticker not trending (relative strength without conviction)
        if (rsAligned) return +5;

        // Worst case: RS conflict + ticker trending AGAINST direction (falling knife)
        if (!rsAligned && "long".equals(direction)  && tickerTrendingDown) return -30;
        if (!rsAligned && "short".equals(direction) && tickerTrendingUp)   return -30;

        // RS conflict but ticker is flat/ambiguous
        return -20;
    }

    private synchronized List<OHLCV> getLiveSpy5mBars() {
        long now = System.currentTimeMillis();
        if (spy5mBarsCache == null || (now - spy5mCacheTs) > CACHE_TTL_MS) {
            try {
                spy5mBarsCache = client.getBars("SPY", "5m", 30);
                spy5mCacheTs   = now;
                log.debug("SPY 5m bars refreshed ({} bars)", spy5mBarsCache != null ? spy5mBarsCache.size() : 0);
            } catch (Exception e) {
                log.warn("SPY 5m bars fetch error: {}", e.getMessage());
                if (spy5mBarsCache == null) spy5mBarsCache = List.of();
            }
        }
        return spy5mBarsCache;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private double computeRs(List<OHLCV> tickerBars, List<OHLCV> spyBars) {
        if (tickerBars == null || tickerBars.size() < RS_DAYS + 1) return 0.0;
        if (spyBars    == null || spyBars.size()    < RS_DAYS + 1) return 0.0;

        double tNow  = tickerBars.get(tickerBars.size() - 1).getClose();
        double tPrev = tickerBars.get(tickerBars.size() - 1 - RS_DAYS).getClose();
        double sNow  = spyBars.get(spyBars.size() - 1).getClose();
        double sPrev = spyBars.get(spyBars.size() - 1 - RS_DAYS).getClose();

        if (tPrev <= 0 || sPrev <= 0) return 0.0;
        return (tNow - tPrev) / tPrev - (sNow - sPrev) / sPrev;
    }

    private synchronized List<OHLCV> getLiveSpyBars() {
        long now = System.currentTimeMillis();
        if (spyBarsCache == null || (now - spyCacheTs) > CACHE_TTL_MS) {
            try {
                spyBarsCache = client.getBars("SPY", "1d", RS_DAYS + 3);
                spyCacheTs   = now;
                log.debug("SPY bars refreshed ({} bars)", spyBarsCache != null ? spyBarsCache.size() : 0);
            } catch (Exception e) {
                log.warn("SPY bars fetch error: {}", e.getMessage());
                if (spyBarsCache == null) spyBarsCache = List.of();
            }
        }
        return spyBarsCache;
    }

    private synchronized double getLiveVixLevel() {
        long now = System.currentTimeMillis();
        if (vixLevelCache <= 0 || (now - vixCacheTs) > CACHE_TTL_MS) {
            try {
                List<OHLCV> bars = client.getBars("I:VIX", "1d", 3);
                if (bars != null && !bars.isEmpty()) {
                    vixLevelCache = bars.get(bars.size() - 1).getClose();
                    log.debug("VIX refreshed: {}", vixLevelCache);
                } else {
                    vixLevelCache = 20.0; // neutral fallback
                }
                vixCacheTs = now;
            } catch (Exception e) {
                log.debug("VIX fetch unavailable: {} — using neutral fallback", e.getMessage());
                vixLevelCache = 20.0;
                vixCacheTs    = now;
            }
        }
        return vixLevelCache;
    }

    private String vixRegime(double vix) {
        if (vix <= 0)  return "normal";
        if (vix > 25)  return "volatile";
        if (vix < 15)  return "calm";
        return "normal";
    }

    private List<OHLCV> sliceTo(List<OHLCV> bars, long cutoffEpochMs) {
        if (bars == null || bars.isEmpty()) return List.of();
        return bars.stream()
                .filter(b -> b.getTimestamp() < cutoffEpochMs)
                .collect(Collectors.toList());
    }

    private boolean isBenchmark(String ticker) {
        return ticker.startsWith("X:") || "SPY".equals(ticker) || "QQQ".equals(ticker);
    }
}
