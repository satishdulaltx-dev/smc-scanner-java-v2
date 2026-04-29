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
 * VWAP Mean-Reversion strategy detector for large-cap stable stocks.
 * Looks for price that has deviated significantly from session VWAP and
 * is beginning to revert back toward it.
 */
@Service
public class VwapStrategyDetector {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * Detect VWAP mean-reversion setups for the given bars.
     *
     * @param bars      5-minute OHLCV bars (100+ bars typical)
     * @param ticker    ticker symbol
     * @param dailyAtr  daily ATR from calling service
     * @return list of detected setups (0 or 1 element)
     */
    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, ticker, dailyAtr, false, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode) {
        return detect(bars, ticker, dailyAtr, backtestMode, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode, boolean longOnly) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.isEmpty()) return result;

        // Filter to regular NYSE session only: same date AND 9:30 AM–4:00 PM ET
        // This excludes pre-market and after-hours bars which skew VWAP calculations
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp())
                .atZone(ET).toLocalDate();
        // Staleness guard skipped in backtest — the last bar date is historical by design
        if (!backtestMode && !today.equals(LocalDate.now(ET))) return result;
        LocalTime mktOpen  = LocalTime.of(9, 30);
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

        if (sessionBars.size() < 12) return result;

        // Early-session gate: VWAP is statistically unreliable during the first 30 minutes
        // of price discovery (9:30–10:00 ET). The volume-weighted mean is still forming and
        // any Z-score deviation is noise, not signal. Hard block before 10:00 ET.
        LocalTime lastBarTime = Instant.ofEpochMilli(sessionBars.get(sessionBars.size() - 1).getTimestamp())
                .atZone(ET).toLocalTime();
        if (lastBarTime.isBefore(LocalTime.of(10, 0))) return result;

        // Compute rolling session VWAP: sum(typical_price * volume) / sum(volume)
        double sumTpVol = 0.0;
        double sumVol   = 0.0;
        for (OHLCV bar : sessionBars) {
            double tp = (bar.getHigh() + bar.getLow() + bar.getClose()) / 3.0;
            sumTpVol += tp * bar.getVolume();
            sumVol   += bar.getVolume();
        }
        double vwap = sumVol > 0 ? sumTpVol / sumVol : sessionBars.get(sessionBars.size() - 1).getClose();

        // ── Z-Score: only trade at the extremes (> 2.0 SD from VWAP) ────────
        // AAPL/MSFT spend 70% of the day within 1 SD of VWAP. Entering near the
        // mean is a coin flip. The edge is mean-reversion from the tails only.
        double sumSqDev = 0.0;
        for (OHLCV bar : sessionBars) {
            double dev = bar.getClose() - vwap;
            sumSqDev += dev * dev;
        }
        double stdDev = Math.sqrt(sumSqDev / sessionBars.size());
        double lastClose = sessionBars.get(sessionBars.size() - 1).getClose();
        double zScore = stdDev > 0 ? (lastClose - vwap) / stdDev : 0.0;

        // Compute 14-bar ATR from all (non-session-filtered) bars
        double atr    = computeAtr(bars);
        double curAtr = Math.max(atr, lastClose * 0.002);

        // Determine target ATR for TP sizing
        double targetAtr = (dailyAtr > curAtr * 2) ? dailyAtr : curAtr * 4;

        // Average volume of session bars
        double avgVol = sessionBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        // ── RVOL gate ────────────────────────────────────────────────────────
        // Relative volume = current bar volume / session average.
        // < 0.8 on a mature session (≥ 20 bars) means very thin participation —
        // Z-score deviations on thin volume are noise, not signals. Hard gate.
        OHLCV last = sessionBars.get(sessionBars.size() - 1);
        double sessionRvol = avgVol > 0 ? last.getVolume() / avgVol : 1.0;
        if (sessionBars.size() >= 20 && sessionRvol < 0.8) return result;

        // ── Z-Score velocity filter ─────────────────────────────────────────
        // Compute Z-Score of previous bar to detect if deviation is still expanding.
        // Only enter when Z-Score has peaked and is reverting (velocity turning).
        double prevZScore = 0.0;
        if (sessionBars.size() >= 2) {
            double prevClose = sessionBars.get(sessionBars.size() - 2).getClose();
            prevZScore = stdDev > 0 ? (prevClose - vwap) / stdDev : 0.0;
        }
        boolean zScoreReverting = Math.abs(zScore) < Math.abs(prevZScore);

        // ── Macro trend filter ────────────────────────────────────────────────
        // VWAP reversion LONGs in a clearly bearish trending day have very low
        // win rates — price dips below VWAP, bounces slightly, then continues down.
        // VWAP reversion SHORTs in a clearly bullish trending day have the same problem.
        //
        // Rules:
        //   1. Compute day change % from first session bar open → current close
        //   2. Compute SMA20 direction (declining or rising)
        //   3. trendBearish  = day < -0.8% AND SMA20 declining
        //      trendBullish  = day >  0.8% AND SMA20 rising
        //   4. Hard block LONGs when day < -1.5% (strongly trending down)
        //      Hard block SHORTs when day >  1.5% (strongly trending up)
        //   5. Soft penalty (-15 conf) for moderate bearish/bullish trend vs LONG/SHORT

        double dayOpen      = sessionBars.get(0).getOpen();
        double dayChangePct = dayOpen > 0 ? (lastClose - dayOpen) / dayOpen * 100.0 : 0.0;

        // SMA20 of session closes — only reliable with 20+ bars
        boolean smaReliable = sessionBars.size() >= 20;
        int smaPeriod = Math.min(20, sessionBars.size());
        double sma20 = 0.0;
        for (int i = sessionBars.size() - smaPeriod; i < sessionBars.size(); i++) {
            sma20 += sessionBars.get(i).getClose();
        }
        sma20 /= smaPeriod;

        // SMA20 direction: compare to SMA20 from 5 bars ago
        double sma20Earlier = 0.0;
        boolean hasSmaEarlier = smaReliable && sessionBars.size() >= smaPeriod + 5;
        if (hasSmaEarlier) {
            for (int i = sessionBars.size() - smaPeriod - 5; i < sessionBars.size() - 5; i++) {
                sma20Earlier += sessionBars.get(i).getClose();
            }
            sma20Earlier /= smaPeriod;
        }
        boolean smaDeclining = hasSmaEarlier && sma20 < sma20Earlier;
        boolean smaRising    = hasSmaEarlier && sma20 > sma20Earlier;

        // VWAP slope gate: if the intraday SMA is trending with enough velocity, the stock
        // is in a directional move — mean-reversion against it has very low win rate.
        // Slope = % change in SMA20 over the last 5 bars (25 minutes).
        // > +0.20% slope → clearly bullish intraday trend → block SHORT reversion entries.
        // < -0.20% slope → clearly bearish intraday trend → block LONG reversion entries.
        // Threshold chosen so AAPL drifts (+0.05%) don't trigger; TSLA trending (+0.30%) does.
        double smaSlopePct = (hasSmaEarlier && sma20Earlier > 0)
                ? (sma20 - sma20Earlier) / sma20Earlier * 100.0 : 0.0;
        boolean steepBullishSlope = smaSlopePct >  0.20;
        boolean steepBearishSlope = smaSlopePct < -0.20;

        // Composite trend flags
        boolean trendBearish         = dayChangePct < -0.8  && smaDeclining;
        boolean trendStronglyBearish = dayChangePct < -1.5;
        boolean trendBullish         = dayChangePct >  0.8  && smaRising;
        boolean trendStronglyBullish = dayChangePct >  1.5;

        // ── LONG setup ────────────────────────────────────────────────────────
        // Look back 20 bars for lowest close (wider window catches more setups)
        int lookback = Math.min(20, sessionBars.size());
        int startIdx = sessionBars.size() - lookback;
        double lowestClose  = Double.MAX_VALUE;
        double highestClose = -Double.MAX_VALUE;
        for (int i = startIdx; i < sessionBars.size(); i++) {
            double c = sessionBars.get(i).getClose();
            if (c < lowestClose)  lowestClose  = c;
            if (c > highestClose) highestClose = c;
        }

        // LONG: price must have dipped meaningfully below VWAP (Z-Score < -1.2)
        // Lowered from 1.5→1.2: 1.5 SD was starving AMZN/SPY (0 signals in 180d).
        // 1.2 SD catches more "rubber band snap" setups while still filtering noise.
        //
        // Hard block: trendStronglyBearish OR steep bearish slope only blocks LONG.
        // SHORT evaluation continues below regardless.
        if (!trendStronglyBearish && !steepBearishSlope && lowestClose < vwap - 0.5 * curAtr && zScore < -1.2) {
            // ── 2-bar confirmation window ────────────────────────────────────
            // Check last 2 bars, not just the final one. If the real reversal
            // started 1 bar earlier (bullish bar + volume spike), we pick it up
            // on the next bar as confirmation rather than missing it entirely.
            OHLCV confirmBar = last;
            if (sessionBars.size() >= 2) {
                OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                // If previous bar was the reversal candle (bullish + volume), use it
                // Current bar just needs to hold above the low (confirmation)
                if (prev.getClose() > prev.getOpen() && prev.getVolume() > avgVol * 1.1
                        && last.getClose() >= prev.getClose() * 0.998) {
                    confirmBar = prev;
                }
            }

            double curClose = last.getClose();
            boolean belowVwap      = curClose < vwap;
            boolean bouncingUp     = curClose > lowestClose + curAtr * 0.2;
            boolean bullishBar     = confirmBar.getClose() > confirmBar.getOpen();
            boolean volSpike       = confirmBar.getVolume() > avgVol * 1.1;
            boolean notFreeFall    = curClose >= vwap - curAtr * 3.0;

            if (belowVwap && bouncingUp && bullishBar && volSpike && notFreeFall && zScoreReverting) {
                double entry = r4(curClose);
                // Anchor SL at nearest structural swing low below entry; fall back to ATR-based
                double atrFallback = Math.min(r4(lowestClose - curAtr * 0.3), r4(entry - targetAtr * 0.35));
                atrFallback = Math.min(atrFallback, r4(entry - curAtr * 0.1));
                double sl = r4(SwingLevelFinder.swingLowSl(sessionBars, entry, curAtr, 20, atrFallback));

                double tpRaw = r4(vwap + curAtr * 0.3);
                double tp    = tpRaw > entry * 1.005 ? tpRaw : r4(entry + targetAtr * 0.9);

                int confidence = 72;
                if (last.getVolume() > avgVol * 1.8)             confidence += 5;
                if ((vwap - curClose) > 0.8 * curAtr)            confidence += 5;
                if (last.getVolume() > avgVol * 2.5)             confidence += 5;
                if (zScore < -2.0)                               confidence += 5;  // extreme Z-Score = high-probability reversion
                if (sessionRvol >= 1.5)                          confidence += 5;  // RVOL-confirmed Z-spike
                // ── Soft trend penalty ─────────────────────────────────────
                // Moderate bearish day (-0.8% to -1.5%) with declining SMA:
                // lower confidence by 15 so it needs 80+ to survive filters.
                if (trendBearish) confidence -= 15;

                // Enforce 2:1 R:R minimum — raises TP if VWAP is too close
                double longRisk = entry - sl;
                double longMin2R = r4(entry + longRisk * 2.0);
                if (tp < longMin2R) tp = longMin2R;

                if (sl < entry && tp > entry) {
                    String trend = trendBearish ? "BEARISH(penalized)" : trendStronglyBearish ? "STRONGLY-BEARISH(blocked)" : "neutral";
                    String factors = String.format(
                            "vwap-reversion-long | VWAP=$%.2f | Z=%.2f | below by %.1f×ATR" +
                            " | vol=%.1f×avg | RVOL=%.1f | trend=%s | reverting=%s",
                            vwap, zScore, (vwap - lastClose) / Math.max(curAtr, 0.001),
                            last.getVolume() / Math.max(avgVol, 1), sessionRvol,
                            trend, zScoreReverting ? "✓" : "✗");
                    result.add(TradeSetup.builder()
                            .ticker(ticker)
                            .direction("long")
                            .entry(entry)
                            .stopLoss(sl)
                            .takeProfit(tp)
                            .confidence(confidence)
                            .session("NYSE")
                            .volatility("normal")
                            .atr(curAtr)
                            .hasBos(false)
                            .hasChoch(false)
                            .fvgTop(r4(vwap))
                            .fvgBottom(r4(lowestClose))
                            .factorBreakdown(factors)
                            .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                            .build());
                    // Don't return here — continue to evaluate SHORT side.
                    // If both fire, pick the higher-confidence one at the end.
                }
            }
        }

        // ── SHORT setup ───────────────────────────────────────────────────────
        // Price must have spiked meaningfully above VWAP (Z-Score > 1.2)
        //
        // Hard block: trendStronglyBullish OR steep bullish slope only blocks SHORT.
        // LONG evaluation above runs independently.
        // vwapLongOnly: momentum tickers (TSLA) where backtest shows 100% short loss rate.
        if (longOnly) return result;
        if (!trendStronglyBullish && !steepBullishSlope && highestClose > vwap + 0.5 * curAtr && zScore > 1.2) {
            // ── 2-bar confirmation window (SHORT side) ───────────────────────
            OHLCV confirmBarShort = last;
            if (sessionBars.size() >= 2) {
                OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                if (prev.getClose() < prev.getOpen() && prev.getVolume() > avgVol * 1.1
                        && last.getClose() <= prev.getClose() * 1.002) {
                    confirmBarShort = prev;
                }
            }

            double curClose = last.getClose();
            boolean aboveVwap     = curClose > vwap;
            boolean revertingDown  = curClose < highestClose - curAtr * 0.2;
            boolean bearishBar    = confirmBarShort.getClose() < confirmBarShort.getOpen();
            boolean volSpike      = confirmBarShort.getVolume() > avgVol * 1.1;
            boolean notFreeRocket = curClose <= vwap + curAtr * 3.0;

            if (aboveVwap && revertingDown && bearishBar && volSpike && notFreeRocket && zScoreReverting) {
                double entry = r4(curClose);
                // Anchor SL at nearest structural swing high above entry; fall back to ATR-based
                double atrFallback = Math.max(r4(highestClose + curAtr * 0.3), r4(entry + targetAtr * 0.35));
                atrFallback = Math.max(atrFallback, r4(entry + curAtr * 0.1));
                double sl = r4(SwingLevelFinder.swingHighSl(sessionBars, entry, curAtr, 20, atrFallback));

                double tpRaw = r4(vwap - curAtr * 0.3);
                double tp    = tpRaw < entry * 0.995 ? tpRaw : r4(entry - targetAtr * 0.9);

                int confidence = 72;
                if (last.getVolume() > avgVol * 1.8)             confidence += 5;
                if ((curClose - vwap) > 0.8 * curAtr)            confidence += 5;
                if (last.getVolume() > avgVol * 2.5)             confidence += 5;
                if (zScore > 2.0)                                confidence += 5;  // extreme Z-Score = high-probability reversion
                if (sessionRvol >= 1.5)                          confidence += 5;  // RVOL-confirmed Z-spike
                // Soft trend penalty for moderate counter-trend setup
                if (trendBullish) confidence -= 15;

                // Enforce 2:1 R:R minimum
                double shortRisk = sl - entry;
                double shortMin2R = r4(entry - shortRisk * 2.0);
                if (tp > shortMin2R) tp = shortMin2R;

                if (sl > entry && tp < entry) {
                    String trend = trendBullish ? "BULLISH(penalized)" : trendStronglyBullish ? "STRONGLY-BULLISH(blocked)" : "neutral";
                    String factors = String.format(
                            "vwap-reversion-short | VWAP=$%.2f | Z=%.2f | above by %.1f×ATR" +
                            " | vol=%.1f×avg | RVOL=%.1f | trend=%s | reverting=%s",
                            vwap, zScore, (curClose - vwap) / Math.max(curAtr, 0.001),
                            last.getVolume() / Math.max(avgVol, 1), sessionRvol,
                            trend, zScoreReverting ? "✓" : "✗");
                    result.add(TradeSetup.builder()
                            .ticker(ticker)
                            .direction("short")
                            .entry(entry)
                            .stopLoss(sl)
                            .takeProfit(tp)
                            .confidence(confidence)
                            .session("NYSE")
                            .volatility("normal")
                            .atr(curAtr)
                            .hasBos(false)
                            .hasChoch(false)
                            .fvgTop(r4(highestClose))
                            .fvgBottom(r4(vwap))
                            .factorBreakdown(factors)
                            .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                            .build());
                }
            }
        }

        // ── VWAP continuation pullback ────────────────────────────────────────
        // On a trending day (60%+ of session bars closed on the trend side of VWAP),
        // the first pullback back TO VWAP is a with-trend entry, not a mean-reversion.
        // This fires only when the reversion logic did NOT already fire (avoids conflict).
        if (result.isEmpty() && sessionBars.size() >= 15) {
            long aboveVwapBars = sessionBars.stream().filter(b -> b.getClose() > vwap).count();
            double abovePct = (double) aboveVwapBars / sessionBars.size();
            long belowVwapBars = sessionBars.size() - aboveVwapBars;
            double belowPct = (double) belowVwapBars / sessionBars.size();

            OHLCV contBar = last;
            // 2-bar window: if previous bar was the actual pullback touch, current bar confirms
            if (sessionBars.size() >= 2) {
                OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                if (prev.getVolume() > avgVol * 1.1) {
                    if (abovePct >= 0.60 && prev.getClose() > prev.getOpen()
                            && last.getClose() >= prev.getClose() * 0.998) contBar = prev;
                    if (belowPct >= 0.60 && prev.getClose() < prev.getOpen()
                            && last.getClose() <= prev.getClose() * 1.002) contBar = prev;
                }
            }

            boolean volOk = contBar.getVolume() > avgVol * 1.0;

            // Continuation LONG: stock trending above VWAP all day, first dip to VWAP
            if (abovePct >= 0.60 && !trendStronglyBearish) {
                boolean atVwap      = Math.abs(lastClose - vwap) <= curAtr * 0.5;
                boolean bullishBar2 = contBar.getClose() > contBar.getOpen();
                boolean notBelow    = lastClose >= vwap - curAtr * 0.3;
                if (atVwap && bullishBar2 && volOk && notBelow && zScore > -1.0) {
                    double entry = r4(lastClose);
                    double atrFb = r4(entry - curAtr * 0.5);
                    double sl    = r4(SwingLevelFinder.swingLowSl(sessionBars, entry, curAtr, 15, atrFb));
                    double risk  = entry - sl;
                    if (risk > 0) {
                        double tp   = r4(entry + risk * 2.5);
                        int    conf = 73;
                        if (abovePct >= 0.75)                conf += 5;
                        if (last.getVolume() > avgVol * 1.5) conf += 5;
                        if (trendBearish)                    conf -= 10;
                        if (sl < entry && tp > entry) {
                            String factors = String.format(
                                    "vwap-continuation-long | VWAP=$%.2f | above=%.0f%% | Z=%.2f | vol=%.1f×avg",
                                    vwap, abovePct * 100, zScore, last.getVolume() / Math.max(avgVol, 1));
                            result.add(TradeSetup.builder()
                                    .ticker(ticker).direction("long").entry(entry).stopLoss(sl).takeProfit(tp)
                                    .confidence(conf).session("NYSE").volatility("normal").atr(curAtr)
                                    .hasBos(false).hasChoch(false).fvgTop(r4(vwap)).fvgBottom(r4(sl))
                                    .factorBreakdown(factors)
                                    .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                                    .build());
                        }
                    }
                }
            }

            // Continuation SHORT: stock trending below VWAP all day, first bounce to VWAP
            if (belowPct >= 0.60 && !trendStronglyBullish && result.isEmpty()) {
                boolean atVwap      = Math.abs(lastClose - vwap) <= curAtr * 0.5;
                boolean bearishBar2 = contBar.getClose() < contBar.getOpen();
                boolean notAbove    = lastClose <= vwap + curAtr * 0.3;
                if (atVwap && bearishBar2 && volOk && notAbove && zScore < 1.0) {
                    double entry = r4(lastClose);
                    double atrFb = r4(entry + curAtr * 0.5);
                    double sl    = r4(SwingLevelFinder.swingHighSl(sessionBars, entry, curAtr, 15, atrFb));
                    double risk  = sl - entry;
                    if (risk > 0) {
                        double tp   = r4(entry - risk * 2.5);
                        int    conf = 73;
                        if (belowPct >= 0.75)                conf += 5;
                        if (last.getVolume() > avgVol * 1.5) conf += 5;
                        if (trendBullish)                    conf -= 10;
                        if (sl > entry && tp < entry) {
                            String factors = String.format(
                                    "vwap-continuation-short | VWAP=$%.2f | below=%.0f%% | Z=%.2f | vol=%.1f×avg",
                                    vwap, belowPct * 100, zScore, last.getVolume() / Math.max(avgVol, 1));
                            result.add(TradeSetup.builder()
                                    .ticker(ticker).direction("short").entry(entry).stopLoss(sl).takeProfit(tp)
                                    .confidence(conf).session("NYSE").volatility("normal").atr(curAtr)
                                    .hasBos(false).hasChoch(false).fvgTop(r4(sl)).fvgBottom(r4(vwap))
                                    .factorBreakdown(factors)
                                    .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                                    .build());
                        }
                    }
                }
            }
        }

        // If both LONG and SHORT fired (rare — range-bound day), keep only the
        // higher-confidence setup. Prevents sending conflicting signals.
        if (result.size() > 1) {
            result.sort((a, b) -> Integer.compare(b.getConfidence(), a.getConfidence()));
            TradeSetup best = result.get(0);
            result.clear();
            result.add(best);
        }
        return result;
    }

    /** Compute 14-bar simple ATR from the last N bars. */
    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0.0;
        int start = bars.size() - period - 1;
        double sum = 0.0;
        for (int i = start + 1; i < bars.size(); i++) {
            OHLCV cur  = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(cur.getHigh() - cur.getLow(),
                        Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                 Math.abs(cur.getLow()  - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    /** Round to 4 decimal places. */
    private double r4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
