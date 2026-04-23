package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.SwingPoint;
import com.smcscanner.model.SwingType;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.smc.StructureAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Liquidity Sweep-and-Flip detector.
 *
 * The cleanest SMC scalp entry: equal highs or equal lows form over several bars
 * (stop orders cluster there), price wicks through them on one candle, then closes
 * back on the other side. That single candle IS the signal — no displacement or
 * FVG chain required. The wick hunted the stops; the close shows the flip.
 *
 * Entry  = close of the sweep-flip bar
 * SL     = wick extreme + 0.1× ATR buffer
 * TP     = 2:1 R from entry
 *
 * Equal-level tolerance: 0.5% (matching LiquidityAnalyzer.EQUAL_TOL).
 * Only fires on sweeps in the last 3 bars — stale sweeps are noise.
 */
@Service
public class LiquiditySweepFlipDetector {
    private static final Logger log = LoggerFactory.getLogger(LiquiditySweepFlipDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double EQUAL_TOL   = 0.005; // 0.5% — equal highs/lows
    private static final int    MAX_AGE     = 3;     // only fire on sweeps ≤ 3 bars old
    private static final double SL_BUFFER   = 0.10;  // ATR buffer beyond wick for SL

    private final StructureAnalyzer sa;

    public LiquiditySweepFlipDetector(StructureAnalyzer sa) { this.sa = sa; }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 20) return result;

        if (!backtestMode) {
            LocalDate lastDate = Instant.ofEpochMilli(bars.get(bars.size()-1).getTimestamp()).atZone(ET).toLocalDate();
            if (!lastDate.equals(LocalDate.now(ET))) return result;
            LocalTime lastTime = Instant.ofEpochMilli(bars.get(bars.size()-1).getTimestamp()).atZone(ET).toLocalTime();
            if (lastTime.isBefore(LocalTime.of(9,45)) || !lastTime.isBefore(LocalTime.of(15,30))) return result;
        }

        double atr    = computeAtr(bars);
        double curAtr = Math.max(atr, bars.get(bars.size()-1).getClose() * 0.001);
        double avgVol = bars.stream().skip(Math.max(0, bars.size()-30))
                           .mapToDouble(OHLCV::getVolume).average().orElse(1);

        List<SwingPoint> swings = sa.detectSwings(bars, 3);
        List<SwingPoint> highs  = swings.stream().filter(s -> s.getSwingType() == SwingType.HIGH).toList();
        List<SwingPoint> lows   = swings.stream().filter(s -> s.getSwingType() == SwingType.LOW).toList();

        int n = bars.size();

        // ── Bear sweep-flip (short): sweep equal highs, close back below ─────────
        for (int i = 0; i < highs.size() - 1; i++) {
            for (int j = i + 1; j < highs.size(); j++) {
                double a = highs.get(i).getPrice(), b = highs.get(j).getPrice();
                if (Math.abs(a - b) / Math.max(a, b) > EQUAL_TOL) continue;
                double level = (a + b) / 2.0;
                int after = highs.get(j).getIndex() + 1;
                for (int k = after; k < n; k++) {
                    OHLCV bar = bars.get(k);
                    if (bar.getHigh() > level * 1.001 && bar.getClose() < level) {
                        int age = n - 1 - k;
                        if (age > MAX_AGE) break;
                        double entry = r4(bar.getClose());
                        double sl    = r4(bar.getHigh() + curAtr * SL_BUFFER);
                        double risk  = sl - entry;
                        if (risk <= 0 || risk > curAtr * 2.5) break;
                        double tp = r4(entry - risk * 2.0);
                        int conf = baseConf(bar, avgVol, age);
                        String factors = String.format(
                                "sweep-flip-short | EQ_HIGH=%.2f | wick=%.2f | age=%d bars | vol=%.1f×avg",
                                level, bar.getHigh(), age, bar.getVolume() / Math.max(avgVol, 1));
                        log.debug("{} SWEEP_FLIP SHORT: {}", ticker, factors);
                        result.add(build(ticker, "short", entry, sl, tp, conf, curAtr, bar, factors));
                        break;
                    }
                }
            }
        }

        // ── Bull sweep-flip (long): sweep equal lows, close back above ──────────
        for (int i = 0; i < lows.size() - 1; i++) {
            for (int j = i + 1; j < lows.size(); j++) {
                double a = lows.get(i).getPrice(), b = lows.get(j).getPrice();
                if (Math.abs(a - b) / Math.max(a, b) > EQUAL_TOL) continue;
                double level = (a + b) / 2.0;
                int after = lows.get(j).getIndex() + 1;
                for (int k = after; k < n; k++) {
                    OHLCV bar = bars.get(k);
                    if (bar.getLow() < level * 0.999 && bar.getClose() > level) {
                        int age = n - 1 - k;
                        if (age > MAX_AGE) break;
                        double entry = r4(bar.getClose());
                        double sl    = r4(bar.getLow() - curAtr * SL_BUFFER);
                        double risk  = entry - sl;
                        if (risk <= 0 || risk > curAtr * 2.5) break;
                        double tp = r4(entry + risk * 2.0);
                        int conf = baseConf(bar, avgVol, age);
                        String factors = String.format(
                                "sweep-flip-long | EQ_LOW=%.2f | wick=%.2f | age=%d bars | vol=%.1f×avg",
                                level, bar.getLow(), age, bar.getVolume() / Math.max(avgVol, 1));
                        log.debug("{} SWEEP_FLIP LONG: {}", ticker, factors);
                        result.add(build(ticker, "long", entry, sl, tp, conf, curAtr, bar, factors));
                        break;
                    }
                }
            }
        }

        if (result.size() > 1) {
            result.sort((x, y) -> Integer.compare(y.getConfidence(), x.getConfidence()));
            return List.of(result.get(0));
        }
        return result;
    }

    private int baseConf(OHLCV bar, double avgVol, int age) {
        int c = 72;
        if (bar.getVolume() > avgVol * 2.0) c += 10;
        else if (bar.getVolume() > avgVol * 1.5) c += 6;
        if (age == 0) c += 5; // current bar = freshest signal
        return c;
    }

    private TradeSetup build(String ticker, String dir, double entry, double sl, double tp,
                              int conf, double atr, OHLCV bar, String factors) {
        return TradeSetup.builder()
                .ticker(ticker).direction(dir).entry(entry).stopLoss(sl).takeProfit(tp)
                .confidence(conf).session("NYSE").volatility("scalp").atr(atr)
                .hasBos(false).hasChoch(false).fvgTop(0).fvgBottom(0)
                .factorBreakdown(factors)
                .timestamp(Instant.ofEpochMilli(bar.getTimestamp())
                        .atZone(ZoneId.of("America/New_York")).toLocalDateTime())
                .build();
    }

    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0;
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            OHLCV c = bars.get(i), p = bars.get(i - 1);
            sum += Math.max(c.getHigh() - c.getLow(),
                   Math.max(Math.abs(c.getHigh() - p.getClose()), Math.abs(c.getLow() - p.getClose())));
        }
        return sum / period;
    }

    private double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
