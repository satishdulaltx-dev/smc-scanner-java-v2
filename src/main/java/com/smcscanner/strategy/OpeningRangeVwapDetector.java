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
 * Session VWAP Bounce Detector — fires throughout the day aligned with daily bias.
 *
 * The regular ScalpMomentumDetector misses these setups because:
 *   1. VWAP SD bands are too narrow to give a meaningful TP target
 *   2. SwingLowSl after an opening flush exceeds the ATR×1.5 risk cap
 *   3. Volume ratio is unreliable vs. a short lookback avg
 *
 * Core logic:
 *   1. After the first 7 bars (~10:05 ET) determine session bias:
 *      - price above VWAP at bar 7 → bullish day → only LONG setups
 *      - price below VWAP at bar 7 → bearish day → only SHORT setups
 *   2. Throughout the day (9:35–14:30, dead zone 11:30–13:30 skipped)
 *      look for the FIRST bar that touches VWAP and closes back on the
 *      bias side with a decent body.
 *   3. SL = bar's own extreme + 0.20×dailyATR (not the distant session low)
 *   4. TP = max(2R, dailyATR×0.30) — meaningful move for options
 */
@Service
public class OpeningRangeVwapDetector {
    private static final Logger log = LoggerFactory.getLogger(OpeningRangeVwapDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final LocalTime SESSION_START  = LocalTime.of(9,  35);
    private static final LocalTime DEAD_ZONE_START = LocalTime.of(11, 30);
    private static final LocalTime DEAD_ZONE_END   = LocalTime.of(13, 30);
    private static final LocalTime SESSION_END     = LocalTime.of(14, 30);

    // Minimum bars before we trust the session bias (~10:05 ET on 5m bars)
    private static final int  BIAS_LOCK_BARS = 7;

    private static final double MIN_TP_PCT         = 0.0035; // 0.35% — options viability
    private static final double MIN_SL_PCT         = 0.0012; // 0.12%
    private static final double MAX_RISK_DAILY_ATR = 0.40;   // SL ≤ 40% daily ATR

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 5) return result;

        // ── Build today's session bars ────────────────────────────────────────
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp()).atZone(ET).toLocalDate();

        if (!backtestMode && !today.equals(LocalDate.now(ET))) return result;

        List<OHLCV> session = new ArrayList<>();
        for (OHLCV b : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET);
            LocalTime t = zdt.toLocalTime();
            if (zdt.toLocalDate().equals(today)
                    && !t.isBefore(LocalTime.of(9, 30))
                    && t.isBefore(LocalTime.of(16, 0))) {
                session.add(b);
            }
        }
        if (session.size() < BIAS_LOCK_BARS + 1) return result;

        OHLCV last = session.get(session.size() - 1);
        LocalTime now = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalTime();

        // Time gate: active windows only
        if (now.isBefore(SESSION_START)) return result;
        if (now.isAfter(SESSION_END)) return result;
        if (!now.isBefore(DEAD_ZONE_START) && now.isBefore(DEAD_ZONE_END)) return result;

        int n = session.size();

        // ── Running VWAP ──────────────────────────────────────────────────────
        double[] vwapArr = computeVwap(session);
        double vwap = vwapArr[n - 1];

        // ── Session bias: determined from bar 7 (~10:05 ET) ──────────────────
        // After the first 35 min, price position relative to VWAP is a reliable
        // indicator of the day's direction. Early open (< 7 bars) is too noisy.
        int biasIdx = Math.min(BIAS_LOCK_BARS, n - 2);
        OHLCV biasBar = session.get(biasIdx);
        double biasVwap = vwapArr[biasIdx];
        boolean sessionBullish = biasBar.getClose() > biasVwap;
        boolean sessionBearish = biasBar.getClose() < biasVwap;

        // Require clear bias (not hugging VWAP — within 0.05% is ambiguous)
        double biasPct = Math.abs(biasBar.getClose() - biasVwap) / biasVwap;
        if (biasPct < 0.0005) return result; // ambiguous day, skip

        // ── VWAP touch: current or previous bar entered VWAP zone ─────────────
        OHLCV prev  = session.get(n - 2);
        double prevVwap = vwapArr[n - 2];
        double atrRef   = dailyAtr > 0 ? dailyAtr : estimateAtr(session);
        double touch    = atrRef * 0.10; // within 10% of ATR = "at VWAP"

        boolean lastGreen = last.getClose() > last.getOpen();
        boolean lastRed   = last.getClose() < last.getOpen();
        double  body      = Math.abs(last.getClose() - last.getOpen())
                          / Math.max(last.getHigh() - last.getLow(), 0.001);

        // ── LONG: bullish day, price dipped to VWAP, closed back above ────────
        boolean longSetup = sessionBullish
                && (last.getLow() <= vwap + touch || prev.getLow() <= prevVwap + touch)
                && last.getClose() > vwap
                && lastGreen
                && body >= 0.35;

        // ── SHORT: bearish day, price bounced to VWAP, closed back below ──────
        boolean shortSetup = sessionBearish
                && (last.getHigh() >= vwap - touch || prev.getHigh() >= prevVwap - touch)
                && last.getClose() < vwap
                && lastRed
                && body >= 0.35;

        // Only fire once per session — if price has repeatedly been at VWAP in the
        // last 8 bars it's just ranging, not a clean first-touch bounce
        if (longSetup || shortSetup) {
            int vwapCrossings = 0;
            int lookback = Math.min(8, n - 1);
            for (int i = n - lookback; i < n - 1; i++) {
                double bVwap = vwapArr[i];
                OHLCV b = session.get(i), bNext = session.get(i + 1);
                if (b.getClose() > bVwap && bNext.getClose() < vwapArr[i + 1]) vwapCrossings++;
                if (b.getClose() < bVwap && bNext.getClose() > vwapArr[i + 1]) vwapCrossings++;
            }
            // More than 2 crossings in last 8 bars = choppy, not a clean bounce
            if (vwapCrossings > 2) return result;
        }

        if (!longSetup && !shortSetup) return result;

        boolean isLong = longSetup;
        double  entry  = round4(last.getClose());

        // ── SL: bar's own extreme + 0.20×dailyATR ────────────────────────────
        double stop = isLong
                ? round4(last.getLow()  - atrRef * 0.20)
                : round4(last.getHigh() + atrRef * 0.20);

        double risk = Math.abs(entry - stop);
        if (risk <= 0) return result;
        if (atrRef > 0 && risk > atrRef * MAX_RISK_DAILY_ATR) {
            log.debug("{} OR-VWAP risk {} > 40% dailyATR — skip", ticker, String.format("%.2f", risk));
            return result;
        }

        // ── TP: max(2R, 30% of dailyATR) ─────────────────────────────────────
        double tpMove = Math.max(risk * 2.0, atrRef * 0.30);
        double tp = isLong ? round4(entry + tpMove) : round4(entry - tpMove);

        double tpPct = Math.abs(tp   - entry) / entry;
        double slPct = Math.abs(stop - entry) / entry;
        if (tpPct < MIN_TP_PCT || slPct < MIN_SL_PCT) return result;

        // ── Confidence ────────────────────────────────────────────────────────
        int confidence = 70;

        if (body >= 0.65)       confidence += 5;
        else if (body >= 0.50)  confidence += 2;

        // Larger bias gap = stronger trend = more conviction on the bounce
        if (biasPct >= 0.005)      confidence += 6;
        else if (biasPct >= 0.002) confidence += 3;

        // Morning session = stronger momentum
        if (now.isBefore(LocalTime.of(10, 30))) confidence += 5;
        else if (now.isBefore(LocalTime.of(11, 30))) confidence += 2;

        // Prior bar also touching VWAP = confirmed level
        if (isLong  && prev.getLow()  <= prevVwap + touch) confidence += 3;
        if (!isLong && prev.getHigh() >= prevVwap - touch) confidence += 3;

        // Opening flush size (how hard the initial move was away from open)
        double openPrice = session.get(0).getOpen();
        double flushSize = isLong
                ? (openPrice - session.stream().mapToDouble(OHLCV::getLow).min().orElse(openPrice)) / openPrice
                : (session.stream().mapToDouble(OHLCV::getHigh).max().orElse(openPrice) - openPrice) / openPrice;
        if (flushSize >= 0.008) confidence += 5;
        else if (flushSize >= 0.004) confidence += 2;

        double rr = tpMove / risk;
        String factors = String.format(
                "or-vwap | %s | entry=%.2f vwap=%.2f | biasPct=%.2f%% %s | body=%.0f%% | R:R %.1f | dailyATR=%.2f",
                isLong ? "LONG" : "SHORT", entry, vwap,
                biasPct * 100, sessionBullish ? "BULL" : "BEAR",
                body * 100, rr, atrRef);

        result.add(TradeSetup.builder()
                .ticker(ticker)
                .direction(isLong ? "long" : "short")
                .entry(entry)
                .stopLoss(stop)
                .takeProfit(tp)
                .confidence(confidence)
                .session("NYSE")
                .volatility("or-vwap")
                .atr(round4(atrRef))
                .hasBos(false)
                .hasChoch(false)
                .factorBreakdown(factors)
                .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                .build());

        log.info("{} OR-VWAP {} conf={} entry={} sl={} tp={} bias={}% {}",
                ticker, isLong ? "LONG" : "SHORT", confidence, entry, stop, tp,
                String.format("%.2f", biasPct * 100), sessionBullish ? "BULL" : "BEAR");

        return result;
    }

    private double[] computeVwap(List<OHLCV> bars) {
        double sumPV = 0, sumV = 0;
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            OHLCV b = bars.get(i);
            double tp = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
            sumPV += tp * b.getVolume();
            sumV  += b.getVolume();
            out[i] = sumV > 0 ? sumPV / sumV : tp;
        }
        return out;
    }

    private double estimateAtr(List<OHLCV> bars) {
        int p = Math.min(8, bars.size() - 1);
        if (p <= 0) return 0.5;
        double sum = 0;
        for (int i = bars.size() - p; i < bars.size(); i++) {
            OHLCV c = bars.get(i), pv = bars.get(i - 1);
            sum += Math.max(c.getHigh() - c.getLow(),
                   Math.max(Math.abs(c.getHigh() - pv.getClose()),
                            Math.abs(c.getLow()  - pv.getClose())));
        }
        return sum / p;
    }

    private double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
