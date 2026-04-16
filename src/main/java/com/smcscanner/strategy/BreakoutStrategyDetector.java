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
 * Opening Range Breakout (ORB) strategy detector for volatile momentum stocks.
 * Defines the opening range as the first 6 session bars (roughly 9:30–9:55 AM ET),
 * then looks for a confirmed breakout/breakdown with volume confirmation.
 */
@Service
public class BreakoutStrategyDetector {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * Detect ORB setups for the given bars.
     *
     * @param bars      5-minute OHLCV bars (100+ bars typical)
     * @param ticker    ticker symbol
     * @param dailyAtr  daily ATR from calling service (unused directly, kept for consistent signature)
     * @return list of detected setups (0 or 1 element)
     */
    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.isEmpty()) return result;

        // Filter to regular NYSE session only: same date AND 9:30 AM–4:00 PM ET
        // ORB must use true market-open bars; pre-market bars produce wrong opening ranges
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp())
                .atZone(ET).toLocalDate();
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

        // Need ORB period (6 bars) + at least 1 post-ORB bar to check for breakout
        if (sessionBars.size() < 7) return result;

        // Opening range = first 6 session bars
        List<OHLCV> orbBars = sessionBars.subList(0, 6);
        double orbHigh = orbBars.stream().mapToDouble(OHLCV::getHigh).max().orElse(0.0);
        double orbLow  = orbBars.stream().mapToDouble(OHLCV::getLow).min().orElse(0.0);
        double orbWidth = orbHigh - orbLow;
        if (orbWidth <= 0) return result;

        // Still in ORB formation — need at least 1 post-ORB bar
        if (sessionBars.size() <= 6) return result; // redundant safety check

        // Average volume of ALL session bars (not just ORB bars — opening candle inflates ORB avg)
        double avgVol = sessionBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        OHLCV last = sessionBars.get(sessionBars.size() - 1);

        // ── RVOL gate — mirrors VwapStrategyDetector ──────────────────────────
        // ORB breakouts on thin volume are false breaks that reverse immediately.
        // Hard gate when RVOL < 0.8 on a mature session (≥ 20 bars).
        double sessionRvol = avgVol > 0 ? last.getVolume() / avgVol : 1.0;
        if (sessionBars.size() >= 20 && sessionRvol < 0.8) return result;

        // ── 2-bar confirmation window ────────────────────────────────────────
        // Check last 2 bars for breakout criteria. If the breakout bar was the
        // previous one (big candle + volume), the current bar is confirmation.
        OHLCV breakoutBar = last;
        if (sessionBars.size() >= 2) {
            OHLCV prev = sessionBars.get(sessionBars.size() - 2);
            boolean prevVol  = prev.getVolume() > avgVol * 1.5;
            boolean prevSize = (prev.getHigh() - prev.getLow()) > orbWidth * 0.3;
            // If prev bar had the breakout characteristics AND current bar holds direction
            if (prevVol && prevSize) {
                boolean prevBrokeUp   = prev.getClose() > orbHigh && prev.getClose() >= prev.getOpen();
                boolean prevBrokeDown = prev.getClose() < orbLow  && prev.getClose() <= prev.getOpen();
                if ((prevBrokeUp && last.getClose() >= prev.getClose() * 0.998)
                        || (prevBrokeDown && last.getClose() <= prev.getClose() * 1.002)) {
                    breakoutBar = prev;
                }
            }
        }

        // Breakout bar validation (common to both directions)
        boolean volConfirmed  = breakoutBar.getVolume() > avgVol * 1.5;
        boolean sizeConfirmed = (breakoutBar.getHigh() - breakoutBar.getLow()) > orbWidth * 0.3;

        if (!volConfirmed || !sizeConfirmed) return result;

        double atr = computeAtr(sessionBars);

        // ── Narrow range filter ─────────────────────────────────────────────────
        // Wide opening ranges (> 1.5x ATR) mean 60%+ of the daily move already
        // happened in the first 30 min. Breakouts from wide ORBs get faded.
        // Tight ORBs (< 0.8x ATR) lead to explosive breakouts — that's the edge.
        if (orbWidth > atr * 2.0) return result; // extremely wide — no trade
        boolean wideOrb = orbWidth > atr * 1.2;  // moderately wide — penalize later

        // Freshness check: scan ALL post-ORB bars before the current one to ensure
        // this is truly the first breakout (works correctly in backtest and live mode)
        boolean prevBrokeHigh = false, prevBrokeLow = false;
        for (int i = 6; i < sessionBars.size() - 1; i++) {
            double c = sessionBars.get(i).getClose();
            if (c > orbHigh) prevBrokeHigh = true;
            if (c < orbLow)  prevBrokeLow  = true;
        }

        // ── LONG breakout ─────────────────────────────────────────────────────
        if (!prevBrokeHigh
                && last.getClose() > orbHigh
                && last.getClose() >= last.getOpen()) { // >= handles doji bars

            double entry = r4(last.getClose());
            double sl    = r4(orbLow + orbWidth * 0.15);
            // Safety guard: sl must be below entry for a long
            if (sl >= entry) sl = r4(entry - orbWidth * 0.15);
            // TP = exactly 1.5:1 R:R (news-aligned extension to 3:1 applied later)
            double tp    = r4(entry + (entry - sl) * 2.5);  // 2.5:1 R:R

            if (sl < entry && tp > entry) {
                double risk   = entry - sl;
                double reward = tp - entry;
                double rr     = risk > 0 ? reward / risk : 0.0;

                if (rr >= 1.2) {
                    int confidence = 70;
                    if (last.getVolume() > avgVol * 2.5)  confidence += 5;
                    if (orbWidth < atr * 0.8)             confidence += 5;
                    if (wideOrb)                          confidence -= 10;
                    if (sessionRvol >= 1.5)               confidence += 5;

                    String orbSize = orbWidth < atr * 0.8 ? "tight" : wideOrb ? "wide" : "normal";
                    String factors = String.format(
                            "breakout-long | ORB=[%.2f/%.2f] | width=$%.2f(%s)" +
                            " | vol=%.1f×avg | RVOL=%.1f | R:R=%.1f",
                            orbLow, orbHigh, orbWidth, orbSize,
                            last.getVolume() / Math.max(avgVol, 1), sessionRvol, rr);

                    result.add(TradeSetup.builder()
                            .ticker(ticker)
                            .direction("long")
                            .entry(entry)
                            .stopLoss(sl)
                            .takeProfit(tp)
                            .confidence(confidence)
                            .session("NYSE")
                            .volatility("high")
                            .atr(atr)
                            .hasBos(false)
                            .hasChoch(false)
                            .fvgTop(r4(orbHigh))
                            .fvgBottom(r4(orbLow))
                            .factorBreakdown(factors)
                            .timestamp(LocalDateTime.now())
                            .build());
                    return result;
                }
            }
        }

        // ── SHORT breakdown ───────────────────────────────────────────────────
        if (!prevBrokeLow
                && last.getClose() < orbLow
                && last.getClose() <= last.getOpen()) { // <= handles doji bars

            double entry = r4(last.getClose());
            double sl    = r4(orbHigh - orbWidth * 0.15);
            // Safety guard: sl must be above entry for a short
            if (sl <= entry) sl = r4(entry + orbWidth * 0.15);
            // TP = exactly 1.5:1 R:R (news-aligned extension to 3:1 applied later)
            double tp    = r4(entry - (sl - entry) * 2.5);  // 2.5:1 R:R

            if (sl > entry && tp < entry) {
                double risk   = sl - entry;
                double reward = entry - tp;
                double rr     = risk > 0 ? reward / risk : 0.0;

                if (rr >= 1.2) {
                    int confidence = 70;
                    if (last.getVolume() > avgVol * 2.5)  confidence += 5;
                    if (orbWidth < atr * 0.8)             confidence += 5;
                    if (wideOrb)                          confidence -= 10;
                    if (sessionRvol >= 1.5)               confidence += 5;

                    String orbSize = orbWidth < atr * 0.8 ? "tight" : wideOrb ? "wide" : "normal";
                    String factors = String.format(
                            "breakout-short | ORB=[%.2f/%.2f] | width=$%.2f(%s)" +
                            " | vol=%.1f×avg | RVOL=%.1f | R:R=%.1f",
                            orbLow, orbHigh, orbWidth, orbSize,
                            last.getVolume() / Math.max(avgVol, 1), sessionRvol, rr);

                    result.add(TradeSetup.builder()
                            .ticker(ticker)
                            .direction("short")
                            .entry(entry)
                            .stopLoss(sl)
                            .takeProfit(tp)
                            .confidence(confidence)
                            .session("NYSE")
                            .volatility("high")
                            .atr(atr)
                            .hasBos(false)
                            .hasChoch(false)
                            .fvgTop(r4(orbHigh))
                            .fvgBottom(r4(orbLow))
                            .factorBreakdown(factors)
                            .timestamp(LocalDateTime.now())
                            .build());
                }
            }
        }

        return result;
    }

    /** Compute 14-bar simple ATR from the provided bars. */
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
