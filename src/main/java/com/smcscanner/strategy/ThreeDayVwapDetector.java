package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * 3-Day VWAP mean-reversion strategy.
 *
 * Computes VWAP over the last 3 trading sessions (anchored, more stable than 1-day).
 * Trades reversion when price deviates ≥ 1.5 SD from the 3-day VWAP reference.
 *
 * AAPL rationale: AAPL's institutional cost basis spans multiple days.
 * Single-session VWAP resets every morning and misses the multi-day mean.
 * The 3-day VWAP captures where large participants are positioned near-term.
 *
 * Caller must pass bars spanning at least 2–3 trading sessions for the anchor to work.
 */
@Service
public class ThreeDayVwapDetector {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * @param multiDayBars  5m bars spanning the last 2–3 trading sessions
     * @param ticker        ticker symbol
     * @param dailyAtr      daily ATR for sizing
     */
    public List<TradeSetup> detect(List<OHLCV> multiDayBars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (multiDayBars == null || multiDayBars.size() < 20) return result;

        // Filter to NYSE session bars only (9:30–16:00 ET), any date
        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);
        List<OHLCV> sessionBars = new ArrayList<>();
        for (OHLCV bar : multiDayBars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (!zdt.toLocalTime().isBefore(mktOpen) && zdt.toLocalTime().isBefore(mktClose)) {
                sessionBars.add(bar);
            }
        }
        if (sessionBars.size() < 20) return result;

        // 3-day VWAP over all session bars
        double sumTpVol = 0, sumVol = 0;
        for (OHLCV bar : sessionBars) {
            double tp = (bar.getHigh() + bar.getLow() + bar.getClose()) / 3.0;
            sumTpVol += tp * bar.getVolume();
            sumVol   += bar.getVolume();
        }
        double vwap3d = sumVol > 0 ? sumTpVol / sumVol : sessionBars.get(sessionBars.size()-1).getClose();

        // Standard deviation of closes from 3d VWAP
        double sumSqDev = 0;
        for (OHLCV bar : sessionBars) { double d = bar.getClose() - vwap3d; sumSqDev += d * d; }
        double stdDev = Math.sqrt(sumSqDev / sessionBars.size());

        OHLCV last   = sessionBars.get(sessionBars.size() - 1);
        double close = last.getClose();
        double zScore = stdDev > 0 ? (close - vwap3d) / stdDev : 0;

        double atr5m = computeAtr(sessionBars);
        double atr   = Math.max(atr5m, close * 0.001);

        // Average volume from today's bars only
        LocalDate today = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDate();
        double avgVol = sessionBars.stream()
                .filter(b -> Instant.ofEpochMilli(b.getTimestamp()).atZone(ET).toLocalDate().equals(today))
                .mapToDouble(OHLCV::getVolume).average()
                .orElse(sessionBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0));

        // Z-Score velocity: must be reverting (moving back toward vwap3d)
        double prevZ = 0;
        if (sessionBars.size() >= 2) {
            double pc = sessionBars.get(sessionBars.size() - 2).getClose();
            prevZ = stdDev > 0 ? (pc - vwap3d) / stdDev : 0;
        }
        boolean reverting = Math.abs(zScore) < Math.abs(prevZ);

        // LONG: price below 3d VWAP by ≥ 1.5 SD, bouncing back
        if (zScore < -1.5 && reverting && close < vwap3d) {
            OHLCV confirmBar = last;
            if (sessionBars.size() >= 2) {
                OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                if (prev.getClose() > prev.getOpen() && prev.getVolume() > avgVol * 1.1
                        && last.getClose() >= prev.getClose() * 0.998) {
                    confirmBar = prev;
                }
            }
            if (confirmBar.getClose() > confirmBar.getOpen() && confirmBar.getVolume() > avgVol * 1.0) {
                double entry = r4(close);
                double sl    = r4(entry - atr * 0.5);
                double risk  = entry - sl;
                double tp    = r4(vwap3d + stdDev * 0.3);
                if (tp < r4(entry + risk * 2.0)) tp = r4(entry + risk * 2.0);

                int conf = 65;
                if (zScore < -2.0) conf += 5;
                if (last.getVolume() > avgVol * 1.5) conf += 5;
                if (sessionBars.size() > 40) conf += 5; // multi-day anchor is stronger with more data

                if (sl < entry && tp > entry) {
                    result.add(TradeSetup.builder()
                            .ticker(ticker).direction("long")
                            .entry(entry).stopLoss(sl).takeProfit(tp)
                            .confidence(conf).session("NYSE").volatility("vwap3d")
                            .atr(atr).hasBos(false).hasChoch(false)
                            .fvgTop(r4(vwap3d)).fvgBottom(r4(close - atr))
                            .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime()).build());
                }
            }
        }

        // SHORT: price above 3d VWAP by ≥ 1.5 SD, fading back
        if (zScore > 1.5 && reverting && close > vwap3d && result.isEmpty()) {
            OHLCV confirmBar = last;
            if (sessionBars.size() >= 2) {
                OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                if (prev.getClose() < prev.getOpen() && prev.getVolume() > avgVol * 1.1
                        && last.getClose() <= prev.getClose() * 1.002) {
                    confirmBar = prev;
                }
            }
            if (confirmBar.getClose() < confirmBar.getOpen() && confirmBar.getVolume() > avgVol * 1.0) {
                double entry = r4(close);
                double sl    = r4(entry + atr * 0.5);
                double risk  = sl - entry;
                double tp    = r4(vwap3d - stdDev * 0.3);
                if (tp > r4(entry - risk * 2.0)) tp = r4(entry - risk * 2.0);

                int conf = 65;
                if (zScore > 2.0) conf += 5;
                if (last.getVolume() > avgVol * 1.5) conf += 5;
                if (sessionBars.size() > 40) conf += 5;

                if (sl > entry && tp < entry) {
                    result.add(TradeSetup.builder()
                            .ticker(ticker).direction("short")
                            .entry(entry).stopLoss(sl).takeProfit(tp)
                            .confidence(conf).session("NYSE").volatility("vwap3d")
                            .atr(atr).hasBos(false).hasChoch(false)
                            .fvgTop(r4(close + atr)).fvgBottom(r4(vwap3d))
                            .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime()).build());
                }
            }
        }
        return result;
    }

    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0.001;
        int start = bars.size() - period - 1;
        double sum = 0;
        for (int i = start + 1; i < bars.size(); i++) {
            OHLCV c = bars.get(i), p = bars.get(i - 1);
            double tr = Math.max(c.getHigh() - c.getLow(),
                        Math.max(Math.abs(c.getHigh() - p.getClose()),
                                 Math.abs(c.getLow()  - p.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    private double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
