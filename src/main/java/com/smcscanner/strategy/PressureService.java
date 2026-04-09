package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pressure, Exhaustion, and Gap-Fill filter layer.
 *
 * Three orthogonal "Filters of Truth" that block low-conviction entries:
 *
 *  1. Bar-Pressure Trap Detector
 *     barPressure = ((close - open) / (high - low)) * volume
 *     Proxy for institutional footprint when real Level-2 data is unavailable.
 *     A BOS/breakout on DECLINING pressure = smart money selling into retail buys.
 *     → confidence penalty up to -20 (trapAdj).
 *
 *  2. ATR Exhaustion Gate
 *     If the session range has already consumed ≥ 90% of the 20-day average
 *     daily range, the ticker is "tired." Cap TP at 1:1 R:R and apply -10 conf.
 *
 *  3. Gap-Fill Wait Rule
 *     If price gapped up > 0.8%, smart money is selling into retail longs.
 *     Block ALL LONG signals until the gap is 50% filled OR 30+ min have passed.
 *
 * Regime hierarchy (per Gemini's recommendation):
 *   SQUEEZE regime → skip trap check entirely. Volume naturally contracts
 *   before expansion; declining pressure in a squeeze is expected, not a trap.
 *
 * Thread-safe: all methods are stateless (no shared mutable state).
 */
@Service
public class PressureService {
    private static final Logger log = LoggerFactory.getLogger(PressureService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MKT_OPEN  = LocalTime.of(9, 30);
    private static final LocalTime MKT_CLOSE = LocalTime.of(16, 0);

    /** Gap threshold above which LONGs are blocked until fill conditions met. */
    private static final double GAP_BLOCK_PCT   = 0.008;  // 0.8%
    /** Gap must be this fraction filled before releasing the LONG block. */
    private static final double GAP_FILL_RATIO  = 0.50;
    /** Session bars representing 30 minutes (5m bars × 6). */
    private static final int    GAP_WAIT_BARS   = 6;
    /** Ratio of session range / 20d avg daily range above which we call exhaustion. */
    private static final double EXHAUSTION_THRESHOLD = 0.90;

    // ─────────────────────────────────────────────────────────────────────────
    // 1.  BAR PRESSURE — Volume Delta Proxy
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute signed bar pressure for a single candle.
     * barPressure = ((close - open) / (high - low)) * volume
     * Positive = buying bar, Negative = selling bar, 0 = doji.
     */
    public double computeBarPressure(OHLCV bar) {
        double range = bar.getHigh() - bar.getLow();
        if (range < 1e-8) return 0.0; // doji — zero directional info
        return ((bar.getClose() - bar.getOpen()) / range) * bar.getVolume();
    }

    /**
     * Trap adjustment: detect divergence between BOS direction and bar pressure.
     *
     * Logic:
     *  - Compute pressure for last 5 bars (rolling average) and current bar.
     *  - If avg pressure is bullish but current bar has < 50% of that pressure
     *    while setup is LONG → smart money is exiting into the breakout. Trap.
     *  - Mirror logic for SHORT setups.
     *
     * Bypass conditions (returns 0):
     *  - VWAP / vwap3d strategies: mean-reversion intentionally enters into pressure.
     *  - SQUEEZE regime: volume naturally contracts before expansion; declining
     *    pressure here is the setup, not a warning.
     *
     * @return -20 strong trap, -10 mild trap, 0 neutral, +5 pressure confirmed
     */
    public int computeTrapAdj(List<OHLCV> bars, String direction,
                               String stratType, MarketRegimeDetector.Regime regime) {
        // Bypass: VWAP mean-reversion and squeeze
        if ("vwap".equals(stratType) || "vwap3d".equals(stratType)) return 0;
        if (regime == MarketRegimeDetector.Regime.SQUEEZE) return 0;

        int n = bars.size();
        if (n < 6) return 0;

        double curPressure = computeBarPressure(bars.get(n - 1));

        // 5-bar rolling average (bars n-6 to n-2, i.e., excluding current bar)
        double avgPressure = 0;
        for (int i = n - 6; i < n - 1; i++) {
            avgPressure += computeBarPressure(bars.get(i));
        }
        avgPressure /= 5.0;

        boolean isLong = "long".equals(direction);

        if (isLong) {
            // avgPressure must be positive (normally bullish session) for trap to apply
            if (avgPressure <= 0) return 0;
            if (curPressure < avgPressure * 0.50) return -20; // strong trap — smart money bailing
            if (curPressure < avgPressure * 0.75) return -10; // mild trap
            if (curPressure >= avgPressure * 1.50) return +5; // surge confirmed
        } else {
            // avgPressure must be negative (normally bearish session) for trap to apply
            if (avgPressure >= 0) return 0;
            // For shorts: avgPressure is negative; if current bar is LESS negative → weak pressure
            if (curPressure > avgPressure * 0.50) return -20; // selling drying up = bull trap cover
            if (curPressure > avgPressure * 0.75) return -10; // mild
            if (curPressure <= avgPressure * 1.50) return +5; // selling surge confirmed
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.  ATR EXHAUSTION GATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detect if the ticker has used ≥ 90% of its expected daily move.
     * Expected move = 20-day average of (daily high - daily low).
     *
     * When exhausted: caller should cap TP to 1:1 R:R and apply -10 conf.
     */
    public ExhaustionResult checkExhaustion(List<OHLCV> sessionBars, List<OHLCV> dailyBars) {
        if (sessionBars == null || sessionBars.isEmpty()
                || dailyBars == null || dailyBars.size() < 5) {
            return new ExhaustionResult(false, 0.0);
        }

        double sessionHigh  = sessionBars.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
        double sessionLow   = sessionBars.stream().mapToDouble(OHLCV::getLow).min().orElse(0);
        double sessionRange = sessionHigh - sessionLow;

        int lookback = Math.min(20, dailyBars.size());
        double sumRange = 0;
        for (int i = dailyBars.size() - lookback; i < dailyBars.size(); i++) {
            sumRange += dailyBars.get(i).getHigh() - dailyBars.get(i).getLow();
        }
        double avgDailyRange = sumRange / lookback;

        if (avgDailyRange <= 0) return new ExhaustionResult(false, 0.0);
        double rangeRatio = sessionRange / avgDailyRange;
        return new ExhaustionResult(rangeRatio >= EXHAUSTION_THRESHOLD, rangeRatio);
    }

    public record ExhaustionResult(boolean exhausted, double rangeRatio) {}

    // ─────────────────────────────────────────────────────────────────────────
    // 3.  GAP LONG BLOCK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if LONG signals should be blocked due to an unresolved gap-up.
     *
     * Block is active when ALL of:
     *  - Gap at open > 0.8% (price gapped away from previous close)
     *  - Gap has NOT been filled by ≥ 50%
     *  - Less than 30 minutes have elapsed since session open (< 6 five-minute bars)
     *
     * @param sessionBars today's regular-session bars (9:30–4:00 ET), most recent last
     * @param prevClose   previous session's closing price
     */
    public boolean isLongBlockedByGap(List<OHLCV> sessionBars, double prevClose) {
        if (sessionBars == null || sessionBars.isEmpty() || prevClose <= 0) return false;

        double todayOpen  = sessionBars.get(0).getOpen();
        double gapPct     = (todayOpen - prevClose) / prevClose;
        if (gapPct <= GAP_BLOCK_PCT) return false; // gap not significant enough

        double currentPrice = sessionBars.get(sessionBars.size() - 1).getClose();
        double gapSize      = todayOpen - prevClose;

        boolean halfFilled       = currentPrice <= todayOpen - gapSize * GAP_FILL_RATIO;
        boolean thirtyMinPassed  = sessionBars.size() > GAP_WAIT_BARS;

        return !halfFilled && !thirtyMinPassed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES — Session-bar extraction and previous-close lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Filter a bar list to today's regular NYSE session (9:30 AM – 4:00 PM ET).
     * "Today" is the date of the most recent bar in the list.
     */
    public List<OHLCV> getSessionBars(List<OHLCV> bars) {
        if (bars == null || bars.isEmpty()) return List.of();
        LocalDate today = Instant.ofEpochMilli(bars.get(bars.size() - 1).getTimestamp())
                .atZone(ET).toLocalDate();
        List<OHLCV> session = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(MKT_OPEN)
                    && zdt.toLocalTime().isBefore(MKT_CLOSE)) {
                session.add(bar);
            }
        }
        return session;
    }

    /**
     * Return the close of the last bar from any day before the most-recent bar's date.
     * Used to compute today's opening gap from 5m bar history (no separate daily call).
     */
    public double getPrevSessionClose(List<OHLCV> bars) {
        if (bars == null || bars.isEmpty()) return 0.0;
        LocalDate today = Instant.ofEpochMilli(bars.get(bars.size() - 1).getTimestamp())
                .atZone(ET).toLocalDate();
        // Scan backwards — first bar that belongs to a prior date
        for (int i = bars.size() - 1; i >= 0; i--) {
            LocalDate d = Instant.ofEpochMilli(bars.get(i).getTimestamp()).atZone(ET).toLocalDate();
            if (d.isBefore(today)) return bars.get(i).getClose();
        }
        return 0.0;
    }
}
