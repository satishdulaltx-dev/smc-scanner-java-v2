package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Detects neutral/range-bound conditions where options spreads
 * (Iron Condor, Straddle) excel.  Complements the existing directional
 * detectors by looking for tight price bands, flat EMAs, declining
 * volume, and choppy bar-by-bar oscillation.
 */
@Service
public class RangeDetector {

    /**
     * Detect neutral/range-bound conditions for options spread strategies.
     * Returns a setup with direction="range" and volatility="range" when
     * price is oscillating within a tight band with declining volume.
     *
     * @param bars      5-minute OHLCV bars (50+)
     * @param dailyBars daily OHLCV bars (20+) for EMA flatness check
     * @param ticker    symbol
     * @param dailyAtr  daily ATR
     * @return 0 or 1 setups with direction="range", volatility="range"
     */
    public List<TradeSetup> detect(List<OHLCV> bars, List<OHLCV> dailyBars,
                                    String ticker, double dailyAtr) {

        if (bars == null || bars.size() < 50 || dailyBars == null || dailyBars.size() < 20) {
            return Collections.emptyList();
        }

        // ── 1. Intraday range check (last 20 5m bars) ─────────────────────────
        List<OHLCV> recent20 = bars.subList(bars.size() - 20, bars.size());
        double rangeHigh = Double.MIN_VALUE;
        double rangeLow  = Double.MAX_VALUE;
        for (OHLCV b : recent20) {
            if (b.getHigh() > rangeHigh) rangeHigh = b.getHigh();
            if (b.getLow()  < rangeLow)  rangeLow  = b.getLow();
        }
        double midPrice   = (rangeHigh + rangeLow) / 2.0;
        double rangePct   = (rangeHigh - rangeLow) / midPrice * 100.0;

        if (rangePct > 3.0 || rangePct < 1.5) {
            return Collections.emptyList();  // outside the 1.5-3% band
        }

        // ── 2. EMA flatness on daily bars ──────────────────────────────────────
        double ema20 = computeEma(dailyBars, 20);
        double ema50 = computeEma(dailyBars, 50);

        // Check slope of each EMA over the last 5 daily bars
        if (!isEmaFlat(dailyBars, 20) || !isEmaFlat(dailyBars, 50)) {
            return Collections.emptyList();
        }

        // ── 3. Volume declining (last 10 vs previous 10) ──────────────────────
        List<OHLCV> last10 = bars.subList(bars.size() - 10, bars.size());
        List<OHLCV> prev10 = bars.subList(bars.size() - 20, bars.size() - 10);
        double avgVolLast = last10.stream().mapToDouble(OHLCV::getVolume).average().orElse(0);
        double avgVolPrev = prev10.stream().mapToDouble(OHLCV::getVolume).average().orElse(0);

        if (avgVolPrev <= 0 || avgVolLast >= avgVolPrev) {
            return Collections.emptyList();  // volume not declining
        }
        double volumeDeclinePct = (avgVolPrev - avgVolLast) / avgVolPrev * 100.0;

        // ── 4. Bar oscillation check (last 8 bars) ────────────────────────────
        List<OHLCV> last8 = bars.subList(bars.size() - 8, bars.size());
        int directionChanges = 0;
        for (int i = 1; i < last8.size(); i++) {
            boolean prevUp = last8.get(i - 1).getClose() > last8.get(i - 1).getOpen();
            boolean currUp = last8.get(i).getClose() > last8.get(i).getOpen();
            if (prevUp != currUp) directionChanges++;
        }
        if (directionChanges < 4) {
            return Collections.emptyList();  // not choppy enough
        }

        // ── 5. Build TradeSetup ────────────────────────────────────────────────
        int confidence = 60;
        if (isEmaFlat(dailyBars, 20) && isEmaFlat(dailyBars, 50)) confidence += 5;
        if (volumeDeclinePct > 20.0)  confidence += 5;
        if (directionChanges >= 6)    confidence += 5;
        if (rangePct < 2.0)           confidence += 5;

        double entry      = r4(midPrice);
        double stopLoss   = r4(rangeLow  - dailyAtr * 0.3);
        double takeProfit = r4(rangeHigh + dailyAtr * 0.3);

        TradeSetup setup = TradeSetup.builder()
                .ticker(ticker)
                .direction("range")
                .entry(entry)
                .stopLoss(stopLoss)
                .takeProfit(takeProfit)
                .atr(r4(dailyAtr))
                .volatility("range")
                .fvgTop(r4(rangeHigh))
                .fvgBottom(r4(rangeLow))
                .confidence(Math.min(confidence, 100))
                .hasBos(false)
                .hasChoch(false)
                .timestamp(LocalDateTime.now())
                .build();

        return List.of(setup);
    }

    // ── Helper: compute EMA seeded with SMA ────────────────────────────────────

    private double computeEma(List<OHLCV> bars, int period) {
        if (bars.size() < period) return 0.0;

        // Seed with SMA of first `period` bars
        double sma = 0;
        for (int i = 0; i < period; i++) {
            sma += bars.get(i).getClose();
        }
        sma /= period;

        double multiplier = 2.0 / (period + 1);
        double ema = sma;
        for (int i = period; i < bars.size(); i++) {
            ema = (bars.get(i).getClose() - ema) * multiplier + ema;
        }
        return ema;
    }

    /**
     * Check that the EMA is flat over the last 5 daily bars
     * (slope within +/-0.3% of EMA value).
     */
    private boolean isEmaFlat(List<OHLCV> dailyBars, int period) {
        if (dailyBars.size() < period + 5) return false;

        // Compute EMA at bar (size-6) and at bar (size-1) by walking the full series
        double multiplier = 2.0 / (period + 1);

        double sma = 0;
        for (int i = 0; i < period; i++) {
            sma += dailyBars.get(i).getClose();
        }
        sma /= period;

        double ema = sma;
        double emaAtStart = 0;
        int startIdx = dailyBars.size() - 6;  // 5 bars ago
        int endIdx   = dailyBars.size() - 1;

        for (int i = period; i <= endIdx; i++) {
            ema = (dailyBars.get(i).getClose() - ema) * multiplier + ema;
            if (i == startIdx) emaAtStart = ema;
        }
        double emaAtEnd = ema;

        if (emaAtStart == 0) return false;
        double slopePct = Math.abs(emaAtEnd - emaAtStart) / emaAtStart * 100.0;
        return slopePct <= 0.3;
    }

    // ── Helper: round to 4 decimal places ──────────────────────────────────────

    private double r4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
