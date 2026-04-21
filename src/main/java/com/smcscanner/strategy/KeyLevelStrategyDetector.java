package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TickerProfile;
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
 * Key Level Rejection (KLR) strategy detector.
 *
 * Identifies horizontal support/resistance levels that have been tested 2+ times
 * on daily (or hourly) bars, then fires alerts when price touches and rejects
 * from those levels on 5-minute bars.
 *
 * Why these levels work:
 *  - Large institutions place block orders at visible S/R levels to distribute/accumulate
 *  - Every market participant can see the same level → self-fulfilling prophecy
 *  - 3rd or 4th test of the same price is the highest-probability rejection
 *
 * Works best for large-cap, liquid stocks (AAPL, MSFT, META) where there is
 * enough institutional size to create persistent price memory.
 */
@Service
public class KeyLevelStrategyDetector {

    /** Max price distance (%) for two swing points to belong to the same cluster. */
    private static final double LEVEL_TOLERANCE = 0.003;  // 0.3%

    /** Max distance (%) from current price to a level for it to be "in range". */
    private static final double TOUCH_TOLERANCE = 0.004;  // 0.4%

    /** Minimum number of daily bars touching the same zone for it to be a valid level. */
    private static final int MIN_TOUCHES = 2;

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * Detect key-level rejection setups.
     *
     * @param fiveMinBars  recent 5-minute bars (used for entry signal + ATR)
     * @param htfBars      daily bars used to identify key S/R levels and trend
     * @param ticker       symbol
     * @param dailyAtr     daily ATR for TP sizing
     * @return             0 or 1 detected setup
     */
    public List<TradeSetup> detect(List<OHLCV> fiveMinBars, List<OHLCV> htfBars,
                                    String ticker, double dailyAtr, TickerProfile profile) {
        double slMult  = profile != null ? profile.resolveSlAtrMult() : 0.5;
        double tpRatio = profile != null ? profile.resolveTpRrRatio() : 1.5;
        List<TradeSetup> result = new ArrayList<>();
        if (fiveMinBars == null || fiveMinBars.isEmpty()) return result;
        if (htfBars == null || htfBars.size() < 10)       return result;

        // ── Filter 5m bars to regular NYSE session (9:30–16:00 ET) ────────────
        // This avoids: (a) pre-market volume distorting avgVol, (b) firing on the
        // noisy first opening bar, (c) after-hours false signals.
        LocalDate sysToday  = LocalDate.now(ET);
        LocalDate today     = Instant.ofEpochMilli(
                fiveMinBars.get(fiveMinBars.size() - 1).getTimestamp()).atZone(ET).toLocalDate();
        // Staleness guard: reject if last bar is from a prior calendar day
        if (!today.equals(sysToday)) return result;
        LocalTime mktOpen   = LocalTime.of(9, 30);
        LocalTime mktClose  = LocalTime.of(16, 0);

        List<OHLCV> sessionBars = new ArrayList<>();
        for (OHLCV bar : fiveMinBars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                sessionBars.add(bar);
            }
        }

        // Require at least 5 session bars — skip the volatile opening bar(s)
        if (sessionBars.size() < 5) return result;

        OHLCV last     = sessionBars.get(sessionBars.size() - 1);
        double curClose = last.getClose();
        double curOpen  = last.getOpen();
        double curHigh  = last.getHigh();
        double curLow   = last.getLow();

        // 5m ATR computed from session bars only
        double atr5m = computeAtr(sessionBars.size() >= 15 ? sessionBars : fiveMinBars);
        double atr   = Math.max(atr5m, curClose * 0.001);

        // TP sizing: prefer dailyAtr if meaningful, else scale from 5m ATR
        double effectiveAtr = (dailyAtr > atr * 3) ? dailyAtr : atr * 8;

        // Average session volume (excludes pre-market noise)
        double avgVol = sessionBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        // ── HTF trend filter (20-day SMA from daily bars) ────────────────────
        // Only take LONG setups when price is in an uptrend (above SMA20).
        // Only take SHORT setups when price is in a downtrend (below SMA20).
        // This prevents counter-trend trades on broken levels.
        String htfTrend = "neutral";
        if (htfBars.size() >= 20) {
            double sma20 = htfBars.subList(htfBars.size() - 20, htfBars.size())
                    .stream().mapToDouble(OHLCV::getClose).average().orElse(curClose);
            htfTrend = curClose > sma20 * 1.005 ? "up"
                     : curClose < sma20 * 0.995 ? "down"
                     : "neutral";
        }

        // ── Step 1: Identify key levels from HTF bars ─────────────────────────
        // lev[0] = price, lev[1] = touch count, lev[2] = +1 (resistance) or -1 (support)
        List<double[]> levels = findKeyLevels(htfBars, curClose);
        if (levels.isEmpty()) return result;

        // ── Step 2: Check for rejection at the nearest level ──────────────────
        for (double[] lev : levels) {
            double levelPrice   = lev[0];
            int    touches      = (int) lev[1];
            boolean isResistance = lev[2] > 0;

            // Only act on levels that are genuinely close to current price
            double distPct = Math.abs(curClose - levelPrice) / levelPrice;
            if (distPct > TOUCH_TOLERANCE * 3) continue;

            if (isResistance) {
                // ── SHORT: price touched resistance and is rejecting downward ──
                // Vector check: the MAJORITY of today's session must have traded BELOW the level.
                // A genuine resistance test has price mostly below the level then spiking up to it.
                // A breakdown-from-above (e.g. GLD 03/24: opened $410, level $403.78, price dropped
                // through it — most session bars were ABOVE the level) fails this check.
                // Symmetric with the LONG approachingFromAbove check (35% threshold).
                boolean approachingFromBelow = false;
                if (sessionBars.size() >= 4) {
                    int totalBefore = sessionBars.size() - 1;
                    int belowCount = 0;
                    for (int bi = 0; bi < totalBefore; bi++) {
                        if (sessionBars.get(bi).getClose() < levelPrice) belowCount++;
                    }
                    approachingFromBelow = belowCount >= totalBefore * 0.35;
                }
                if (!approachingFromBelow) continue; // price arrived from wrong side — skip

                boolean touched      = curHigh >= levelPrice * (1 - TOUCH_TOLERANCE);
                boolean rejectedDown = curClose <= levelPrice * (1 + TOUCH_TOLERANCE * 0.3);
                // ── 2-bar confirmation: if previous bar was the rejection candle, use it ──
                OHLCV shortConfirm = last;
                if (sessionBars.size() >= 2) {
                    OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                    if (prev.getClose() < prev.getOpen() && prev.getVolume() > avgVol * 1.2
                            && prev.getHigh() >= levelPrice * (1 - TOUCH_TOLERANCE)
                            && last.getClose() <= prev.getClose() * 1.002) {
                        shortConfirm = prev;
                        touched = true; // prev bar touched the level
                    }
                }
                boolean bearishBar   = shortConfirm.getClose() < shortConfirm.getOpen();
                boolean upperWick    = (shortConfirm.getHigh() - shortConfirm.getClose()) > atr * 0.15;
                boolean volConfirmed = shortConfirm.getVolume() > avgVol * 1.2;
                // Counter-trend flag: shorting into an uptrend reduces conviction
                boolean counterTrend = "up".equals(htfTrend);

                if (touched && rejectedDown && bearishBar && volConfirmed) {
                    double entry = r4(curClose);
                    double sl    = r4(levelPrice + atr * slMult);
                    // Safety guard: sl must be above entry for a short
                    if (sl <= entry) sl = r4(entry + atr * slMult);
                    // TP = tpRatio:1 R:R (news-aligned extension to 3:1 applied later)
                    double tp    = r4(entry - (sl - entry) * tpRatio);

                    if (sl > entry && tp < entry) {
                        double risk   = sl - entry;
                        double reward = entry - tp;
                        double rr     = risk > 0 ? reward / risk : 0.0;

                        if (rr >= tpRatio * 0.95) {
                            int confidence = 65;
                            if (touches >= 3)                         confidence += 10;
                            if (touches >= 4)                         confidence += 5;
                            if (upperWick)                            confidence += 5;
                            if (last.getVolume() > avgVol * 2.0)     confidence += 5;
                            if (counterTrend)                         confidence -= 8;

                            double volRatio = avgVol > 0 ? last.getVolume() / avgVol : 1.0;
                            String factors = String.format(
                                    "keylevel-short | level=$%.2f | touches=%d | type=RESISTANCE" +
                                    " | wick=%s | vol=%.1f×avg | trend=%s | R:R=%.1f",
                                    levelPrice, touches,
                                    upperWick ? "✓" : "✗",
                                    volRatio, htfTrend.toUpperCase(), rr);

                            result.add(TradeSetup.builder()
                                    .ticker(ticker)
                                    .direction("short")
                                    .entry(entry)
                                    .stopLoss(sl)
                                    .takeProfit(tp)
                                    .confidence(confidence)
                                    .session("NYSE")
                                    .volatility("keylevel")
                                    .atr(atr)
                                    .hasBos(false)
                                    .hasChoch(false)
                                    .fvgTop(r4(levelPrice))
                                    .fvgBottom(r4(levelPrice - atr))
                                    .factorBreakdown(factors)
                                    .timestamp(LocalDateTime.now())
                                    .build());
                            return result;
                        }
                    }
                }

            } else {
                // ── LONG: price touched support and is bouncing upward ──────────
                // Vector check: price must be APPROACHING from above (not already below level).
                // At least 2 of the recent bars before this one should have been ABOVE
                // the level — confirming this is a fresh test from above, not a breakout
                // retest from below (which would be a SHORT setup).
                // Vector check: the MAJORITY of today's session must have traded ABOVE the level.
                // A genuine pullback-to-support has price mostly above the level then dipping to it.
                // A rally-from-below (like GLD 02/13: opened $458, level $460, only ~18% of bars
                // above level before the 11:25 entry) fails this check — only a small fraction of
                // bars were above because price spent the morning climbing UP through the level.
                // This is ATR-independent and not fooled by overnight micro-gaps.
                boolean approachingFromAbove = false;
                if (sessionBars.size() >= 4) {
                    int totalBefore = sessionBars.size() - 1;
                    int aboveCount = 0;
                    for (int bi = 0; bi < totalBefore; bi++) {
                        if (sessionBars.get(bi).getClose() > levelPrice) aboveCount++;
                    }
                    approachingFromAbove = aboveCount >= totalBefore * 0.35;
                }
                if (!approachingFromAbove) continue; // price arrived from wrong side — skip

                boolean touched      = curLow <= levelPrice * (1 + TOUCH_TOLERANCE);
                boolean bouncedUp    = curClose >= levelPrice * (1 - TOUCH_TOLERANCE * 0.3);
                // ── 2-bar confirmation: if previous bar was the bounce candle, use it ──
                OHLCV longConfirm = last;
                if (sessionBars.size() >= 2) {
                    OHLCV prev = sessionBars.get(sessionBars.size() - 2);
                    if (prev.getClose() > prev.getOpen() && prev.getVolume() > avgVol * 1.2
                            && prev.getLow() <= levelPrice * (1 + TOUCH_TOLERANCE)
                            && last.getClose() >= prev.getClose() * 0.998) {
                        longConfirm = prev;
                        touched = true; // prev bar touched the level
                    }
                }
                boolean bullishBar   = longConfirm.getClose() > longConfirm.getOpen();
                boolean lowerWick    = (longConfirm.getClose() - longConfirm.getLow()) > atr * 0.15;
                boolean volConfirmed = longConfirm.getVolume() > avgVol * 1.2;
                // Counter-trend flag: buying into a downtrend reduces conviction
                boolean counterTrend = "down".equals(htfTrend);

                if (touched && bouncedUp && bullishBar && volConfirmed) {
                    double entry = r4(curClose);
                    double sl    = r4(levelPrice - atr * slMult);
                    // Safety guard: sl must be below entry for a long
                    if (sl >= entry) sl = r4(entry - atr * slMult);
                    // TP = tpRatio:1 R:R (news-aligned extension to 3:1 applied later)
                    double tp    = r4(entry + (entry - sl) * tpRatio);

                    if (sl < entry && tp > entry) {
                        double risk   = entry - sl;
                        double reward = tp - entry;
                        double rr     = risk > 0 ? reward / risk : 0.0;

                        if (rr >= tpRatio * 0.95) {
                            int confidence = 65;
                            if (touches >= 3)                         confidence += 10;
                            if (touches >= 4)                         confidence += 5;
                            if (lowerWick)                            confidence += 5;
                            if (last.getVolume() > avgVol * 2.0)     confidence += 5;
                            if (counterTrend)                         confidence -= 8;

                            double volRatio = avgVol > 0 ? last.getVolume() / avgVol : 1.0;
                            String factors = String.format(
                                    "keylevel-long | level=$%.2f | touches=%d | type=SUPPORT" +
                                    " | wick=%s | vol=%.1f×avg | trend=%s | R:R=%.1f",
                                    levelPrice, touches,
                                    lowerWick ? "✓" : "✗",
                                    volRatio, htfTrend.toUpperCase(), rr);

                            result.add(TradeSetup.builder()
                                    .ticker(ticker)
                                    .direction("long")
                                    .entry(entry)
                                    .stopLoss(sl)
                                    .takeProfit(tp)
                                    .confidence(confidence)
                                    .session("NYSE")
                                    .volatility("keylevel")
                                    .atr(atr)
                                    .hasBos(false)
                                    .hasChoch(false)
                                    .fvgTop(r4(levelPrice + atr))
                                    .fvgBottom(r4(levelPrice))
                                    .factorBreakdown(factors)
                                    .timestamp(LocalDateTime.now())
                                    .build());
                            return result;
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Find key horizontal levels from HTF (daily) bars.
     *
     * Algorithm — rejection-wick density (not strict pivots):
     *  A price acts as RESISTANCE if a bar's high was near that price AND price
     *  closed well below it (upper wick / rejection). Multiple bars showing this
     *  pattern at the same price cluster = a real supply zone.
     *
     *  A price acts as SUPPORT if a bar's low was near that price AND price closed
     *  well above it (lower wick / bounce).
     *
     *  This approach captures flat-top/flat-bottom patterns like AAPL $255-256
     *  where consecutive days touch the same level but none is a strict pivot high
     *  (because the adjacent bars have similar highs).
     *
     * @param htf      daily bars (or 60m bars) — exclude the very last bar (current)
     * @param curClose current 5m price for proximity filtering
     */
    private List<double[]> findKeyLevels(List<OHLCV> htf, double curClose) {
        List<Double> touchHighs = new ArrayList<>();
        List<Double> touchLows  = new ArrayList<>();

        // Use last 60 daily bars (≈3 months) — recent enough to be actionable
        int start = Math.max(0, htf.size() - 60);
        // Exclude the very last HTF bar; it may be incomplete (current day)
        int end = htf.size() - 1;

        for (int i = start; i < end; i++) {
            OHLCV bar = htf.get(i);
            double h = bar.getHigh();
            double l = bar.getLow();
            double c = bar.getClose();

            // HIGH qualifies as a resistance touch if close < high - 0.3% of high
            // (upper wick = price was rejected from the high)
            if (c < h * (1 - 0.003)) {
                touchHighs.add(h);
            }

            // LOW qualifies as a support touch if close > low + 0.3% of low
            // (lower wick = price bounced from the low)
            if (c > l * (1 + 0.003)) {
                touchLows.add(l);
            }
        }

        List<double[]> result = new ArrayList<>();
        result.addAll(clusterLevels(touchHighs, curClose, +1.0)); // resistance
        result.addAll(clusterLevels(touchLows,  curClose, -1.0)); // support

        // Sort ascending by distance from current price (nearest first)
        result.sort((a, b) -> Double.compare(
                Math.abs(a[0] - curClose),
                Math.abs(b[0] - curClose)));
        return result;
    }

    /**
     * Cluster a list of price points and return valid levels (min touches met).
     * Each cluster's representative price is the average of its members.
     */
    private List<double[]> clusterLevels(List<Double> points, double curClose, double type) {
        List<double[]> clusters = new ArrayList<>();
        boolean[] used = new boolean[points.size()];

        for (int i = 0; i < points.size(); i++) {
            if (used[i]) continue;
            double p = points.get(i);

            // Only consider levels within 8% of current price (relevant range)
            if (Math.abs(p - curClose) / curClose > 0.08) {
                used[i] = true;
                continue;
            }

            List<Double> cluster = new ArrayList<>();
            cluster.add(p);
            used[i] = true;

            for (int j = i + 1; j < points.size(); j++) {
                if (!used[j] && Math.abs(points.get(j) - p) / p <= LEVEL_TOLERANCE) {
                    cluster.add(points.get(j));
                    used[j] = true;
                }
            }

            if (cluster.size() >= MIN_TOUCHES) {
                double avg = cluster.stream().mapToDouble(Double::doubleValue).average().orElse(p);
                clusters.add(new double[]{avg, cluster.size(), type});
            }
        }
        return clusters;
    }

    /** Compute 14-bar simple ATR. Requires at least 5 bars for meaningful result. */
    private double computeAtr(List<OHLCV> bars) {
        if (bars.size() < 6) return 0.0; // need 5+ true range samples
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0.0;
        int start = bars.size() - period - 1;
        double sum = 0.0;
        for (int i = start + 1; i < bars.size(); i++) {
            OHLCV cur  = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr  = Math.max(cur.getHigh() - cur.getLow(),
                         Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                  Math.abs(cur.getLow()  - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    /** Round to 4 decimal places. */
    private double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
