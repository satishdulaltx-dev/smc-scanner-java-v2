package com.smcscanner.strategy;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.OrderBlock;
import com.smcscanner.smc.OrderBlockAnalyzer;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Microstructure qualifier for scalp setups.
 *
 * Replaces three shallow proxies in ScalpMomentumDetector:
 *   - fake OHLCV "delta" (candle position ≠ tape pressure)
 *   - single NBBO snapshot (one quote ≠ persistent imbalance)
 *   - single-print sweep check (size threshold ≠ burst clustering)
 *
 * Scores five inferences from actual tape data:
 *   1. Absorption at level     — heavy prints, price not moving → support/resistance confirmed
 *   2. Sweep burst quality     — clustered prints in 2-sec windows → directional or trap
 *   3. Failed-breakout penalty — price broke level then reversed → don't chase the corpse
 *   4. Order-block proximity   — unmitigated OB near entry → reclaim = confirm, contra = block
 *   5. Quote imbalance persistence — repeated bid/ask stacking → real queue pressure vs one-off
 *
 * Returns a score in roughly [-25, +30]. Used as a hard gate (≤ -12 blocks the trade)
 * and a confidence modifier in ScalpMomentumDetector.
 */
@Service
public class MicrostructureQualifier {

    private final OrderBlockAnalyzer obAnalyzer;

    public MicrostructureQualifier(OrderBlockAnalyzer obAnalyzer) {
        this.obAnalyzer = obAnalyzer;
    }

    public record MicroScore(int total, String label) {}

    public MicroScore qualify(
            boolean isLong,
            double entry,
            double atr,
            List<PolygonClient.TradeRecord> trades,
            List<PolygonClient.QuoteRecord> quotes,
            List<OHLCV> sessionBars) {

        StringBuilder sb = new StringBuilder("micro[");
        int score = 0;

        score += absorptionScore(isLong, entry, atr, trades, sb);
        score += sweepBurstScore(isLong, trades, sb);
        score += failedBreakoutPenalty(isLong, sessionBars, atr, sb);
        score += obProximityScore(isLong, entry, atr, sessionBars, sb);
        score += quoteImbalanceScore(isLong, quotes, sb);

        sb.append(" total=").append(score).append("]");
        return new MicroScore(score, sb.toString());
    }

    // ── 1. Absorption at level ────────────────────────────────────────────────
    // Heavy volume printed at a tight price range near entry, without price moving
    // through that range, signals institutional absorption (buyers eating sellers at
    // support for long, or sellers absorbing buyers at resistance for short).

    private int absorptionScore(boolean isLong, double entry, double atr,
                                List<PolygonClient.TradeRecord> trades, StringBuilder sb) {
        if (trades.size() < 5) return 0;

        long cutoffNs = (System.currentTimeMillis() - 90_000L) * 1_000_000L;
        List<PolygonClient.TradeRecord> recent = trades.stream()
                .filter(t -> t.timestampNs() > cutoffNs && t.size() > 0).toList();
        if (recent.size() < 5) return 0;

        double avgSize = recent.stream().mapToDouble(PolygonClient.TradeRecord::size).average().orElse(1);

        // For long: absorb zone is just at/below entry (buyers absorbing sellers at support)
        // For short: absorb zone is just at/above entry
        double zoneHalf = atr * 0.12;
        double zoneMin  = isLong ? entry - atr * 0.35 : entry - zoneHalf;
        double zoneMax  = isLong ? entry + zoneHalf   : entry + atr * 0.35;

        List<PolygonClient.TradeRecord> atLevel = recent.stream()
                .filter(t -> t.price() >= zoneMin && t.price() <= zoneMax).toList();

        if (atLevel.size() >= 3) {
            double totalVol  = atLevel.stream().mapToDouble(PolygonClient.TradeRecord::size).sum();
            double priceSpan = atLevel.stream().mapToDouble(PolygonClient.TradeRecord::price).max().orElse(0)
                             - atLevel.stream().mapToDouble(PolygonClient.TradeRecord::price).min().orElse(0);
            // Absorbed: heavy prints at a tight price — price didn't move through the level
            boolean absorbed = priceSpan < atr * 0.07 && totalVol >= avgSize * atLevel.size() * 1.4;
            if (absorbed) {
                double ratio = totalVol / (avgSize * atLevel.size());
                if (ratio >= 3.0) { sb.append(" ABS:STRONG(x").append(String.format("%.1f", ratio)).append(")"); return 12; }
                if (ratio >= 1.8) { sb.append(" ABS:MOD");   return  7; }
                sb.append(" ABS:WEAK");                       return  3;
            }
        }

        // Check for absorption running against the setup — heavy prints on the opposite side
        double contraMin = isLong ? entry + zoneHalf   : entry - atr * 0.35;
        double contraMax = isLong ? entry + atr * 0.35 : entry + zoneHalf;
        List<PolygonClient.TradeRecord> contra = recent.stream()
                .filter(t -> t.price() >= contraMin && t.price() <= contraMax).toList();
        if (contra.size() >= 3) {
            double contraVol  = contra.stream().mapToDouble(PolygonClient.TradeRecord::size).sum();
            double contraSpan = contra.stream().mapToDouble(PolygonClient.TradeRecord::price).max().orElse(0)
                              - contra.stream().mapToDouble(PolygonClient.TradeRecord::price).min().orElse(0);
            if (contraSpan < atr * 0.07 && contraVol >= avgSize * contra.size() * 2.0) {
                sb.append(" ABS:CONTRA"); return -8;
            }
        }

        return 0;
    }

    // ── 2. Sweep burst quality ────────────────────────────────────────────────
    // Groups trades into 2-second buckets. A burst = bucket with ≥3 prints and
    // total size ≥ 4× average. Directional if price moved during the burst.
    // A burst that reversed after = trap (penalise if it was in setup direction).
    // A contra-burst that failed (reversed back) = setup-confirming.

    private int sweepBurstScore(boolean isLong,
                                List<PolygonClient.TradeRecord> trades, StringBuilder sb) {
        if (trades.size() < 5) return 0;

        long cutoffNs = (System.currentTimeMillis() - 60_000L) * 1_000_000L;
        List<PolygonClient.TradeRecord> recent = new ArrayList<>(trades.stream()
                .filter(t -> t.timestampNs() > cutoffNs && t.size() > 0).toList());
        if (recent.size() < 5) return 0;

        recent.sort(Comparator.comparingLong(PolygonClient.TradeRecord::timestampNs)); // oldest first
        double avgSize = recent.stream().mapToDouble(PolygonClient.TradeRecord::size).average().orElse(1);

        // Group into 2-second buckets
        Map<Long, List<PolygonClient.TradeRecord>> buckets = new TreeMap<>();
        for (var t : recent) {
            long bucket = t.timestampNs() / 2_000_000_000L;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(t);
        }

        // Find the bucket with the highest total size that qualifies as a burst
        Map.Entry<Long, List<PolygonClient.TradeRecord>> burstEntry = null;
        double burstTotal = 0;
        for (var e : buckets.entrySet()) {
            List<PolygonClient.TradeRecord> b = e.getValue();
            if (b.size() < 3) continue;
            double tot = b.stream().mapToDouble(PolygonClient.TradeRecord::size).sum();
            if (tot >= avgSize * 4 && tot > burstTotal) { burstTotal = tot; burstEntry = e; }
        }
        if (burstEntry == null) return 0;

        List<PolygonClient.TradeRecord> burst = burstEntry.getValue();
        double burstFirst = burst.get(0).price();
        double burstLast  = burst.get(burst.size() - 1).price();
        boolean burstBull = burstLast > burstFirst * 1.0001; // net up move inside burst
        boolean burstBear = burstLast < burstFirst * 0.9999;

        // Check whether price reversed AFTER the burst bucket
        long burstBucket = burstEntry.getKey();
        List<PolygonClient.TradeRecord> after = recent.stream()
                .filter(t -> t.timestampNs() / 2_000_000_000L > burstBucket).toList();

        boolean reversedAfter = false;
        if (after.size() >= 2) {
            double afterFirst = after.get(0).price();
            double afterLast  = after.get(after.size() - 1).price();
            if (burstBull && afterLast < afterFirst * 0.9999) reversedAfter = true;
            if (burstBear && afterLast > afterFirst * 1.0001) reversedAfter = true;
        }

        if (reversedAfter) {
            // Burst in setup direction that reversed = trap, don't chase
            if (isLong  && burstBull) { sb.append(" SWEEP:BULL-TRAP");       return -10; }
            if (!isLong && burstBear) { sb.append(" SWEEP:BEAR-TRAP");       return -10; }
            // Contra burst that reversed = opposing pressure exhausted, setup confirmed
            if (isLong  && burstBear) { sb.append(" SWEEP:BEAR-EXHAUST");    return  8; }
            if (!isLong && burstBull) { sb.append(" SWEEP:BULL-EXHAUST");    return  8; }
        } else {
            // Sustained burst in setup direction
            if (isLong  && burstBull) { sb.append(String.format(" SWEEP:BULL-CONF(x%.1f)", burstTotal / avgSize)); return 10; }
            if (!isLong && burstBear) { sb.append(String.format(" SWEEP:BEAR-CONF(x%.1f)", burstTotal / avgSize)); return 10; }
            // Ongoing contra burst = setup direction is wrong
            if (isLong  && burstBear) { sb.append(" SWEEP:BEAR-ONGOING");    return -8; }
            if (!isLong && burstBull) { sb.append(" SWEEP:BULL-ONGOING");    return -8; }
        }
        return 0;
    }

    // ── 3. Failed-breakout penalty ────────────────────────────────────────────
    // If price made a new high 2 bars ago (for a long) and then immediately reversed
    // below that bar's low on the last bar, it is a bull trap — penalise the long entry.
    // Same logic inverted for shorts.

    private int failedBreakoutPenalty(boolean isLong, List<OHLCV> sessionBars,
                                      double atr, StringBuilder sb) {
        int n = sessionBars.size();
        if (n < 4) return 0;
        OHLCV bar3 = sessionBars.get(n - 3);
        OHLCV bar2 = sessionBars.get(n - 2);
        OHLCV bar1 = sessionBars.get(n - 1);

        if (isLong) {
            // bar2 broke above prior high, bar1 reversed below bar2's low = bull trap
            boolean newHigh   = bar2.getHigh() > bar3.getHigh() + atr * 0.04;
            boolean reversal  = bar1.getClose() < bar2.getLow();
            if (newHigh && reversal) { sb.append(" FAKEOUT:BULL-TRAP"); return -12; }
        } else {
            // bar2 broke below prior low, bar1 reversed above bar2's high = bear trap
            boolean newLow    = bar2.getLow() < bar3.getLow() - atr * 0.04;
            boolean reversal  = bar1.getClose() > bar2.getHigh();
            if (newLow && reversal) { sb.append(" FAKEDOWN:BEAR-TRAP"); return -12; }
        }
        return 0;
    }

    // ── 4. Order-block proximity ──────────────────────────────────────────────
    // An unmitigated order block near entry in the setup direction is the single
    // strongest structural confirmation — price is returning to an imbalance zone.
    // A contra OB (unmitigated, opposing direction) within striking distance means
    // the trade is likely to run into that resistance/support immediately.

    private int obProximityScore(boolean isLong, double entry, double atr,
                                 List<OHLCV> sessionBars, StringBuilder sb) {
        if (sessionBars.size() < 10) return 0;
        List<OrderBlock> obs;
        try { obs = obAnalyzer.detectOrderBlocks(sessionBars); }
        catch (Exception e) { return 0; }
        if (obs.isEmpty()) return 0;

        String setupDir = isLong ? "bullish" : "bearish";
        String contraDir = isLong ? "bearish" : "bullish";

        // Find nearest unmitigated OB in setup direction
        for (OrderBlock ob : obs) {
            if (ob.isMitigated() || !ob.getDirection().equals(setupDir)) continue;
            double dist = Math.abs(ob.midpoint() - entry);
            if (dist > atr * 1.0) continue;
            boolean reclaiming = isLong
                    ? entry >= ob.getLow()   // entered from below or inside bullish OB
                    : entry <= ob.getHigh(); // entered from above or inside bearish OB
            if (reclaiming) {
                sb.append(String.format(" OB:%s-RECLAIM(%.2f)", setupDir.toUpperCase(), ob.midpoint()));
                return dist < atr * 0.4 ? 12 : 8;
            }
        }

        // Check for unmitigated contra OB too close — will act as a magnet against the trade
        for (OrderBlock ob : obs) {
            if (ob.isMitigated() || !ob.getDirection().equals(contraDir)) continue;
            double dist = Math.abs(ob.midpoint() - entry);
            if (dist < atr * 0.6) {
                sb.append(String.format(" OB:%s-BLOCK(%.2f)", contraDir.toUpperCase(), ob.midpoint()));
                return -10;
            }
        }

        return 0;
    }

    // ── 5. Quote imbalance persistence ───────────────────────────────────────
    // A single NBBO snapshot can be noise. Repeated bid-stacking across 20 quote
    // updates means real queue pressure: large buyers continuously refreshing bids.
    // One snapshot saying bids are 70% of book means almost nothing.
    // 14 out of 20 snapshots showing that means something real is sitting there.

    private int quoteImbalanceScore(boolean isLong, List<PolygonClient.QuoteRecord> quotes,
                                    StringBuilder sb) {
        if (quotes.size() < 5) return 0;
        int n = Math.min(quotes.size(), 20);
        long bidStack = quotes.stream().limit(n).filter(q -> q.imbalance() > 0.62).count();
        long askStack = quotes.stream().limit(n).filter(q -> q.imbalance() < 0.38).count();
        double bidPct = (double) bidStack / n;
        double askPct = (double) askStack / n;

        if (isLong) {
            if (bidPct >= 0.65) { sb.append(String.format(" QI:BID-STACK(%.0f%%)", bidPct * 100)); return  8; }
            if (bidPct >= 0.50) { sb.append(String.format(" QI:BID-LEAN(%.0f%%)",  bidPct * 100)); return  3; }
            if (askPct >= 0.65) { sb.append(String.format(" QI:ASK-STACK(%.0f%%)", askPct * 100)); return -5; }
        } else {
            if (askPct >= 0.65) { sb.append(String.format(" QI:ASK-STACK(%.0f%%)", askPct * 100)); return  8; }
            if (askPct >= 0.50) { sb.append(String.format(" QI:ASK-LEAN(%.0f%%)",  askPct * 100)); return  3; }
            if (bidPct >= 0.65) { sb.append(String.format(" QI:BID-STACK(%.0f%%)", bidPct * 100)); return -5; }
        }
        return 0;
    }
}
