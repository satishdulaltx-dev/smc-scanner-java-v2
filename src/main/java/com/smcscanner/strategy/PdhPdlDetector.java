package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
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

/**
 * Previous Day High / Previous Day Low (PDH/PDL) detector.
 *
 * Four patterns:
 *   1. PDH rejection (short): wick above PDH, close back below — institutions defending the level
 *   2. PDL rejection (long):  wick below PDL, close back above — institutions defending the level
 *   3. PDH breakout retest (long):  broke above PDH earlier today, first pullback to test PDH from above
 *   4. PDL breakout retest (short): broke below PDL earlier today, first pullback to test PDL from below
 *
 * Entry = close of the signal bar
 * SL    = wick extreme + 0.15× ATR buffer (rejection) or bar extreme + 0.15× ATR (retest)
 * TP    = 2:1 R from entry
 *
 * Level tolerance: 0.2% (PDH/PDL are exact levels, not fuzzy zones)
 */
@Service
public class PdhPdlDetector {
    private static final Logger log = LoggerFactory.getLogger(PdhPdlDetector.class);
    private static final ZoneId ET        = ZoneId.of("America/New_York");
    private static final double LEVEL_TOL = 0.002; // 0.2%
    private static final double SL_BUFFER = 0.15;  // ATR buffer beyond wick for SL

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 30) return result;

        OHLCV lastBar = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastBar.getTimestamp()).atZone(ET).toLocalDate();

        if (!backtestMode) {
            if (!today.equals(LocalDate.now(ET))) return result;
            LocalTime lastTime = Instant.ofEpochMilli(lastBar.getTimestamp()).atZone(ET).toLocalTime();
            if (lastTime.isBefore(LocalTime.of(9, 45)) || !lastTime.isBefore(LocalTime.of(15, 30))) return result;
        }

        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);

        // Partition bars into today's session and the most recent prior session
        List<OHLCV> todayBars   = new ArrayList<>();
        LocalDate   prevDate    = null;

        for (OHLCV bar : bars) {
            ZonedDateTime zdt   = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            LocalDate     bDate = zdt.toLocalDate();
            LocalTime     bTime = zdt.toLocalTime();
            if (bTime.isBefore(mktOpen) || !bTime.isBefore(mktClose)) continue;
            if (bDate.equals(today)) {
                todayBars.add(bar);
            } else if (bDate.isBefore(today)) {
                if (prevDate == null || bDate.isAfter(prevDate)) prevDate = bDate;
            }
        }

        if (prevDate == null || todayBars.size() < 2) return result;

        // Collect previous session bars
        List<OHLCV> prevDayBars = new ArrayList<>();
        LocalDate   fpd = prevDate;
        for (OHLCV bar : bars) {
            ZonedDateTime zdt   = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            LocalDate     bDate = zdt.toLocalDate();
            LocalTime     bTime = zdt.toLocalTime();
            if (bDate.equals(fpd) && !bTime.isBefore(mktOpen) && bTime.isBefore(mktClose))
                prevDayBars.add(bar);
        }
        if (prevDayBars.isEmpty()) return result;

        double pdh = prevDayBars.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
        double pdl = prevDayBars.stream().mapToDouble(OHLCV::getLow).min().orElse(Double.MAX_VALUE);
        if (pdh <= 0 || pdl >= Double.MAX_VALUE || pdh <= pdl) return result;

        double atr    = computeAtr(bars);
        double curAtr = Math.max(atr, lastBar.getClose() * 0.001);
        double avgVol = bars.stream().skip(Math.max(0, bars.size() - 30))
                           .mapToDouble(OHLCV::getVolume).average().orElse(1);

        OHLCV last = todayBars.get(todayBars.size() - 1);

        // ── Pattern 1: PDH rejection (short) ────────────────────────────────
        if (last.getHigh() > pdh && last.getClose() < pdh) {
            double entry = r4(last.getClose());
            double sl    = r4(last.getHigh() + curAtr * SL_BUFFER);
            double risk  = sl - entry;
            if (risk > 0 && risk <= curAtr * 2.5) {
                double tp   = r4(entry - risk * 2.0);
                int    conf = baseConf(last, avgVol);
                if (pdh - last.getClose() > curAtr * 0.3) conf += 5; // strong close below = cleaner rejection
                String factors = String.format(
                        "pdhpdl-rejection-short | PDH=%.2f | wick=%.2f | close=%.2f | vol=%.1f×avg",
                        pdh, last.getHigh(), last.getClose(), last.getVolume() / Math.max(avgVol, 1));
                log.debug("{} PDH_REJECTION SHORT: {}", ticker, factors);
                result.add(build(ticker, "short", entry, sl, tp, conf, curAtr, last, factors));
            }
        }

        // ── Pattern 2: PDL rejection (long) ─────────────────────────────────
        if (last.getLow() < pdl && last.getClose() > pdl) {
            double entry = r4(last.getClose());
            double sl    = r4(last.getLow() - curAtr * SL_BUFFER);
            double risk  = entry - sl;
            if (risk > 0 && risk <= curAtr * 2.5) {
                double tp   = r4(entry + risk * 2.0);
                int    conf = baseConf(last, avgVol);
                if (last.getClose() - pdl > curAtr * 0.3) conf += 5;
                String factors = String.format(
                        "pdhpdl-rejection-long | PDL=%.2f | wick=%.2f | close=%.2f | vol=%.1f×avg",
                        pdl, last.getLow(), last.getClose(), last.getVolume() / Math.max(avgVol, 1));
                log.debug("{} PDL_REJECTION LONG: {}", ticker, factors);
                result.add(build(ticker, "long", entry, sl, tp, conf, curAtr, last, factors));
            }
        }

        // ── Pattern 3: PDH breakout retest (long) ────────────────────────────
        // Price broke above PDH earlier today, now dipping back to test PDH from above
        if (todayBars.size() >= 3) {
            boolean prevBrokeAbovePdh = false;
            for (int i = 0; i < todayBars.size() - 1; i++) {
                if (todayBars.get(i).getClose() > pdh * (1 + LEVEL_TOL)) {
                    prevBrokeAbovePdh = true;
                    break;
                }
            }
            boolean touchedPdh = last.getLow() <= pdh * (1 + LEVEL_TOL * 2);
            boolean closedAbove = last.getClose() > pdh;
            boolean nearPdh     = Math.abs(last.getClose() - pdh) / pdh < LEVEL_TOL * 2;
            if (prevBrokeAbovePdh && touchedPdh && closedAbove && nearPdh) {
                double entry = r4(last.getClose());
                double sl    = r4(last.getLow() - curAtr * SL_BUFFER);
                double risk  = entry - sl;
                if (risk > 0 && risk <= curAtr * 2.5) {
                    double tp   = r4(entry + risk * 2.0);
                    int    conf = baseConf(last, avgVol) + 5; // breakout retest = higher quality
                    String factors = String.format(
                            "pdhpdl-breakout-retest-long | PDH=%.2f | close=%.2f | vol=%.1f×avg",
                            pdh, last.getClose(), last.getVolume() / Math.max(avgVol, 1));
                    log.debug("{} PDH_BREAKOUT_RETEST LONG: {}", ticker, factors);
                    result.add(build(ticker, "long", entry, sl, tp, conf, curAtr, last, factors));
                }
            }
        }

        // ── Pattern 4: PDL breakout retest (short) ───────────────────────────
        // Price broke below PDL earlier today, now bouncing up to test PDL from below
        if (todayBars.size() >= 3) {
            boolean prevBrokeBelowPdl = false;
            for (int i = 0; i < todayBars.size() - 1; i++) {
                if (todayBars.get(i).getClose() < pdl * (1 - LEVEL_TOL)) {
                    prevBrokeBelowPdl = true;
                    break;
                }
            }
            boolean touchedPdl = last.getHigh() >= pdl * (1 - LEVEL_TOL * 2);
            boolean closedBelow = last.getClose() < pdl;
            boolean nearPdl     = Math.abs(last.getClose() - pdl) / pdl < LEVEL_TOL * 2;
            if (prevBrokeBelowPdl && touchedPdl && closedBelow && nearPdl) {
                double entry = r4(last.getClose());
                double sl    = r4(last.getHigh() + curAtr * SL_BUFFER);
                double risk  = sl - entry;
                if (risk > 0 && risk <= curAtr * 2.5) {
                    double tp   = r4(entry - risk * 2.0);
                    int    conf = baseConf(last, avgVol) + 5;
                    String factors = String.format(
                            "pdhpdl-breakout-retest-short | PDL=%.2f | close=%.2f | vol=%.1f×avg",
                            pdl, last.getClose(), last.getVolume() / Math.max(avgVol, 1));
                    log.debug("{} PDL_BREAKOUT_RETEST SHORT: {}", ticker, factors);
                    result.add(build(ticker, "short", entry, sl, tp, conf, curAtr, last, factors));
                }
            }
        }

        if (result.size() > 1) {
            result.sort((x, y) -> Integer.compare(y.getConfidence(), x.getConfidence()));
            return List.of(result.get(0));
        }
        return result;
    }

    private int baseConf(OHLCV bar, double avgVol) {
        int c = 68;
        if (bar.getVolume() > avgVol * 2.0)      c += 10;
        else if (bar.getVolume() > avgVol * 1.5) c += 6;
        return c;
    }

    private TradeSetup build(String ticker, String dir, double entry, double sl, double tp,
                              int conf, double atr, OHLCV bar, String factors) {
        return TradeSetup.builder()
                .ticker(ticker).direction(dir).entry(entry).stopLoss(sl).takeProfit(tp)
                .confidence(conf).session("NYSE").volatility("scalp").atr(atr)
                .hasBos(false).hasChoch(false).fvgTop(0).fvgBottom(0)
                .factorBreakdown(factors)
                .timestamp(Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET).toLocalDateTime())
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
