package com.smcscanner.indicator;

import com.smcscanner.model.OHLCV;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Technical indicator calculations: SMA 200, RSI 14, candle patterns, volume confirmation.
 * Used as confidence adjustments in ScannerService — NOT hard gates.
 */
@Service
public class TechnicalIndicators {

    // ── SMA 200 ─────────────────────────────────────────────────────────────

    /**
     * Compute SMA from daily bar closes.
     * @param dailyBars daily OHLCV bars
     * @param period    SMA period (e.g. 200)
     * @return SMA value, or 0 if not enough bars
     */
    public double sma(List<OHLCV> dailyBars, int period) {
        if (dailyBars == null || dailyBars.size() < period) return 0.0;
        double sum = 0;
        for (int i = dailyBars.size() - period; i < dailyBars.size(); i++) {
            sum += dailyBars.get(i).getClose();
        }
        return sum / period;
    }

    /**
     * SMA 200 confidence adjustment.
     * - Long above SMA 200: +5 (with trend)
     * - Short below SMA 200: +5 (with trend)
     * - Long below SMA 200: -10 (counter-trend, fighting institutions)
     * - Short above SMA 200: -10 (counter-trend)
     * - Price within 0.5% of SMA 200: 0 (too close to call)
     *
     * @return confidence delta
     */
    public int sma200Delta(List<OHLCV> dailyBars, double currentPrice, String direction) {
        double sma200 = sma(dailyBars, 200);
        if (sma200 <= 0) return 0; // not enough data

        double distPct = (currentPrice - sma200) / sma200;

        // Too close to the SMA — no clear bias
        if (Math.abs(distPct) < 0.005) return 0;

        boolean above = distPct > 0;
        boolean isLong = "long".equals(direction);

        if (isLong && above) return +5;   // long with trend
        if (!isLong && !above) return +5; // short with trend
        if (isLong && !above) return -10; // long against 200 SMA
        if (!isLong && above) return -10; // short against 200 SMA

        return 0;
    }

    // ── RSI 14 ──────────────────────────────────────────────────────────────

    /**
     * Compute 14-period RSI from bar closes.
     * Uses standard Wilder smoothing (exponential).
     *
     * @param bars OHLCV bars (needs 15+ bars)
     * @return RSI value (0-100), or 50 if insufficient data
     */
    public double rsi(List<OHLCV> bars, int period) {
        if (bars == null || bars.size() < period + 1) return 50.0;

        double gainSum = 0, lossSum = 0;

        // Initial average gain/loss over first `period` bars
        for (int i = 1; i <= period; i++) {
            double change = bars.get(i).getClose() - bars.get(i - 1).getClose();
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        // Wilder smoothing for remaining bars
        for (int i = period + 1; i < bars.size(); i++) {
            double change = bars.get(i).getClose() - bars.get(i - 1).getClose();
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * RSI-based confidence adjustment.
     * - Long when RSI > 75 (overbought): -8 (buying into exhaustion)
     * - Short when RSI < 25 (oversold): -8 (shorting into exhaustion)
     * - Long when RSI 30-50 (oversold bounce): +5 (buying the dip with momentum)
     * - Short when RSI 50-70 (overbought fade): +5 (selling the rip)
     * - Long when RSI < 20 (extreme oversold): -12 (falling knife)
     * - Short when RSI > 80 (extreme overbought): -12 (short squeeze risk)
     *
     * @param bars      5-minute bars for RSI calculation
     * @param direction "long" or "short"
     * @return confidence delta
     */
    public int rsiDelta(List<OHLCV> bars, String direction) {
        double rsiVal = rsi(bars, 14);
        boolean isLong = "long".equals(direction);

        if (isLong) {
            if (rsiVal > 80) return -12;      // extreme overbought — don't chase
            if (rsiVal > 75) return -8;       // overbought
            if (rsiVal >= 30 && rsiVal <= 50) return +5; // oversold bounce zone
            if (rsiVal < 20) return -12;      // extreme oversold — falling knife
        } else {
            if (rsiVal < 20) return -12;      // extreme oversold — don't short
            if (rsiVal < 25) return -8;       // oversold
            if (rsiVal >= 50 && rsiVal <= 70) return +5; // overbought fade zone
            if (rsiVal > 80) return -12;      // extreme overbought — squeeze risk
        }
        return 0;
    }

    // ── Candle Pattern Recognition ──────────────────────────────────────────

    /**
     * Detect if the last bar (or second-to-last) is a rejection candle pattern.
     * Checks: hammer, inverted hammer, engulfing, pin bar.
     *
     * @param bars      5-minute session bars
     * @param direction expected trade direction
     * @return confidence delta: +8 for strong pattern, +5 for moderate, 0 for none
     */
    public int candlePatternDelta(List<OHLCV> bars, String direction) {
        if (bars == null || bars.size() < 3) return 0;

        OHLCV last = bars.get(bars.size() - 1);
        OHLCV prev = bars.get(bars.size() - 2);
        boolean isLong = "long".equals(direction);

        // ── Bullish patterns (for long entries) ────────────────────────────
        if (isLong) {
            // Hammer: long lower wick, small body at top
            if (isHammer(last)) return +8;
            if (isHammer(prev) && last.getClose() > prev.getClose()) return +5; // confirmed hammer

            // Bullish engulfing: prev red, current green body fully engulfs prev
            if (isBullishEngulfing(prev, last)) return +8;

            // Pin bar (bullish): very long lower wick, tiny body
            if (isBullishPinBar(last)) return +5;
        }

        // ── Bearish patterns (for short entries) ───────────────────────────
        if (!isLong) {
            // Shooting star (inverted hammer at top)
            if (isShootingStar(last)) return +8;
            if (isShootingStar(prev) && last.getClose() < prev.getClose()) return +5;

            // Bearish engulfing
            if (isBearishEngulfing(prev, last)) return +8;

            // Pin bar (bearish): very long upper wick, tiny body
            if (isBearishPinBar(last)) return +5;
        }

        return 0;
    }

    /** Hammer: lower wick >= 2x body, upper wick < body */
    private boolean isHammer(OHLCV bar) {
        double body = Math.abs(bar.getClose() - bar.getOpen());
        double range = bar.getHigh() - bar.getLow();
        if (range <= 0 || body <= 0) return false;
        double lowerWick = Math.min(bar.getOpen(), bar.getClose()) - bar.getLow();
        double upperWick = bar.getHigh() - Math.max(bar.getOpen(), bar.getClose());
        return lowerWick >= body * 2.0 && upperWick < body && bar.getClose() > bar.getOpen();
    }

    /** Shooting star: upper wick >= 2x body, lower wick < body */
    private boolean isShootingStar(OHLCV bar) {
        double body = Math.abs(bar.getClose() - bar.getOpen());
        double range = bar.getHigh() - bar.getLow();
        if (range <= 0 || body <= 0) return false;
        double upperWick = bar.getHigh() - Math.max(bar.getOpen(), bar.getClose());
        double lowerWick = Math.min(bar.getOpen(), bar.getClose()) - bar.getLow();
        return upperWick >= body * 2.0 && lowerWick < body && bar.getClose() < bar.getOpen();
    }

    /** Bullish engulfing: prev red, current green, current body engulfs prev body */
    private boolean isBullishEngulfing(OHLCV prev, OHLCV curr) {
        boolean prevRed = prev.getClose() < prev.getOpen();
        boolean currGreen = curr.getClose() > curr.getOpen();
        return prevRed && currGreen
                && curr.getClose() > prev.getOpen()
                && curr.getOpen() < prev.getClose();
    }

    /** Bearish engulfing: prev green, current red, current body engulfs prev body */
    private boolean isBearishEngulfing(OHLCV prev, OHLCV curr) {
        boolean prevGreen = prev.getClose() > prev.getOpen();
        boolean currRed = curr.getClose() < curr.getOpen();
        return prevGreen && currRed
                && curr.getClose() < prev.getOpen()
                && curr.getOpen() > prev.getClose();
    }

    /** Bullish pin bar: lower wick >= 3x body, body in top 30% of range */
    private boolean isBullishPinBar(OHLCV bar) {
        double body = Math.abs(bar.getClose() - bar.getOpen());
        double range = bar.getHigh() - bar.getLow();
        if (range <= 0) return false;
        double lowerWick = Math.min(bar.getOpen(), bar.getClose()) - bar.getLow();
        double bodyTop = Math.max(bar.getOpen(), bar.getClose());
        return lowerWick >= body * 3.0 && (bodyTop - bar.getLow()) > range * 0.7;
    }

    /** Bearish pin bar: upper wick >= 3x body, body in bottom 30% of range */
    private boolean isBearishPinBar(OHLCV bar) {
        double body = Math.abs(bar.getClose() - bar.getOpen());
        double range = bar.getHigh() - bar.getLow();
        if (range <= 0) return false;
        double upperWick = bar.getHigh() - Math.max(bar.getOpen(), bar.getClose());
        double bodyBottom = Math.min(bar.getOpen(), bar.getClose());
        return upperWick >= body * 3.0 && (bar.getHigh() - bodyBottom) > range * 0.7;
    }

    // ── Volume Confirmation ─────────────────────────────────────────────────

    /**
     * Volume confirmation delta.
     * Checks if setup candle has above-average volume.
     *
     * @param bars session bars
     * @return +5 if volume spike (>1.5x avg), -5 if low volume (<0.7x avg), 0 otherwise
     */
    public int volumeDelta(List<OHLCV> bars) {
        if (bars == null || bars.size() < 10) return 0;

        // Compute 20-bar average volume (or available bars)
        int lookback = Math.min(20, bars.size() - 1);
        double sumVol = 0;
        for (int i = bars.size() - 1 - lookback; i < bars.size() - 1; i++) {
            sumVol += bars.get(i).getVolume();
        }
        double avgVol = sumVol / lookback;
        if (avgVol <= 0) return 0;

        double lastVol = bars.get(bars.size() - 1).getVolume();
        double ratio = lastVol / avgVol;

        if (ratio >= 2.0) return +8;  // strong volume surge
        if (ratio >= 1.5) return +5;  // above-average volume
        if (ratio < 0.7) return -5;   // low volume — weak conviction
        return 0;
    }
}
