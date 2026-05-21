package com.smcscanner.strategy;

import com.smcscanner.indicator.VolumeProfileCalculator;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.model.indicator.VolumeProfileDetailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Volume Profile + Order Flow strategy.
 *
 * Builds a session volume profile (VPOC / VAH / VAL / HVN / LVN) and derives
 * directional order flow from per-bar delta approximation. Five signal types:
 *
 *   A) va-reentry   – price re-enters the 70% Value Area with delta confirmation
 *   B) delta-div    – price diverges from cumulative delta (absorption at extreme)
 *   C) vpoc-magnet  – price drifts toward VPOC with consistent directional delta
 *   D) hvn-reject   – price rejected at a High Volume Node on a volume spike
 *   E) lvn-break    – price accelerates into a Low Volume Node gap
 *
 * Delta approximation (OHLCV only, no tick data):
 *   buyVol  = volume × (close − low) / (high − low)
 *   sellVol = volume − buyVol
 *   delta   = buyVol − sellVol  → range [−volume, +volume]
 */
@Service
public class VolumeOrderFlowDetector {
    private static final Logger log = LoggerFactory.getLogger(VolumeOrderFlowDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final double HVN_THRESHOLD  = 0.60;   // bucket vol >= 60% of VPOC vol
    private static final double LVN_THRESHOLD  = 0.20;   // bucket vol <= 20% of VPOC vol
    private static final double VPOC_MAGNET_PCT = 0.004; // 0.4% window to trigger VPOC magnet
    private static final double HVN_TOUCH_PCT   = 0.003; // 0.3% tolerance for HVN touches

    private final VolumeProfileCalculator vpCalc;

    public VolumeOrderFlowDetector(VolumeProfileCalculator vpCalc) {
        this.vpCalc = vpCalc;
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.isEmpty()) return result;

        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp()).atZone(ET).toLocalDate();
        if (!backtestMode && !today.equals(LocalDate.now(ET))) return result;

        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);
        List<OHLCV> session = new ArrayList<>();
        for (OHLCV b : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                session.add(b);
            }
        }

        // Volume profile is statistically too thin before 10:00 ET (first 30 min)
        if (session.size() < 30) return result;
        LocalTime lastTime = Instant.ofEpochMilli(session.get(session.size() - 1).getTimestamp())
                .atZone(ET).toLocalTime();
        if (lastTime.isBefore(LocalTime.of(10, 0))) return result;

        // After 14:30 ET the VPOC is well established but there is insufficient time
        // remaining in the session to reach VP-derived targets — skip new entries.
        if (lastTime.isAfter(LocalTime.of(14, 30))) return result;

        VolumeProfileDetailed vp = vpCalc.calculateDetailed(session);
        if (vp == null) return result;

        // ── Per-bar delta: buying pressure minus selling pressure ──────────────
        // Using close position in bar range as buying fraction.
        // delta[i] in range [−vol, +vol]: positive = net buying, negative = net selling.
        int n = session.size();
        double[] delta    = new double[n];
        double[] cumDelta = new double[n];
        for (int i = 0; i < n; i++) {
            OHLCV b     = session.get(i);
            double range = b.getHigh() - b.getLow();
            double buyPct = range > 1e-8 ? (b.getClose() - b.getLow()) / range : 0.5;
            delta[i] = b.getVolume() * (2.0 * buyPct - 1.0);
        }
        cumDelta[0] = delta[0];
        for (int i = 1; i < n; i++) cumDelta[i] = cumDelta[i - 1] + delta[i];

        OHLCV last   = session.get(n - 1);
        double avgVol = session.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        double rvol   = avgVol > 0 ? last.getVolume() / avgVol : 1.0;
        double atr    = Math.max(dailyAtr, last.getClose() * 0.003);

        // ── Session trend bias ─────────────────────────────────────────────────
        // deltaRatio = cumulative delta / total session volume.
        // Range: −1 (100% selling) to +1 (100% buying).
        // A strongly positive session means institutions are buying — don't fade longs,
        // don't take counter-trend shorts. Threshold ±0.15 (15% net imbalance).
        double totalSessionVol = session.stream().mapToDouble(OHLCV::getVolume).sum();
        double deltaRatio = totalSessionVol > 0 ? cumDelta[n - 1] / totalSessionVol : 0;
        boolean sessionBullish = deltaRatio >  0.15;  // buyers in control — longs only
        boolean sessionBearish = deltaRatio < -0.15;  // sellers in control — shorts only

        List<TradeSetup> candidates = new ArrayList<>();

        TradeSetup s;
        if ((s = detectValueAreaReentry(session, delta, cumDelta, vp, atr, rvol, ticker, sessionBullish, sessionBearish)) != null) candidates.add(s);
        if ((s = detectDeltaDivergence (session, delta, cumDelta, vp, atr,       ticker, sessionBullish, sessionBearish)) != null) candidates.add(s);
        if ((s = detectVpocMagnet      (session, delta,           vp, atr, rvol, ticker, sessionBullish, sessionBearish)) != null) candidates.add(s);
        if ((s = detectHvnRejection    (session, delta,           vp, atr, rvol, ticker, sessionBullish, sessionBearish)) != null) candidates.add(s);
        if ((s = detectLvnBreakout     (session, delta,           vp, atr, rvol, ticker, sessionBullish, sessionBearish)) != null) candidates.add(s);

        candidates.sort(Comparator.comparingInt(TradeSetup::getConfidence).reversed());
        if (!candidates.isEmpty()) {
            TradeSetup best = candidates.get(0);
            result.add(best);
            log.debug("{} VOLFLOW signal={} conf={} entry={} sl={} tp={}",
                    ticker, best.getFactorBreakdown(), best.getConfidence(),
                    String.format("%.2f", best.getEntry()),
                    String.format("%.2f", best.getStopLoss()),
                    String.format("%.2f", best.getTakeProfit()));
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIGNAL A: VALUE AREA RE-ENTRY
    //  Price was outside the 70% value area for ≥2 bars, then crosses back in.
    //  The re-entry bar must confirm with positive (long) or negative (short) delta.
    //  Logic: institutional traders fade price outside fair value — when price
    //  snaps back into the value area it tends to magnetise toward VPOC.
    // ════════════════════════════════════════════════════════════════════════
    private TradeSetup detectValueAreaReentry(List<OHLCV> session, double[] delta, double[] cumDelta,
            VolumeProfileDetailed vp, double atr, double rvol, String ticker,
            boolean sessionBullish, boolean sessionBearish) {
        int n = session.size();
        if (n < 8) return null;

        OHLCV last  = session.get(n - 1);
        OHLCV prev  = session.get(n - 2);
        double lc   = last.getClose();
        double pc   = prev.getClose();
        double vpoc = vp.getVpoc();
        double vah  = vp.getVah();
        double val  = vp.getVal();

        int barsAbove = 0, barsBelow = 0;
        for (int i = Math.max(0, n - 7); i < n - 1; i++) {
            double c = session.get(i).getClose();
            if (c > vah) barsAbove++;
            if (c < val) barsBelow++;
        }

        // ── LONG: re-entry from below VAL ─────────────────────────────────────
        if (barsBelow >= 2 && lc >= val && pc < val) {
            if (sessionBearish) return null;  // don't fight the trend
            if (delta[n - 1] <= 0 || rvol < 0.6) return null;

            int conf = 64;
            StringBuilder f = new StringBuilder("va-reentry-long");
            if (cumDelta[n - 1] > 0)                            { conf += 5; f.append(" | cumDelta+"); }
            if (rvol > 1.5)                                      { conf += 4; f.append(" | rvol+"); }
            if (delta[n - 1] > 0.30 * last.getVolume())         { conf += 4; f.append(" | delta++"); }
            if (lc > vpoc)                                       { conf += 3; f.append(" | above_vpoc"); }
            if (lc > (val + vah) / 2.0)                         { conf += 2; f.append(" | mid_above"); }

            double sl = val - 0.4 * atr;
            double tp = Math.abs(vpoc - lc) > atr * 0.25 ? vpoc : vah + 0.5 * atr;

            return build(ticker, "long", lc, sl, tp, conf, atr, f.toString(), last);
        }

        // ── SHORT: re-entry from above VAH ─────────────────────────────────────
        if (barsAbove >= 2 && lc <= vah && pc > vah) {
            if (sessionBullish) return null;  // don't fight the trend
            if (delta[n - 1] >= 0 || rvol < 0.6) return null;

            int conf = 64;
            StringBuilder f = new StringBuilder("va-reentry-short");
            if (cumDelta[n - 1] < 0)                             { conf += 5; f.append(" | cumDelta-"); }
            if (rvol > 1.5)                                      { conf += 4; f.append(" | rvol+"); }
            if (delta[n - 1] < -0.30 * last.getVolume())        { conf += 4; f.append(" | delta--"); }
            if (lc < vpoc)                                       { conf += 3; f.append(" | below_vpoc"); }

            double sl = vah + 0.4 * atr;
            double tp = Math.abs(lc - vpoc) > atr * 0.25 ? vpoc : val - 0.5 * atr;

            return build(ticker, "short", lc, sl, tp, conf, atr, f.toString(), last);
        }

        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIGNAL B: DELTA DIVERGENCE (absorption)
    //  At a session extreme, if the bar that set the extreme has delta in the
    //  OPPOSITE direction to the move, large players were absorbing the retail
    //  crowd. Price should reverse toward fair value (VPOC).
    //
    //  Bullish: price at session low, bar delta positive (buyers absorbing sellers)
    //  Bearish: price at session high, bar delta negative (sellers absorbing buyers)
    // ════════════════════════════════════════════════════════════════════════
    private TradeSetup detectDeltaDivergence(List<OHLCV> session, double[] delta, double[] cumDelta,
            VolumeProfileDetailed vp, double atr, String ticker,
            boolean sessionBullish, boolean sessionBearish) {
        int n = session.size();
        if (n < 15) return null;

        int window = Math.min(12, n);
        int start  = n - window;

        int lowIdx = start, highIdx = start;
        for (int i = start; i < n; i++) {
            if (session.get(i).getClose() < session.get(lowIdx).getClose())  lowIdx  = i;
            if (session.get(i).getClose() > session.get(highIdx).getClose()) highIdx = i;
        }

        OHLCV last = session.get(n - 1);
        double lc  = last.getClose();

        double avgVol = 0;
        for (int i = start; i < n; i++) avgVol += session.get(i).getVolume();
        avgVol /= window;

        // ── BULLISH divergence: session low bar had POSITIVE delta ─────────────
        if (!sessionBearish && lowIdx >= n - 6 && lowIdx < n - 1) {
            double lowClose  = session.get(lowIdx).getClose();
            double lowDelta  = delta[lowIdx];
            double recovPct  = (lc - lowClose) / Math.max(lowClose, 1.0);

            if (lowDelta > 0 && recovPct > 0.0015 && lc > lowClose) {
                boolean volSpike = session.get(lowIdx).getVolume() > avgVol * 1.2;

                int conf = 66;
                StringBuilder f = new StringBuilder("delta-div-bullish");
                if (volSpike)                                           { conf += 6; f.append(" | vol_spike"); }
                if (lowDelta > 0.20 * session.get(lowIdx).getVolume()) { conf += 4; f.append(" | strong_delta+"); }
                if (recovPct > 0.004)                                  { conf += 3; f.append(" | big_recovery"); }
                if (cumDelta[n - 1] > cumDelta[lowIdx])                { conf += 4; f.append(" | cumDelta_rising"); }

                double sl = Math.min(session.get(lowIdx).getLow() - 0.2 * atr, lc - 0.6 * atr);
                double tp = Math.max(vp.getVpoc(), lc + 0.8 * atr);

                return build(ticker, "long", lc, sl, tp, conf, atr, f.toString(), last);
            }
        }

        // ── BEARISH divergence: session high bar had NEGATIVE delta ────────────
        if (!sessionBullish && highIdx >= n - 6 && highIdx < n - 1) {
            double highClose = session.get(highIdx).getClose();
            double highDelta = delta[highIdx];
            double declinePct = (highClose - lc) / Math.max(highClose, 1.0);

            if (highDelta < 0 && declinePct > 0.0015 && lc < highClose) {
                boolean volSpike = session.get(highIdx).getVolume() > avgVol * 1.2;

                int conf = 66;
                StringBuilder f = new StringBuilder("delta-div-bearish");
                if (volSpike)                                             { conf += 6; f.append(" | vol_spike"); }
                if (highDelta < -0.20 * session.get(highIdx).getVolume()){ conf += 4; f.append(" | strong_delta-"); }
                if (declinePct > 0.004)                                  { conf += 3; f.append(" | big_decline"); }
                if (cumDelta[n - 1] < cumDelta[highIdx])                 { conf += 4; f.append(" | cumDelta_falling"); }

                double sl = Math.max(session.get(highIdx).getHigh() + 0.2 * atr, lc + 0.6 * atr);
                double tp = Math.min(vp.getVpoc(), lc - 0.8 * atr);

                return build(ticker, "short", lc, sl, tp, conf, atr, f.toString(), last);
            }
        }

        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIGNAL C: VPOC MAGNET
    //  Price within 0.4% of VPOC and trending toward it with consistent delta.
    //  VPOC is the fair-value anchor; price has a statistical pull toward it
    //  when order flow agrees with the direction.
    // ════════════════════════════════════════════════════════════════════════
    private TradeSetup detectVpocMagnet(List<OHLCV> session, double[] delta,
            VolumeProfileDetailed vp, double atr, double rvol, String ticker,
            boolean sessionBullish, boolean sessionBearish) {
        int n = session.size();
        if (n < 12) return null;

        OHLCV last   = session.get(n - 1);
        double lc    = last.getClose();
        double vpoc  = vp.getVpoc();
        double distPct = Math.abs(lc - vpoc) / Math.max(vpoc, 1.0);

        if (distPct > VPOC_MAGNET_PCT || distPct < 0.0005) return null;

        int look = Math.min(5, n);
        int pos  = 0, neg = 0;
        for (int i = n - look; i < n; i++) {
            if (delta[i] > 0) pos++; else neg++;
        }

        // ── LONG: below VPOC, buyers pushing toward it ─────────────────────────
        if (lc < vpoc && pos >= 3) {
            if (sessionBearish) return null;
            if (rvol < 0.8) return null;  // require some participation on magnet entries
            if (lc <= session.get(n - 3).getClose()) return null; // must be trending up

            int conf = 60;
            StringBuilder f = new StringBuilder("vpoc-magnet-long");
            if (pos >= 4)     { conf += 4; f.append(" | delta+4/5"); }
            if (rvol > 1.2)   { conf += 3; f.append(" | rvol+"); }
            if (distPct < 0.002) { conf += 3; f.append(" | near_vpoc"); }

            return build(ticker, "long", lc, lc - 0.5 * atr, vpoc + 0.3 * atr, conf, atr, f.toString(), last);
        }

        // ── SHORT: above VPOC, sellers pushing toward it ───────────────────────
        if (lc > vpoc && neg >= 3) {
            if (sessionBullish) return null;
            if (rvol < 0.8) return null;
            if (lc >= session.get(n - 3).getClose()) return null;

            int conf = 60;
            StringBuilder f = new StringBuilder("vpoc-magnet-short");
            if (neg >= 4)     { conf += 4; f.append(" | delta-4/5"); }
            if (rvol > 1.2)   { conf += 3; f.append(" | rvol+"); }
            if (distPct < 0.002) { conf += 3; f.append(" | near_vpoc"); }

            return build(ticker, "short", lc, lc + 0.5 * atr, vpoc - 0.3 * atr, conf, atr, f.toString(), last);
        }

        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIGNAL D: HVN REJECTION
    //  High Volume Nodes act as strong S/R because large resting orders cluster
    //  there. A volume spike rejection (bar touched HVN but closed away from it
    //  with opposing delta) signals institutional absorption at the node.
    // ════════════════════════════════════════════════════════════════════════
    private TradeSetup detectHvnRejection(List<OHLCV> session, double[] delta,
            VolumeProfileDetailed vp, double atr, double rvol, String ticker,
            boolean sessionBullish, boolean sessionBearish) {
        int n = session.size();
        if (n < 8 || rvol < 1.2) return null;

        OHLCV last   = session.get(n - 1);
        double lc    = last.getClose();
        double ld    = delta[n - 1];
        double[] hvn = vp.hvnLevels(HVN_THRESHOLD);
        if (hvn.length == 0) return null;

        for (double level : hvn) {
            if (Math.abs(level - lc) / Math.max(level, 1.0) > HVN_TOUCH_PCT) continue;

            // SHORT: bar's high reached HVN but close is below — rejection
            if (!sessionBullish && last.getHigh() >= level * 0.999 && lc < level && ld < 0) {
                int conf = 62;
                StringBuilder f = new StringBuilder("hvn-reject-short");
                if (rvol > 2.0)                              { conf += 5; f.append(" | big_vol_spike"); }
                else if (rvol > 1.5)                         { conf += 3; f.append(" | vol_spike"); }
                if (ld < -0.30 * last.getVolume())           { conf += 4; f.append(" | strong_delta-"); }
                f.append(String.format(" | hvn=%.2f", level));

                double sl = level + 0.3 * atr;
                double tp = Math.min(vp.getVpoc(), lc - 0.7 * atr);
                return build(ticker, "short", lc, sl, tp, conf, atr, f.toString(), last);
            }

            // LONG: bar's low reached HVN but close is above — support bounce
            if (!sessionBearish && last.getLow() <= level * 1.001 && lc > level && ld > 0) {
                int conf = 62;
                StringBuilder f = new StringBuilder("hvn-reject-long");
                if (rvol > 2.0)                              { conf += 5; f.append(" | big_vol_spike"); }
                else if (rvol > 1.5)                         { conf += 3; f.append(" | vol_spike"); }
                if (ld > 0.30 * last.getVolume())            { conf += 4; f.append(" | strong_delta+"); }
                f.append(String.format(" | hvn=%.2f", level));

                double sl = level - 0.3 * atr;
                double tp = Math.max(vp.getVpoc(), lc + 0.7 * atr);
                return build(ticker, "long", lc, sl, tp, conf, atr, f.toString(), last);
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SIGNAL E: LVN BREAKOUT
    //  Low Volume Nodes are price gaps in the profile — thin areas with few
    //  resting orders. When price breaks into an LVN it tends to accelerate
    //  quickly to the next HVN on the other side (no resistance / support).
    // ════════════════════════════════════════════════════════════════════════
    private TradeSetup detectLvnBreakout(List<OHLCV> session, double[] delta,
            VolumeProfileDetailed vp, double atr, double rvol, String ticker,
            boolean sessionBullish, boolean sessionBearish) {
        int n = session.size();
        if (n < 10) return null;

        OHLCV last  = session.get(n - 1);
        OHLCV prev  = session.get(n - 2);
        double lc   = last.getClose();
        double pc   = prev.getClose();
        double ld   = delta[n - 1];

        double[] buckets   = vp.getBuckets();
        double   priceBase = vp.getPriceBase();
        double   bktSz     = vp.getBucketSize();

        int curBkt  = vp.bucketIndex(lc);
        int prevBkt = vp.bucketIndex(pc);
        if (curBkt < 0 || prevBkt < 0 || curBkt == prevBkt) return null;

        if (!vp.isLvn(curBkt, LVN_THRESHOLD)) return null;

        boolean isLong = curBkt > prevBkt;
        if (isLong  && (ld <= 0 || sessionBearish)) return null;
        if (!isLong && (ld >= 0 || sessionBullish)) return null;

        // Find nearest HVN on the far side as TP target
        double tp;
        if (isLong) {
            tp = lc + 1.0 * atr;
            for (int i = curBkt + 1; i < buckets.length; i++) {
                if (vp.isHvn(i, HVN_THRESHOLD)) { tp = priceBase + (i + 0.5) * bktSz; break; }
            }
        } else {
            tp = lc - 1.0 * atr;
            for (int i = curBkt - 1; i >= 0; i--) {
                if (vp.isHvn(i, HVN_THRESHOLD)) { tp = priceBase + (i + 0.5) * bktSz; break; }
            }
        }

        int conf = 61;
        StringBuilder f = new StringBuilder("lvn-break-").append(isLong ? "long" : "short");
        if (rvol > 1.5)                              { conf += 4; f.append(" | rvol+"); }
        if (Math.abs(ld) > 0.30 * last.getVolume()) { conf += 3; f.append(" | delta_strong"); }

        double sl = isLong ? lc - 0.5 * atr : lc + 0.5 * atr;
        return build(ticker, isLong ? "long" : "short", lc, sl, tp, conf, atr, f.toString(), last);
    }

    // ── Builder helper ────────────────────────────────────────────────────────
    private TradeSetup build(String ticker, String dir, double entry, double sl, double tp,
                              int conf, double atr, String factors, OHLCV bar) {
        return TradeSetup.builder()
                .ticker(ticker).direction(dir)
                .entry(entry).stopLoss(sl).takeProfit(tp)
                .confidence(conf).atr(atr)
                .session("NYSE").volatility("normal")
                .factorBreakdown(factors)
                .timestamp(Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET).toLocalDateTime())
                .build();
    }
}
