package com.smcscanner.strategy;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.VolumeProfileCalculator;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.model.indicator.VolumeProfile;
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
 * Signal layers (all additive to confidence, none are hard blocks except the
 * base Bollinger conditions):
 *
 *  1. Bollinger bounce / squeeze-break  — core signal (unchanged)
 *  2. Volume Profile (VP)               — prefer entries at VPOC / VAH / VAL
 *  3. Order-flow delta                  — buy/sell pressure from last 5 bars
 *  4. Bid/ask imbalance (NBBO)          — live pending-order stacking via Polygon
 *  5. Large-print sweep detection       — institutional tape prints via Polygon trades
 */
@Service
public class ScalpMomentumDetector {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int    BB_PERIOD = 20;
    private static final double BB_MULT   = 2.0;

    private final PolygonClient            polygon;
    private final VolumeProfileCalculator  vpCalc;

    public ScalpMomentumDetector(PolygonClient polygon, VolumeProfileCalculator vpCalc) {
        this.polygon = polygon;
        this.vpCalc  = vpCalc;
    }

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

        int    n     = sessionBars.size();
        OHLCV  prev  = sessionBars.get(n - 2);

        double atr    = Math.max(computeAtr(sessionBars, 8), last.getClose() * 0.0012);
        double effAtr = dailyAtr > atr * 4 ? dailyAtr : atr * 8;
        double sessionVwap = computeSessionVwap(sessionBars, n - 1);
        double avgVol      = averageVolume(sessionBars, n - 7, n - 2);
        double volRatio    = last.getVolume() / Math.max(1.0, avgVol);

        Bands prevBands = computeBands(sessionBars, n - 2);
        Bands lastBands = computeBands(sessionBars, n - 1);
        if (prevBands == null || lastBands == null) return result;

        double lastPctB      = percentB(last.getClose(), lastBands);
        double prevPctB      = percentB(prev.getClose(), prevBands);
        double prevLowPctB   = percentB(prev.getLow(),   prevBands);
        double prevHighPctB  = percentB(prev.getHigh(),  prevBands);
        double bandwidth     = bandWidth(lastBands);
        double prevBandwidth = bandWidth(prevBands);
        double minRecentBandwidth = minRecentBandWidth(sessionBars, n - 10, n - 2);
        boolean squeezeRecently = minRecentBandwidth > 0 && prevBandwidth <= minRecentBandwidth * 1.15;
        boolean widthExpanding  = bandwidth > prevBandwidth * 1.08;

        double  spyReturn    = intradayReturn(spyBars);
        double  tickerReturn = intradayReturn(sessionBars);
        double  rsLead       = tickerReturn - spyReturn;
        boolean spyBull      = spyReturn >  0.0015;
        boolean spyBear      = spyReturn < -0.0015;

        double  lastBodyPct  = bodyPct(last);
        double  prevBodyPct  = bodyPct(prev);
        boolean lastGreen    = last.getClose() > last.getOpen();
        boolean lastRed      = last.getClose() < last.getOpen();
        boolean prevGreen    = prev.getClose() > prev.getOpen();
        boolean prevRed      = prev.getClose() < prev.getOpen();
        boolean closeNearHigh = (last.getHigh() - last.getClose()) <= (last.getHigh() - last.getLow()) * 0.25;
        boolean closeNearLow  = (last.getClose() - last.getLow())  <= (last.getHigh() - last.getLow()) * 0.25;

        // ── Core Bollinger conditions (unchanged) ─────────────────────────────
        boolean bounceLong = prevLowPctB < 0.05
                && prev.getClose() > prevBands.lower
                && lastGreen && prevGreen
                && prevBodyPct >= 0.40 && lastBodyPct >= 0.50
                && closeNearHigh
                && last.getClose() > prev.getHigh()
                && last.getClose() > sessionVwap
                && !spyBear && rsLead > -0.002;

        boolean bounceShort = prevHighPctB > 0.95
                && prev.getClose() < prevBands.upper
                && lastRed && prevRed
                && prevBodyPct >= 0.40 && lastBodyPct >= 0.50
                && closeNearLow
                && last.getClose() < prev.getLow()
                && last.getClose() < sessionVwap
                && !spyBull && rsLead < 0.002;

        boolean breakLong = squeezeRecently && widthExpanding
                && prevPctB > 0.60 && lastPctB > 1.0
                && prevGreen && lastGreen
                && prevBodyPct >= 0.45 && lastBodyPct >= 0.55
                && closeNearHigh
                && volRatio >= 1.25
                && last.getClose() > sessionVwap
                && !spyBear && rsLead >= 0.0;

        boolean breakShort = squeezeRecently && widthExpanding
                && prevPctB < 0.40 && lastPctB < 0.0
                && prevRed && lastRed
                && prevBodyPct >= 0.45 && lastBodyPct >= 0.55
                && closeNearLow
                && volRatio >= 1.25
                && last.getClose() < sessionVwap
                && !spyBull && rsLead <= 0.0;

        boolean isLong  = bounceLong || breakLong;
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

        // ── Base confidence ───────────────────────────────────────────────────
        int confidence = 70;
        if ("bb-break".equals(setupType)) confidence += 6;
        if (volRatio >= 1.6)              confidence += 5;
        if (Math.abs(rsLead) >= 0.003)    confidence += 4;
        if (widthExpanding && bandwidth >= minRecentBandwidth * 1.25) confidence += 4;

        // ══════════════════════════════════════════════════════════════════════
        // LAYER 2 — Volume Profile
        // ══════════════════════════════════════════════════════════════════════
        VolumeProfile vp = vpCalc.calculate(sessionBars);
        String vpLabel = "N/A";
        if (vp != null) {
            double vpoc = vp.getVpoc(), vah = vp.getVah(), val = vp.getVal();
            // Nearest VP level distance in ATR units
            double nearestDist = Math.min(
                    Math.min(Math.abs(entry - vpoc), Math.abs(entry - vah)),
                    Math.abs(entry - val));
            if (nearestDist < atr * 0.4) {
                confidence += 6;  // entry at a real institutional level
                vpLabel = String.format("AT KEY VP (vpoc=%.2f vah=%.2f val=%.2f)", vpoc, vah, val);
            } else if (nearestDist < atr * 1.0) {
                confidence += 2;
                vpLabel = String.format("NEAR VP (vpoc=%.2f vah=%.2f val=%.2f)", vpoc, vah, val);
            } else {
                confidence -= 4;  // entry is in dead air — no institutional backing
                vpLabel = String.format("NO VP LEVEL (vpoc=%.2f vah=%.2f val=%.2f dist=%.2f atr)", vpoc, vah, val, nearestDist / atr);
            }
            // VP directional sense: long entries should be below or at VPOC
            if (isLong  && entry > vah + atr * 0.3) confidence -= 4;  // chasing above value
            if (!isLong && entry < val - atr * 0.3) confidence -= 4;  // chasing below value
        }

        // ══════════════════════════════════════════════════════════════════════
        // LAYER 3 — Order-flow delta (buy/sell pressure from last 5 bars)
        // Close position within range × volume → directional volume estimate.
        // deltaRatio in [-1, +1]: +1 = all buying, -1 = all selling.
        // ══════════════════════════════════════════════════════════════════════
        double deltaRatio = computeDelta(sessionBars, n - 6, n - 2);
        String deltaLabel;
        if (isLong) {
            if      (deltaRatio >  0.35) { confidence += 5; deltaLabel = String.format("BULL %+.2f ✓", deltaRatio); }
            else if (deltaRatio >  0.10) { confidence += 2; deltaLabel = String.format("MILD BULL %+.2f", deltaRatio); }
            else if (deltaRatio < -0.35) { confidence -= 6; deltaLabel = String.format("BEAR %+.2f ✗ (conflict)", deltaRatio); }
            else                         {                   deltaLabel = String.format("NEUTRAL %+.2f", deltaRatio); }
        } else {
            if      (deltaRatio < -0.35) { confidence += 5; deltaLabel = String.format("BEAR %+.2f ✓", deltaRatio); }
            else if (deltaRatio < -0.10) { confidence += 2; deltaLabel = String.format("MILD BEAR %+.2f", deltaRatio); }
            else if (deltaRatio >  0.35) { confidence -= 6; deltaLabel = String.format("BULL %+.2f ✗ (conflict)", deltaRatio); }
            else                         {                   deltaLabel = String.format("NEUTRAL %+.2f", deltaRatio); }
        }

        // ══════════════════════════════════════════════════════════════════════
        // LAYER 4 — Bid/ask imbalance (live NBBO from Polygon)
        // imbalance: 0 = all asks stacked, 1 = all bids stacked, 0.5 = neutral
        // ══════════════════════════════════════════════════════════════════════
        double nbboImbalance = 0.5;
        String nbboLabel = "unavailable";
        try {
            PolygonClient.NbboSnapshot nbbo = polygon.getNbbo(ticker);
            if (nbbo != null && (nbbo.bidSize() + nbbo.askSize()) > 0) {
                nbboImbalance = nbbo.imbalance();
                if (isLong) {
                    if      (nbboImbalance > 0.68) { confidence += 6; nbboLabel = String.format("BIDS STACKED %.0f%% ✓", nbboImbalance * 100); }
                    else if (nbboImbalance > 0.55) { confidence += 2; nbboLabel = String.format("BID-LEAN %.0f%%", nbboImbalance * 100); }
                    else if (nbboImbalance < 0.38) { confidence -= 4; nbboLabel = String.format("ASKS STACKED %.0f%% ✗", nbboImbalance * 100); }
                    else                           {                   nbboLabel = String.format("NEUTRAL %.0f%%", nbboImbalance * 100); }
                } else {
                    if      (nbboImbalance < 0.32) { confidence += 6; nbboLabel = String.format("ASKS STACKED %.0f%% ✓", nbboImbalance * 100); }
                    else if (nbboImbalance < 0.45) { confidence += 2; nbboLabel = String.format("ASK-LEAN %.0f%%", nbboImbalance * 100); }
                    else if (nbboImbalance > 0.62) { confidence -= 4; nbboLabel = String.format("BIDS STACKED %.0f%% ✗", nbboImbalance * 100); }
                    else                           {                   nbboLabel = String.format("NEUTRAL %.0f%%", nbboImbalance * 100); }
                }
            }
        } catch (Exception ignored) {}

        // ══════════════════════════════════════════════════════════════════════
        // LAYER 5 — Large-print / institutional sweep detection
        // A sweep = single trade > 4× avg recent trade size in last 3 minutes.
        // Direction inferred via tick rule (price vs prior trade).
        // ══════════════════════════════════════════════════════════════════════
        String sweepLabel = "none";
        try {
            List<PolygonClient.TradeRecord> trades = polygon.getRecentTrades(ticker, 50);
            SweepResult sweep = detectSweep(trades);
            if (isLong && sweep.bullish()) {
                confidence += 7;
                sweepLabel = "BULL SWEEP ✓";
            } else if (!isLong && sweep.bearish()) {
                confidence += 7;
                sweepLabel = "BEAR SWEEP ✓";
            } else if (isLong && sweep.bearish()) {
                confidence -= 5;
                sweepLabel = "BEAR SWEEP ✗ (conflict)";
            } else if (!isLong && sweep.bullish()) {
                confidence -= 5;
                sweepLabel = "BULL SWEEP ✗ (conflict)";
            }
        } catch (Exception ignored) {}

        confidence = Math.min(95, Math.max(50, confidence));

        String factors = String.format(
                "%s | %%B %.2f | BW %.3f→%.3f | vol x%.1f | RS %+.2f%% vs SPY" +
                " | VP: %s | delta: %s | nbbo: %s | sweep: %s",
                setupType, lastPctB, prevBandwidth, bandwidth, volRatio, rsLead * 100.0,
                vpLabel, deltaLabel, nbboLabel, sweepLabel);

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Order-flow delta over bars [fromInclusive, toInclusive].
     * Uses close position within candle range as a buy/sell weight.
     * Returns value in [-1, +1]: positive = net buying pressure.
     */
    private double computeDelta(List<OHLCV> bars, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to   = Math.min(bars.size() - 1, toInclusive);
        if (from > to) return 0.0;
        double netDelta = 0, totalVol = 0;
        for (int i = from; i <= to; i++) {
            OHLCV b = bars.get(i);
            double range = Math.max(0.0001, b.getHigh() - b.getLow());
            double bodyPos = (b.getClose() - b.getLow()) / range;  // 0=bear close, 1=bull close
            netDelta += (bodyPos * 2.0 - 1.0) * b.getVolume();
            totalVol += b.getVolume();
        }
        return totalVol > 0 ? netDelta / totalVol : 0.0;
    }

    private record SweepResult(boolean bullish, boolean bearish) {}

    /**
     * Detect large institutional prints in the last 3 minutes.
     * A sweep = any trade > 4× avg size. Direction from tick rule (price vs prior trade).
     */
    private SweepResult detectSweep(List<PolygonClient.TradeRecord> trades) {
        if (trades == null || trades.size() < 5) return new SweepResult(false, false);
        long cutoffNs = (System.currentTimeMillis() - 180_000L) * 1_000_000L;
        List<PolygonClient.TradeRecord> recent = trades.stream()
                .filter(t -> t.timestampNs() > cutoffNs && t.size() > 0)
                .toList();
        if (recent.size() < 3) return new SweepResult(false, false);
        double avgSize = recent.stream().mapToDouble(PolygonClient.TradeRecord::size).average().orElse(0);
        if (avgSize < 10) return new SweepResult(false, false);
        boolean bullSweep = false, bearSweep = false;
        for (int i = 1; i < recent.size(); i++) {
            if (recent.get(i).size() < avgSize * 4) continue;
            // Tick rule: price >= prior trade price → buyer aggressor
            if (recent.get(i).price() >= recent.get(i - 1).price()) bullSweep = true;
            else                                                       bearSweep = true;
        }
        return new SweepResult(bullSweep, bearSweep);
    }

    private List<OHLCV> regularSessionBarsForToday(List<OHLCV> bars) {
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp()).atZone(ET).toLocalDate();
        List<OHLCV> result = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt  = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            LocalTime     time = zdt.toLocalTime();
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
                             Math.abs(curr.getLow()  - prev.getClose())));
            sum += tr;
        }
        return sum / p;
    }

    private double averageVolume(List<OHLCV> bars, int fromInclusive, int toInclusive) {
        int from = Math.max(0, fromInclusive);
        int to   = Math.min(bars.size() - 1, toInclusive);
        if (from > to) return 1.0;
        double sum = 0.0; int count = 0;
        for (int i = from; i <= to; i++) { sum += bars.get(i).getVolume(); count++; }
        return count > 0 ? sum / count : 1.0;
    }

    private double computeSessionVwap(List<OHLCV> bars, int endIdxInclusive) {
        double pv = 0.0, vol = 0.0;
        for (int i = 0; i <= endIdxInclusive && i < bars.size(); i++) {
            OHLCV b = bars.get(i);
            double typical = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
            pv  += typical * b.getVolume();
            vol += b.getVolume();
        }
        return vol > 0 ? pv / vol : bars.get(Math.max(0, Math.min(endIdxInclusive, bars.size() - 1))).getClose();
    }

    private double intradayReturn(List<OHLCV> bars) {
        if (bars == null || bars.size() < 2) return 0.0;
        OHLCV first = bars.get(0), last = bars.get(bars.size() - 1);
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
            Bands b = computeBands(bars, i);
            if (b != null) min = Math.min(min, bandWidth(b));
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
        for (int i = start; i <= idx; i++) { double d = bars.get(i).getClose() - mid; var += d * d; }
        double sd = Math.sqrt(var / BB_PERIOD);
        return new Bands(mid, mid + BB_MULT * sd, mid - BB_MULT * sd);
    }

    private double round4(double value) { return Math.round(value * 10_000.0) / 10_000.0; }

    private record Bands(double mid, double upper, double lower) {}
}
