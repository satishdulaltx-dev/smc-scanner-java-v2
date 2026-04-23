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
 * Opening Range VWAP Detector — 9:30–10:30 ET only.
 *
 * Catches the flush-and-recover pattern that the regular ScalpMomentumDetector
 * misses because:
 *   1. VWAP SD bands are too narrow early in session (TP = $0.20 away)
 *   2. SwingLowSl anchors to the distant session low (violates max-risk check)
 *   3. Volume ratio unreliable vs. a 6-bar average that includes the opening spike
 *
 * Setup: Opening bar makes a thrust AWAY from VWAP (gap fill direction OR
 * opening drive). Within the first hour, price returns to VWAP and the first
 * bar to close back on the correct side = entry.
 *
 * SL: bar's own low/high + 0.20×dailyATR (tight, not session swing low)
 * TP:  max(entry ± dailyATR × 0.30, entry ± risk × 2.0)  — meaningful target
 * No volume filter — opening volume distribution is too uneven to use reliably.
 */
@Service
public class OpeningRangeVwapDetector {
    private static final Logger log = LoggerFactory.getLogger(OpeningRangeVwapDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final LocalTime OR_START  = LocalTime.of(9,  35); // skip the chaotic first bar
    private static final LocalTime OR_END    = LocalTime.of(10, 30); // opening range window
    private static final double    MIN_TP_PCT = 0.0035;              // 0.35% min TP (options viability)
    private static final double    MIN_SL_PCT = 0.0012;              // 0.12% min SL
    private static final double    MAX_RISK_DAILY_ATR = 0.40;        // SL <= 40% of daily ATR

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
        if (session.size() < 3) return result;

        OHLCV last = session.get(session.size() - 1);
        LocalTime now = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalTime();

        // Only fire inside the opening range window
        if (now.isBefore(OR_START) || now.isAfter(OR_END)) return result;

        int n = session.size();

        // ── Running VWAP up to current bar ────────────────────────────────────
        double[] vwapArr = computeVwap(session);
        double vwap = vwapArr[n - 1];

        // ── Determine opening thrust direction ────────────────────────────────
        // Look at the aggregate move of the first 1-3 bars vs VWAP.
        // If early bars were mostly BELOW vwap → opening was bearish → recovery = LONG
        // If early bars were mostly ABOVE vwap → opening was bullish → failure = SHORT
        int earlyBars = Math.min(3, n - 1);
        int belowCount = 0, aboveCount = 0;
        for (int i = 0; i < earlyBars; i++) {
            double earlyVwap = vwapArr[i];
            OHLCV b = session.get(i);
            if (b.getClose() < earlyVwap) belowCount++;
            else aboveCount++;
        }
        // Also look at absolute open-to-current move for confirmation
        double openPrice    = session.get(0).getOpen();
        double sessionLow   = session.stream().mapToDouble(OHLCV::getLow).min().orElse(openPrice);
        double sessionHigh  = session.stream().mapToDouble(OHLCV::getHigh).max().orElse(openPrice);
        double openFlushDown = (openPrice - sessionLow)  / openPrice; // how far below open did we flush
        double openFlushUp   = (sessionHigh - openPrice) / openPrice; // how far above open did we spike

        // Require a meaningful opening move so we're not trading noise (>= 0.25%)
        boolean hadOpenFlushDown = openFlushDown >= 0.0025 || belowCount >= 2;
        boolean hadOpenFlushUp   = openFlushUp   >= 0.0025 || aboveCount >= 2;

        OHLCV prev = session.get(n - 2);
        double prevVwap = vwapArr[n - 2];

        boolean lastGreen = last.getClose() > last.getOpen();
        boolean lastRed   = last.getClose() < last.getOpen();
        double  body      = Math.abs(last.getClose() - last.getOpen())
                          / Math.max(last.getHigh() - last.getLow(), 0.001);

        // ── LONG: price was below VWAP, now closing back above ────────────────
        boolean longSetup = hadOpenFlushDown
                && prev.getClose() < prevVwap          // prior bar was below VWAP
                && last.getLow()   <= vwap * 1.002     // current bar touched VWAP zone
                && last.getClose() > vwap              // closed back above VWAP
                && lastGreen
                && body >= 0.35;

        // ── SHORT: price was above VWAP, now closing back below ───────────────
        boolean shortSetup = hadOpenFlushUp
                && prev.getClose() > prevVwap          // prior bar was above VWAP
                && last.getHigh()  >= vwap * 0.998     // current bar touched VWAP zone
                && last.getClose() < vwap              // closed back below VWAP
                && lastRed
                && body >= 0.35;

        if (!longSetup && !shortSetup) return result;

        boolean isLong = longSetup;
        double  entry  = round4(last.getClose());
        double  atrRef = dailyAtr > 0 ? dailyAtr : estimateAtr(session);

        // ── SL: bar's own extreme + 0.20×atr ─────────────────────────────────
        double stop;
        if (isLong) {
            stop = round4(last.getLow() - atrRef * 0.20);
        } else {
            stop = round4(last.getHigh() + atrRef * 0.20);
        }

        double risk = Math.abs(entry - stop);
        if (risk <= 0) return result;

        // Cap: no more than 40% of daily ATR as risk (keeps options viable)
        if (atrRef > 0 && risk > atrRef * MAX_RISK_DAILY_ATR) {
            log.debug("{} OR-VWAP filtered — risk {} > {} (40% dailyATR)",
                    ticker, String.format("%.2f", risk), String.format("%.2f", atrRef * MAX_RISK_DAILY_ATR));
            return result;
        }

        // ── TP: larger of 2R or 30% of daily ATR ─────────────────────────────
        double tp;
        double tpMinMove = Math.max(risk * 2.0, atrRef * 0.30);
        if (isLong) {
            tp = round4(entry + tpMinMove);
        } else {
            tp = round4(entry - tpMinMove);
        }

        // Options viability gate (same as ScalpMomentumDetector)
        double tpPct = Math.abs(tp   - entry) / entry;
        double slPct = Math.abs(stop - entry) / entry;
        if (tpPct < MIN_TP_PCT || slPct < MIN_SL_PCT) {
            log.debug("{} OR-VWAP filtered — TP {}% SL {}% too tight for options",
                    ticker, String.format("%.2f", tpPct * 100), String.format("%.2f", slPct * 100));
            return result;
        }

        // ── Confidence ────────────────────────────────────────────────────────
        int confidence = 72;

        // Strong body = conviction bar
        if (body >= 0.65) confidence += 5;
        else if (body >= 0.50) confidence += 2;

        // Bigger opening flush = more trapped traders = bigger recovery
        double flushSize = isLong ? openFlushDown : openFlushUp;
        if (flushSize >= 0.008) confidence += 6; // 0.8%+ flush
        else if (flushSize >= 0.005) confidence += 3; // 0.5%+ flush

        // Earlier in the window = cleaner (first VWAP touch after flush)
        if (now.isBefore(LocalTime.of(9, 55)))  confidence += 5;
        else if (now.isBefore(LocalTime.of(10, 15))) confidence += 2;

        // If the prior bar was also showing a recovery attempt (wick toward VWAP)
        if (isLong && prev.getLow() <= vwap * 1.003) confidence += 3;
        if (!isLong && prev.getHigh() >= vwap * 0.997) confidence += 3;

        double rr  = Math.abs(tp - entry) / risk;
        String factors = String.format(
                "or-vwap | %s | entry=%.2f vwap=%.2f | flush=%.2f%% | body=%.0f%% | risk=%.2f tp=%.2f (R:R %.1f) | dailyATR=%.2f",
                isLong ? "LONG" : "SHORT", entry, vwap,
                flushSize * 100, body * 100,
                risk, Math.abs(tp - entry), rr, atrRef);

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

        log.info("{} OR-VWAP {} conf={} entry={} sl={} tp={} flush={}%",
                ticker, isLong ? "LONG" : "SHORT", confidence, entry, stop, tp,
                String.format("%.2f", flushSize * 100));

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
