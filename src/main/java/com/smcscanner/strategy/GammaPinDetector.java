package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Gamma Pin strategy: round-number strike magnetism.
 *
 * Options market makers delta-hedge toward large gamma walls at round-number strikes.
 * For AAPL: every $5 increment is a major gamma level ($195, $200, $205, ...).
 * On options expiration days (especially Fridays), this pinning effect is strongest.
 *
 * Strategy: when price is within 1% of a $5 strike and trending toward it with
 * momentum, trade the continuation toward the pin target.
 */
@Service
public class GammaPinDetector {

    private static final ZoneId  ET               = ZoneId.of("America/New_York");
    private static final double  STRIKE_INCREMENT = 5.0;   // AAPL major strikes at $5 increments
    private static final double  MAX_DIST_PCT     = 0.015; // must be within 1.5% of a strike
    private static final double  MIN_DIST_PCT     = 0.001; // not already at the strike (< 0.1%)

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 15) return result;

        // Filter to regular NYSE session only
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(Long.parseLong(lastRaw.getTimestamp()))
                .atZone(ET).toLocalDate();
        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);

        List<OHLCV> sb = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(Long.parseLong(bar.getTimestamp())).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                sb.add(bar);
            }
        }
        if (sb.size() < 10) return result;

        OHLCV last   = sb.get(sb.size() - 1);
        double close = last.getClose();

        double atr5m = computeAtr(sb);
        double atr   = Math.max(atr5m, close * 0.001);
        double avgVol = sb.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        // Find nearest $5 strike
        double nearestStrike = Math.round(close / STRIKE_INCREMENT) * STRIKE_INCREMENT;
        double distPct       = Math.abs(close - nearestStrike) / close;

        if (distPct > MAX_DIST_PCT) return result; // too far
        if (distPct < MIN_DIST_PCT) return result; // already pinned — no momentum left

        boolean isFriday = today.getDayOfWeek() == DayOfWeek.FRIDAY;
        // Wednesday also active due to Wed/Fri weekly expiry cycles
        boolean isWednesday = today.getDayOfWeek() == DayOfWeek.WEDNESDAY;

        // Momentum: last 3 bars trending toward the strike
        int n = sb.size();
        boolean trendingUp   = false;
        boolean trendingDown = false;
        if (n >= 4) {
            double mom = close - sb.get(n - 4).getClose();
            trendingUp   = mom >  atr * 0.08;
            trendingDown = mom < -atr * 0.08;
        }

        boolean longSignal  = close < nearestStrike && trendingUp;   // below strike, moving toward pin
        boolean shortSignal = close > nearestStrike && trendingDown; // above strike, moving toward pin

        boolean volOk = last.getVolume() > avgVol * 1.0;

        if (longSignal && volOk) {
            double entry = r4(close);
            double sl    = r4(entry - atr * 0.5);
            double risk  = entry - sl;
            // TP = just below the pin strike (target the magnetism, not through it)
            double tp = r4(nearestStrike - atr * 0.1);
            if (tp < r4(entry + risk * 1.5)) tp = r4(entry + risk * 1.5);

            int conf = 65;
            if (isFriday)                           conf += 10;
            else if (isWednesday)                   conf += 5;
            if (distPct < 0.005)                    conf += 5;  // very close = stronger pull
            if (last.getVolume() > avgVol * 1.5)   conf += 5;

            if (sl < entry && tp > entry) {
                result.add(TradeSetup.builder()
                        .ticker(ticker).direction("long")
                        .entry(entry).stopLoss(sl).takeProfit(tp)
                        .confidence(conf).session("NYSE").volatility("gammapin")
                        .atr(atr).hasBos(false).hasChoch(false)
                        .fvgTop(r4(nearestStrike)).fvgBottom(r4(nearestStrike - STRIKE_INCREMENT))
                        .timestamp(LocalDateTime.now()).build());
            }
        } else if (shortSignal && volOk) {
            double entry = r4(close);
            double sl    = r4(entry + atr * 0.5);
            double risk  = sl - entry;
            // TP = just above the pin strike
            double tp = r4(nearestStrike + atr * 0.1);
            if (tp > r4(entry - risk * 1.5)) tp = r4(entry - risk * 1.5);

            int conf = 65;
            if (isFriday)                           conf += 10;
            else if (isWednesday)                   conf += 5;
            if (distPct < 0.005)                    conf += 5;
            if (last.getVolume() > avgVol * 1.5)   conf += 5;

            if (sl > entry && tp < entry) {
                result.add(TradeSetup.builder()
                        .ticker(ticker).direction("short")
                        .entry(entry).stopLoss(sl).takeProfit(tp)
                        .confidence(conf).session("NYSE").volatility("gammapin")
                        .atr(atr).hasBos(false).hasChoch(false)
                        .fvgTop(r4(nearestStrike + STRIKE_INCREMENT)).fvgBottom(r4(nearestStrike))
                        .timestamp(LocalDateTime.now()).build());
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
