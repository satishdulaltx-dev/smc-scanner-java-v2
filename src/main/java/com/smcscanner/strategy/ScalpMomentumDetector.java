package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Bollinger-based scalp detector.
 *
 * Research basis:
 * - Band tags are not signals by themselves.
 * - Mean-reversion is best after a band pierce that reclaims back inside.
 * - Trend continuation is best when BandWidth expands after a squeeze.
 * - SPY should not be strongly fighting the setup on equity scalps.
 */
@Service
public class ScalpMomentumDetector {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, List.of(), ticker, dailyAtr);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, List<OHLCV> spyBars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < BB_PERIOD + 3) return result;

        List<OHLCV> sessionBars = regularSessionBarsForToday(bars);
        if (sessionBars.size() < BB_PERIOD + 3) return result;

        OHLCV last = sessionBars.get(sessionBars.size() - 1);
        LocalTime now = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalTime();
        if (now.isBefore(LocalTime.of(9, 35)) || !now.isBefore(LocalTime.of(15, 30))) return result;

        int n = sessionBars.size();
        OHLCV prev = sessionBars.get(n - 2);
        OHLCV prev2 = sessionBars.get(n - 3);

        double atr = Math.max(computeAtr(sessionBars, 8), last.getClose() * 0.0012);
        double effAtr = dailyAtr > atr * 4 ? dailyAtr : atr * 8;
        double sessionVwap = computeSessionVwap(sessionBars, n - 1);
        double avgVol = averageVolume(sessionBars, n - 7, n - 2);
        double volRatio = last.getVolume() / Math.max(1.0, avgVol);

        Bands prevBands = computeBands(sessionBars, n - 2);
        Bands lastBands = computeBands(sessionBars, n - 1);
        if (prevBands == null || lastBands == null) return result;

        double lastPctB = percentB(last.getClose(), lastBands);
        double prevPctB = percentB(prev.getClose(), prevBands);
        double prevLowPctB = percentB(prev.getLow(), prevBands);
        double prevHighPctB = percentB(prev.getHigh(), prevBands);
        double bandwidth = bandWidth(lastBands);
        double prevBandwidth = bandWidth(prevBands);
        double minRecentBandwidth = minRecentBandWidth(sessionBars, n - 10, n - 2);
        boolean squeezeRecently = minRecentBandwidth > 0 && prevBandwidth <= minRecentBandwidth * 1.15;
        boolean widthExpanding = bandwidth > prevBandwidth * 1.08;

        double spyReturn = intradayReturn(spyBars);
        double tickerReturn = intradayReturn(sessionBars);
        double rsLead = tickerReturn - spyReturn;
        boolean spyBull = spyReturn > 0.0015;
        boolean spyBear = spyReturn < -0.0015;

        double lastBodyPct = bodyPct(last);
        double prevBodyPct = bodyPct(prev);
        boolean lastGreen = last.getClose() > last.getOpen();
        boolean lastRed = last.getClose() < last.getOpen();
        boolean prevGreen = prev.getClose() > prev.getOpen();
        boolean prevRed = prev.getClose() < prev.getOpen();
        boolean closeNearHigh = (last.getHigh() - last.getClose()) <= (last.getHigh() - last.getLow()) * 0.25;
        boolean closeNearLow = (last.getClose() - last.getLow()) <= (last.getHigh() - last.getLow()) * 0.25;

        boolean bounceLong = prevLowPctB < 0.05
                && prev.getClose() > prevBands.lower
                && lastGreen
                && prevGreen
                && prevBodyPct >= 0.40
                && lastBodyPct >= 0.50
                && closeNearHigh
                && last.getClose() > prev.getHigh()
                && last.getClose() > sessionVwap
                && !spyBear
                && rsLead > -0.002;

        boolean bounceShort = prevHighPctB > 0.95
                && prev.getClose() < prevBands.upper
                && lastRed
                && prevRed
                && prevBodyPct >= 0.40
                && lastBodyPct >= 0.50
                && closeNearLow
                && last.getClose() < prev.getLow()
                && last.getClose() < sessionVwap
                && !spyBull
                && rsLead < 0.002;

        boolean breakLong = squeezeRecently
                && widthExpanding
                && prevPctB > 0.60
                && lastPctB > 1.0
                && prevGreen
                && lastGreen
                && prevBodyPct >= 0.45
                && lastBodyPct >= 0.55
                && closeNearHigh
                && volRatio >= 1.25
                && last.getClose() > sessionVwap
                && !spyBear
                && rsLead >= 0.0;

        boolean breakShort = squeezeRecently
                && widthExpanding
                && prevPctB < 0.40
                && lastPctB < 0.0
                && prevRed
                && lastRed
                && prevBodyPct >= 0.45
                && lastBodyPct >= 0.55
                && closeNearLow
                && volRatio >= 1.25
                && last.getClose() < sessionVwap
                && !spyBull
                && rsLead <= 0.0;

        boolean isLong = bounceLong || breakLong;
        boolean isShort = bounceShort || breakShort;
        if (!isLong && !isShort) return result;

        String setupType = bounceLong || bounceShort ? "bb-bounce" : "bb-break";
        double entry = round4(last.getClose());
        double stop;
        double tp;
        if (isLong) {
            stop = "bb-bounce".equals(setupType)
                    ? round4(Math.min(prev.getLow(), last.getLow()) - atr * 0.12)
                    : round4(Math.min(lastBands.mid, prev.getLow()) - atr * 0.12);
            double risk = Math.abs(entry - stop);
            if (risk <= 0) return result;
            tp = "bb-bounce".equals(setupType)
                    ? round4(Math.max(lastBands.mid, entry + risk * 1.0))
                    : round4(entry + Math.max(risk * 1.4, effAtr * 0.20));
        } else {
            stop = "bb-bounce".equals(setupType)
                    ? round4(Math.max(prev.getHigh(), last.getHigh()) + atr * 0.12)
                    : round4(Math.max(lastBands.mid, prev.getHigh()) + atr * 0.12);
            double risk = Math.abs(entry - stop);
            if (risk <= 0) return result;
            tp = "bb-bounce".equals(setupType)
                    ? round4(Math.min(lastBands.mid, entry - risk * 1.0))
                    : round4(entry - Math.max(risk * 1.4, effAtr * 0.20));
        }

        int confidence = 70;
        if ("bb-break".equals(setupType)) confidence += 6;
        if (volRatio >= 1.6) confidence += 5;
        if (Math.abs(rsLead) >= 0.003) confidence += 4;
        if (widthExpanding && bandwidth >= minRecentBandwidth * 1.25) confidence += 4;
        confidence = Math.min(90, confidence);

        String factors = String.format("%s | %%B %.2f | BW %.3f→%.3f | vol x%.1f | RS %+.2f%% vs SPY",
                setupType, lastPctB, prevBandwidth, bandwidth, volRatio, rsLead * 100.0);

        result.add(TradeSetup.builder()
                .ticker(ticker)
                .direction(isLong ? "long" : "short")
                .entry(entry)
                .stopLoss(stop)
                .takeProfit(tp)
                .confidence(confidence)
                .session("NYSE")
                .volatility("scalp")
                .atr(round4(atr))
                .hasBos(false)
                .hasChoch(false)
                .fvgTop(round4(lastBands.upper))
                .fvgBottom(round4(lastBands.lower))
                .factorBreakdown(factors)
                .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                .build());

        return result;
    }

    private List<OHLCV> regularSessionBarsForToday(List<OHLCV> bars) {
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp()).atZone(ET).toLocalDate();
        List<OHLCV> result = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            LocalTime time = zdt.toLocalTime();
            if (zdt.toLocalDate().equals(today)
                    && !time.isBefore(LocalTime.of(9, 30))
                    && time.isBefore(LocalTime.of(16, 0))) {
                result.add(bar);
            }
        }
        return result;
    }

    private double computeAtr(List<OHLCV> bars, int period) {
        int p = Math.min(period, bars.size() - 1);
        if (p <= 0) return 0.0;
        double sum = 0.0;
        for (int i = bars.size() - p; i < bars.size(); i++) {
            OHLCV curr = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(),
                    Math.max(Math.abs(curr.getHigh() - prev.getClose()),
                            Math.abs(curr.getLow() - prev.getClose())));
            sum += tr;
        }
        return sum / p;
    }

    private double averageVolume(List<OHLCV> bars, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to = Math.min(bars.size() - 1, toInclusive);
        if (from > to) return 1.0;
        double sum = 0.0;
        int count = 0;
        for (int i = from; i <= to; i++) {
            sum += bars.get(i).getVolume();
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    private double computeSessionVwap(List<OHLCV> bars, int endIdxInclusive) {
        double pv = 0.0;
        double vol = 0.0;
        for (int i = 0; i <= endIdxInclusive && i < bars.size(); i++) {
            OHLCV b = bars.get(i);
            double typical = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
            pv += typical * b.getVolume();
            vol += b.getVolume();
        }
        return vol > 0 ? pv / vol : bars.get(Math.max(0, Math.min(endIdxInclusive, bars.size() - 1))).getClose();
    }

    private double intradayReturn(List<OHLCV> bars) {
        if (bars == null || bars.size() < 2) return 0.0;
        OHLCV first = bars.get(0);
        OHLCV last = bars.get(bars.size() - 1);
        return first.getOpen() > 0 ? (last.getClose() - first.getOpen()) / first.getOpen() : 0.0;
    }

    private double bodyPct(OHLCV bar) {
        double range = Math.max(0.0001, bar.getHigh() - bar.getLow());
        return Math.abs(bar.getClose() - bar.getOpen()) / range;
    }

    private double percentB(double price, Bands bands) {
        double width = Math.max(0.0001, bands.upper - bands.lower);
        return (price - bands.lower) / width;
    }

    private double bandWidth(Bands bands) {
        return bands.mid != 0 ? (bands.upper - bands.lower) / bands.mid : 0.0;
    }

    private double minRecentBandWidth(List<OHLCV> bars, int fromInclusive, int toInclusive) {
        double min = Double.MAX_VALUE;
        for (int i = Math.max(BB_PERIOD - 1, fromInclusive); i <= Math.min(toInclusive, bars.size() - 1); i++) {
            Bands bands = computeBands(bars, i);
            if (bands != null) min = Math.min(min, bandWidth(bands));
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    private Bands computeBands(List<OHLCV> bars, int idx) {
        if (idx < BB_PERIOD - 1) return null;
        int start = idx - BB_PERIOD + 1;
        double sum = 0.0;
        for (int i = start; i <= idx; i++) sum += bars.get(i).getClose();
        double mid = sum / BB_PERIOD;
        double var = 0.0;
        for (int i = start; i <= idx; i++) {
            double diff = bars.get(i).getClose() - mid;
            var += diff * diff;
        }
        double sd = Math.sqrt(var / BB_PERIOD);
        return new Bands(mid, mid + BB_MULT * sd, mid - BB_MULT * sd);
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record Bands(double mid, double upper, double lower) {}
}
