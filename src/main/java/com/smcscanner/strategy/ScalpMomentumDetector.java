package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated momentum scalp detector.
 *
 * Goal: catch a live impulse sequence early, ride the train briefly,
 * and get off as soon as momentum fades.
 */
@Service
public class ScalpMomentumDetector {
    private static final ZoneId ET = ZoneId.of("America/New_York");

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 8) return result;

        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp()).atZone(ET).toLocalDate();
        LocalTime mktOpen = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);

        List<OHLCV> sessionBars = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                sessionBars.add(bar);
            }
        }
        if (sessionBars.size() < 8) return result;

        OHLCV last = sessionBars.get(sessionBars.size() - 1);
        LocalTime now = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalTime();
        if (now.isBefore(LocalTime.of(9, 35)) || !now.isBefore(LocalTime.of(15, 30))) return result;

        double atr = Math.max(computeAtr(sessionBars), last.getClose() * 0.0015);

        int seqLen = Math.min(4, sessionBars.size());
        int baseLen = Math.min(6, sessionBars.size() - seqLen);
        if (seqLen < 3 || baseLen < 4) return result;

        List<OHLCV> sequence = sessionBars.subList(sessionBars.size() - seqLen, sessionBars.size());
        List<OHLCV> base = sessionBars.subList(sessionBars.size() - seqLen - baseLen, sessionBars.size() - seqLen);
        OHLCV firstSeq = sequence.get(0);
        OHLCV prevBar = base.get(base.size() - 1);

        double avgVol = base.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        double avgRange = base.stream().mapToDouble(b -> b.getHigh() - b.getLow()).average().orElse(0.01);
        double sequenceAvgVol = sequence.stream().mapToDouble(OHLCV::getVolume).average().orElse(avgVol);
        double sequenceRange = sequence.stream().mapToDouble(b -> b.getHigh() - b.getLow()).sum();
        double highestBase = base.stream().mapToDouble(OHLCV::getHigh).max().orElse(prevBar.getHigh());
        double lowestBase = base.stream().mapToDouble(OHLCV::getLow).min().orElse(prevBar.getLow());
        double sessionOpen = sessionBars.get(0).getOpen();
        double sessionVwap = computeSessionVwap(sessionBars, sessionBars.size() - 1);
        double prevSessionVwap = computeSessionVwap(sessionBars, sessionBars.size() - 2);
        double vwapDistance = Math.abs(last.getClose() - sessionVwap);
        double moveFromOpen = Math.abs(last.getClose() - sessionOpen) / Math.max(0.01, atr);
        double cumulativeUpMove = last.getClose() - firstSeq.getOpen();
        double cumulativeDownMove = firstSeq.getOpen() - last.getClose();
        double strongImpulseMove = Math.max(atr * 0.85, last.getClose() * 0.0035);
        double extensionCap = atr * 2.2;
        double breakoutBuffer = Math.max(atr * 0.08, last.getClose() * 0.0006);

        int greenCount = 0;
        int redCount = 0;
        int closeNearHighCount = 0;
        int closeNearLowCount = 0;
        int rangeExpansionCount = 0;
        int volumeExpansionCount = 0;
        int strongBullCount = 0;
        int strongBearCount = 0;
        for (OHLCV bar : sequence) {
            double range = Math.max(0.0001, bar.getHigh() - bar.getLow());
            double bodyPct = Math.abs(bar.getClose() - bar.getOpen()) / range;
            double rangeRatio = range / Math.max(avgRange, 0.01);
            double volRatio = bar.getVolume() / Math.max(avgVol, 1.0);
            if (bar.getClose() > bar.getOpen()) greenCount++;
            if (bar.getClose() < bar.getOpen()) redCount++;
            if ((bar.getHigh() - bar.getClose()) <= range * 0.25) closeNearHighCount++;
            if ((bar.getClose() - bar.getLow()) <= range * 0.25) closeNearLowCount++;
            if (rangeRatio >= 1.15 && bodyPct >= 0.45) rangeExpansionCount++;
            if (volRatio >= 1.25) volumeExpansionCount++;
            if (bar.getClose() > bar.getOpen() && bodyPct >= 0.55 && rangeRatio >= 1.20) strongBullCount++;
            if (bar.getClose() < bar.getOpen() && bodyPct >= 0.55 && rangeRatio >= 1.20) strongBearCount++;
        }

        OHLCV prev2 = sequence.get(sequence.size() - 2);
        OHLCV prev3 = sequence.get(sequence.size() - 3);
        double lastBodyPct = Math.abs(last.getClose() - last.getOpen()) / Math.max(0.0001, last.getHigh() - last.getLow());
        double prev2BodyPct = Math.abs(prev2.getClose() - prev2.getOpen()) / Math.max(0.0001, prev2.getHigh() - prev2.getLow());
        boolean lastStrongBull = last.getClose() > last.getOpen() && lastBodyPct >= 0.55;
        boolean lastStrongBear = last.getClose() < last.getOpen() && lastBodyPct >= 0.55;
        boolean prev2StrongBull = prev2.getClose() > prev2.getOpen() && prev2BodyPct >= 0.55;
        boolean prev2StrongBear = prev2.getClose() < prev2.getOpen() && prev2BodyPct >= 0.55;
        boolean consecutiveBullImpulse = prev2StrongBull && lastStrongBull
                && prev2.getClose() >= prev2.getHigh() - (prev2.getHigh() - prev2.getLow()) * 0.25
                && last.getClose() >= last.getHigh() - (last.getHigh() - last.getLow()) * 0.25;
        boolean consecutiveBearImpulse = prev2StrongBear && lastStrongBear
                && prev2.getClose() <= prev2.getLow() + (prev2.getHigh() - prev2.getLow()) * 0.25
                && last.getClose() <= last.getLow() + (last.getHigh() - last.getLow()) * 0.25;
        boolean recentOpposingLong = prev3.getClose() < prev3.getOpen() && prev3BodyPct(prev3) >= 0.45;
        boolean recentOpposingShort = prev3.getClose() > prev3.getOpen() && prev3BodyPct(prev3) >= 0.45;

        boolean closesStackedUp = last.getClose() > sequence.get(sequence.size() - 2).getClose()
                && sequence.get(sequence.size() - 2).getClose() >= sequence.get(sequence.size() - 3).getClose();
        boolean closesStackedDown = last.getClose() < sequence.get(sequence.size() - 2).getClose()
                && sequence.get(sequence.size() - 2).getClose() <= sequence.get(sequence.size() - 3).getClose();
        boolean vwapAlignedLong = last.getClose() > sessionVwap && sessionVwap >= prevSessionVwap;
        boolean vwapAlignedShort = last.getClose() < sessionVwap && sessionVwap <= prevSessionVwap;
        boolean baseBreakLong = firstSeq.getOpen() > highestBase - breakoutBuffer || last.getClose() > highestBase + breakoutBuffer;
        boolean baseBreakShort = firstSeq.getOpen() < lowestBase + breakoutBuffer || last.getClose() < lowestBase - breakoutBuffer;
        boolean volumeTrendUp = sequenceAvgVol >= avgVol * 1.35;
        boolean notTooExtended = vwapDistance <= extensionCap;

        boolean longImpulse = greenCount >= 3
                && strongBullCount >= 2
                && closeNearHighCount >= 2
                && rangeExpansionCount >= 2
                && volumeExpansionCount >= 2
                && consecutiveBullImpulse
                && closesStackedUp
                && cumulativeUpMove >= strongImpulseMove
                && volumeTrendUp
                && vwapAlignedLong
                && baseBreakLong
                && !recentOpposingLong
                && notTooExtended;

        boolean shortImpulse = redCount >= 3
                && strongBearCount >= 2
                && closeNearLowCount >= 2
                && rangeExpansionCount >= 2
                && volumeExpansionCount >= 2
                && consecutiveBearImpulse
                && closesStackedDown
                && cumulativeDownMove >= strongImpulseMove
                && volumeTrendUp
                && vwapAlignedShort
                && baseBreakShort
                && !recentOpposingShort
                && notTooExtended;

        if (!longImpulse && !shortImpulse) return result;

        boolean isLong = longImpulse;
        double lastRange = Math.max(0.0001, last.getHigh() - last.getLow());
        double entry = r4(last.getClose());
        double stop = isLong
                ? r4(Math.min(sequence.get(sequence.size() - 2).getLow(), last.getLow()) - Math.max(atr * 0.12, lastRange * 0.20))
                : r4(Math.max(sequence.get(sequence.size() - 2).getHigh(), last.getHigh()) + Math.max(atr * 0.12, lastRange * 0.20));
        double risk = Math.abs(entry - stop);
        if (risk <= 0) return result;

        double tp = isLong ? r4(entry + risk * 0.75) : r4(entry - risk * 0.75);

        int confidence = 68;
        if (sequenceAvgVol >= avgVol * 1.6) confidence += 6;
        if (sequenceRange >= avgRange * 3.8) confidence += 5;
        if (Math.max(cumulativeUpMove, cumulativeDownMove) >= atr * 1.2) confidence += 5;
        if (dailyAtr > 0 && sequenceRange >= dailyAtr * 0.18) confidence += 4;
        if (moveFromOpen >= 1.0) confidence += 3;
        confidence = Math.min(90, confidence);

        ZonedDateTime signalTs = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET);
        String factors = String.format("seq %s%d/%d strong=%d | vol x%.1f | move %.2f ATR | VWAP %s | open %.1f ATR",
                isLong ? "g" : "r",
                isLong ? greenCount : redCount,
                sequence.size(),
                isLong ? strongBullCount : strongBearCount,
                sequenceAvgVol / Math.max(avgVol, 1.0),
                Math.max(cumulativeUpMove, cumulativeDownMove) / Math.max(atr, 0.01),
                isLong ? "above" : "below",
                moveFromOpen);

        result.add(TradeSetup.builder()
                .ticker(ticker)
                .direction(isLong ? "long" : "short")
                .entry(entry)
                .stopLoss(stop)
                .takeProfit(tp)
                .confidence(confidence)
                .session("NYSE")
                .volatility("scalp")
                .atr(atr)
                .hasBos(false)
                .hasChoch(false)
                .fvgTop(r4(highestBase))
                .fvgBottom(r4(lowestBase))
                .factorBreakdown(factors)
                .timestamp(signalTs.toLocalDateTime())
                .build());

        return result;
    }

    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(8, bars.size() - 1);
        if (period <= 0) return 0.0;
        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            OHLCV curr = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(),
                    Math.max(Math.abs(curr.getHigh() - prev.getClose()),
                            Math.abs(curr.getLow() - prev.getClose())));
            sum += tr;
        }
        return sum / period;
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

    private double prev3BodyPct(OHLCV bar) {
        return Math.abs(bar.getClose() - bar.getOpen()) / Math.max(0.0001, bar.getHigh() - bar.getLow());
    }

    private double r4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
