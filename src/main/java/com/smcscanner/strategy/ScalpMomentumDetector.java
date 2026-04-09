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
 * Goal: catch a live impulse, ride the train briefly, and get off quickly.
 * This is intentionally different from the slower intraday setup detectors.
 *
 * Core requirements:
 * - expansion candle
 * - volume burst
 * - breakout through nearby micro structure
 * - close near the candle extreme (little hesitation)
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

        int evalWindow = Math.min(6, sessionBars.size() - 1);
        if (evalWindow < 4) return result;

        List<OHLCV> recent = sessionBars.subList(sessionBars.size() - 1 - evalWindow, sessionBars.size() - 1);
        double avgVol = recent.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        double avgRange = recent.stream().mapToDouble(b -> b.getHigh() - b.getLow()).average().orElse(0.01);
        double atr = Math.max(computeAtr(sessionBars), last.getClose() * 0.0015);

        double range = Math.max(0.0001, last.getHigh() - last.getLow());
        double body = Math.abs(last.getClose() - last.getOpen());
        double bodyPct = body / range;
        double volRatio = avgVol > 0 ? last.getVolume() / avgVol : 1.0;
        double rangeRatio = avgRange > 0 ? range / avgRange : 1.0;

        double highestPrev = recent.stream().mapToDouble(OHLCV::getHigh).max().orElse(last.getHigh());
        double lowestPrev = recent.stream().mapToDouble(OHLCV::getLow).min().orElse(last.getLow());
        double breakoutBuffer = Math.max(atr * 0.10, last.getClose() * 0.0008);

        boolean closeNearHigh = (last.getHigh() - last.getClose()) <= range * 0.20;
        boolean closeNearLow = (last.getClose() - last.getLow()) <= range * 0.20;

        boolean prevBarsUp = recent.get(recent.size() - 1).getClose() >= recent.get(recent.size() - 2).getClose()
                || recent.get(recent.size() - 2).getClose() >= recent.get(recent.size() - 3).getClose();
        boolean prevBarsDown = recent.get(recent.size() - 1).getClose() <= recent.get(recent.size() - 2).getClose()
                || recent.get(recent.size() - 2).getClose() <= recent.get(recent.size() - 3).getClose();
        double sessionOpen = sessionBars.get(0).getOpen();
        double moveFromOpen = Math.abs(last.getClose() - sessionOpen) / Math.max(0.01, atr);

        boolean expansion = rangeRatio >= 1.6 && bodyPct >= 0.60;
        boolean volumeBurst = volRatio >= 1.8;

        boolean longImpulse = expansion
                && volumeBurst
                && last.getClose() > last.getOpen()
                && closeNearHigh
                && last.getClose() > highestPrev + breakoutBuffer
                && prevBarsUp;

        boolean shortImpulse = expansion
                && volumeBurst
                && last.getClose() < last.getOpen()
                && closeNearLow
                && last.getClose() < lowestPrev - breakoutBuffer
                && prevBarsDown;

        if (!longImpulse && !shortImpulse) return result;

        boolean isLong = longImpulse;
        double entry = r4(last.getClose());
        double stop = isLong
                ? r4(Math.min(last.getLow() - atr * 0.08, entry - Math.max(range * 0.45, atr * 0.28)))
                : r4(Math.max(last.getHigh() + atr * 0.08, entry + Math.max(range * 0.45, atr * 0.28)));
        double risk = Math.abs(entry - stop);
        if (risk <= 0) return result;

        // Scalp target: bank the first momentum leg quickly rather than asking
        // for a full intraday move.
        double tp = isLong ? r4(entry + risk * 0.85) : r4(entry - risk * 0.85);

        int confidence = 68;
        if (volRatio >= 2.2) confidence += 6;
        if (rangeRatio >= 2.0) confidence += 6;
        if ((isLong && last.getClose() > highestPrev + atr * 0.25)
                || (!isLong && last.getClose() < lowestPrev - atr * 0.25)) confidence += 5;
        if (dailyAtr > 0 && range >= dailyAtr * 0.12) confidence += 4;
        if (moveFromOpen >= 1.0) confidence += 3;
        confidence = Math.min(90, confidence);

        ZonedDateTime signalTs = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET);
        String factors = String.format("vol x%.1f | range x%.1f | body %.0f%% | open move %.1f ATR",
                volRatio, rangeRatio, bodyPct * 100.0, moveFromOpen);

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
                .fvgTop(r4(highestPrev))
                .fvgBottom(r4(lowestPrev))
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

    private double r4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
