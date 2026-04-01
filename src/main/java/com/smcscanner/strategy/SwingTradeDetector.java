package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Swing trade detector for 2–5 day hold setups.
 * Scans daily bars for consolidation ("squeeze zones"), then confirms
 * breakout direction using hourly bars with volume confirmation.
 * Detected setups use volatility="swing" for Discord routing.
 */
@Service
public class SwingTradeDetector {

    /**
     * Detect swing trade setups using hourly consolidation + daily structure.
     *
     * @param hourlyBars  1-hour OHLCV bars (50+)
     * @param dailyBars   daily OHLCV bars (20+)
     * @param ticker      symbol
     * @param dailyAtr    daily ATR
     * @return 0 or 1 setups with volatility="swing"
     */
    public List<TradeSetup> detect(List<OHLCV> hourlyBars, List<OHLCV> dailyBars,
                                    String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (hourlyBars == null || hourlyBars.size() < 50) return result;
        if (dailyBars == null || dailyBars.size() < 20) return result;

        // ── 1. Daily consolidation scan ─────────────────────────────────────
        // Look in the last 10 daily bars for 3+ consecutive bars where
        // high-low range is within a 2% band = "squeeze zone".
        double rangeHigh = -Double.MAX_VALUE;
        double rangeLow  = Double.MAX_VALUE;
        boolean squeezeFound = false;
        boolean tightSqueeze = false; // squeeze < 1.5%

        int scanStart = Math.max(0, dailyBars.size() - 10);
        int bestRunStart = -1;
        int bestRunLen   = 0;

        int runStart = scanStart;
        int runLen    = 0;

        for (int i = scanStart; i < dailyBars.size(); i++) {
            OHLCV bar = dailyBars.get(i);
            double mid = (bar.getHigh() + bar.getLow()) / 2.0;
            double rangePct = mid > 0 ? (bar.getHigh() - bar.getLow()) / mid * 100.0 : 0.0;

            if (rangePct <= 2.0) {
                if (runLen == 0) runStart = i;
                runLen++;
                if (runLen >= 3 && runLen > bestRunLen) {
                    bestRunLen   = runLen;
                    bestRunStart = runStart;
                }
            } else {
                runLen = 0;
            }
        }

        if (bestRunLen < 3) return result; // no consolidation found

        // Compute rangeHigh / rangeLow from the squeeze bars
        for (int i = bestRunStart; i < bestRunStart + bestRunLen; i++) {
            OHLCV bar = dailyBars.get(i);
            if (bar.getHigh() > rangeHigh) rangeHigh = bar.getHigh();
            if (bar.getLow()  < rangeLow)  rangeLow  = bar.getLow();
        }
        squeezeFound = true;

        double squeezeMid = (rangeHigh + rangeLow) / 2.0;
        double squeezePct = squeezeMid > 0 ? (rangeHigh - rangeLow) / squeezeMid * 100.0 : 0.0;
        tightSqueeze = squeezePct < 1.5;

        // ── 2. 20-bar EMA on daily closes ───────────────────────────────────
        int emaPeriod = Math.min(20, dailyBars.size());
        double ema20 = computeEma(dailyBars, emaPeriod);

        // EMA slope: compare current EMA to EMA computed 5 bars earlier
        boolean emaFlat     = false;
        boolean emaRising   = false;
        boolean emaDeclining = false;

        if (dailyBars.size() > emaPeriod + 5) {
            // EMA as of 5 bars ago: use sublist ending 5 bars before end
            List<OHLCV> earlier = dailyBars.subList(0, dailyBars.size() - 5);
            double ema20Earlier = computeEma(earlier, emaPeriod);

            double slopePct = ema20Earlier > 0
                    ? (ema20 - ema20Earlier) / ema20Earlier * 100.0 : 0.0;

            if (Math.abs(slopePct) <= 0.3) {
                emaFlat = true;
            } else if (slopePct > 0.3) {
                emaRising = true;
            } else {
                emaDeclining = true;
            }
        } else {
            emaFlat = true; // not enough data to determine slope
        }

        // ── 3. Hourly breakout confirmation ─────────────────────────────────
        // Average hourly volume
        double hourlyVolSum = 0.0;
        for (OHLCV bar : hourlyBars) {
            hourlyVolSum += bar.getVolume();
        }
        double avgHourlyVol = hourlyVolSum / hourlyBars.size();

        OHLCV lastBar  = hourlyBars.get(hourlyBars.size() - 1);
        OHLCV prevBar  = hourlyBars.get(hourlyBars.size() - 2);

        double lastClose = lastBar.getClose();
        double prevClose = prevBar.getClose();

        // LONG breakout: both bars close above rangeHigh, above 20EMA, volume > 1.4x avg
        boolean longBreakout =
                prevClose > rangeHigh && prevClose > ema20
                && prevBar.getVolume() > avgHourlyVol * 1.4
                && lastClose > rangeHigh && lastClose > ema20
                && lastBar.getVolume() > avgHourlyVol * 1.4;

        // SHORT breakout: both bars close below rangeLow, below 20EMA, volume > 1.4x avg
        boolean shortBreakout =
                prevClose < rangeLow && prevClose < ema20
                && prevBar.getVolume() > avgHourlyVol * 1.4
                && lastClose < rangeLow && lastClose < ema20
                && lastBar.getVolume() > avgHourlyVol * 1.4;

        if (!longBreakout && !shortBreakout) return result;

        // ── 4. Risk / Reward ────────────────────────────────────────────────
        if (longBreakout) {
            double entry = r4(lastClose);
            double sl    = r4(rangeLow - dailyAtr * 0.5);
            double risk  = entry - sl;
            double tp    = r4(entry + risk * 2.0); // minimum 2:1 R:R

            if (sl >= entry || tp <= entry) return result;

            // ── 5. Confidence scoring ───────────────────────────────────────
            int confidence = 65;
            if (lastBar.getVolume() > avgHourlyVol * 2.0)   confidence += 5; // volume spike
            if (emaRising)                                    confidence += 5; // EMA trending with setup
            if (tightSqueeze)                                 confidence += 5; // tighter squeeze
            if (emaDeclining)                                 confidence -= 10; // counter-EMA

            result.add(TradeSetup.builder()
                    .ticker(ticker)
                    .direction("long")
                    .entry(entry)
                    .stopLoss(sl)
                    .takeProfit(tp)
                    .confidence(confidence)
                    .session("NYSE")
                    .volatility("swing")
                    .atr(dailyAtr)
                    .hasBos(false)
                    .hasChoch(false)
                    .fvgTop(r4(rangeHigh))
                    .fvgBottom(r4(rangeLow))
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        if (shortBreakout) {
            double entry = r4(lastClose);
            double sl    = r4(rangeHigh + dailyAtr * 0.5);
            double risk  = sl - entry;
            double tp    = r4(entry - risk * 2.0); // minimum 2:1 R:R

            if (sl <= entry || tp >= entry) return result;

            // ── 5. Confidence scoring ───────────────────────────────────────
            int confidence = 65;
            if (lastBar.getVolume() > avgHourlyVol * 2.0)   confidence += 5; // volume spike
            if (emaDeclining)                                 confidence += 5; // EMA trending with setup
            if (tightSqueeze)                                 confidence += 5; // tighter squeeze
            if (emaRising)                                    confidence -= 10; // counter-EMA

            result.add(TradeSetup.builder()
                    .ticker(ticker)
                    .direction("short")
                    .entry(entry)
                    .stopLoss(sl)
                    .takeProfit(tp)
                    .confidence(confidence)
                    .session("NYSE")
                    .volatility("swing")
                    .atr(dailyAtr)
                    .hasBos(false)
                    .hasChoch(false)
                    .fvgTop(r4(rangeHigh))
                    .fvgBottom(r4(rangeLow))
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        // If both LONG and SHORT fired, keep only the higher-confidence one
        if (result.size() > 1) {
            result.sort((a, b) -> Integer.compare(b.getConfidence(), a.getConfidence()));
            TradeSetup best = result.get(0);
            result.clear();
            result.add(best);
        }

        return result;
    }

    /**
     * Compute EMA of closes for the given period.
     * Uses standard EMA: multiplier = 2/(period+1), seeded with SMA of first 'period' bars.
     */
    private double computeEma(List<OHLCV> bars, int period) {
        int safePeriod = Math.min(period, bars.size());
        if (safePeriod <= 0) return 0.0;

        // Seed with SMA of first 'safePeriod' bars
        double sma = 0.0;
        for (int i = 0; i < safePeriod; i++) {
            sma += bars.get(i).getClose();
        }
        sma /= safePeriod;

        double multiplier = 2.0 / (safePeriod + 1);
        double ema = sma;
        for (int i = safePeriod; i < bars.size(); i++) {
            ema = (bars.get(i).getClose() - ema) * multiplier + ema;
        }
        return ema;
    }

    /** Round to 4 decimal places. */
    private double r4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
