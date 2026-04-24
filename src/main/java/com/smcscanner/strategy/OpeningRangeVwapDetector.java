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
 * Two-mode VWAP detector:
 *
 * MODE A — Opening Flush Recovery (9:35–9:55 ET)
 *   Price dumps sharply in the first few bars (≥0.30% below open), then a
 *   bar closes back above VWAP with a decent body. No bias lock needed — the
 *   flush itself defines direction. Classic "morning capitulation" entry.
 *
 * MODE B — Session VWAP Bounce (10:00–14:30, skip 12:00–13:00 lunch)
 *   After the first 7 bars (~10:05), session bias is locked via price vs VWAP.
 *   Look for first clean touch-and-bounce aligned with that bias.
 *   Chop filter: >2 VWAP crossings in last 8 bars = ranging, skip.
 */
@Service
public class OpeningRangeVwapDetector {
    private static final Logger log = LoggerFactory.getLogger(OpeningRangeVwapDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Mode A window
    private static final LocalTime EARLY_START = LocalTime.of(9,  35);
    private static final LocalTime EARLY_END   = LocalTime.of(9,  55);

    // Mode B window (dead zone = strict lunch 12:00–13:00)
    private static final LocalTime REGULAR_START    = LocalTime.of(10,  0);
    private static final LocalTime DEAD_ZONE_START  = LocalTime.of(12,  0);
    private static final LocalTime DEAD_ZONE_END    = LocalTime.of(13,  0);
    private static final LocalTime SESSION_END      = LocalTime.of(14, 30);

    // Bias lock: bar 7 (~10:05 ET on 5m bars)
    private static final int    BIAS_LOCK_BARS   = 7;

    // Opening flush: price must drop ≥ 0.50% below open to qualify
    private static final double MIN_FLUSH_PCT    = 0.0050;

    // Sizing limits
    private static final double MIN_TP_PCT         = 0.0035; // 0.35% min TP for options
    private static final double MIN_SL_PCT         = 0.0012; // 0.12% min SL
    private static final double MAX_RISK_DAILY_ATR = 0.40;   // SL ≤ 40% daily ATR

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr, boolean backtestMode) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 3) return result;

        // Build today's session bars (9:30–16:00)
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
        if (session.size() < 2) return result;

        OHLCV last = session.get(session.size() - 1);
        LocalTime now = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalTime();

        // Global time gate
        if (now.isBefore(EARLY_START)) return result;
        if (now.isAfter(SESSION_END))  return result;

        int n = session.size();
        double[] vwapArr = computeVwap(session);
        double vwap      = vwapArr[n - 1];
        double atrRef    = dailyAtr > 0 ? dailyAtr : estimateAtr(session);
        double touch     = atrRef * 0.12; // within 12% of ATR = "at VWAP"

        boolean lastGreen = last.getClose() > last.getOpen();
        boolean lastRed   = last.getClose() < last.getOpen();
        double  body      = Math.abs(last.getClose() - last.getOpen())
                          / Math.max(last.getHigh() - last.getLow(), 0.001);

        boolean isLong;
        String  modeTag;

        // ── MODE A: Opening Flush Recovery (9:35–9:55), LONG only ───────────
        // Short-side opening flush (spike up + failure) is unreliable without
        // HTF bias confirmation. Only trade the flush-down → VWAP recovery pattern.
        if (!now.isAfter(EARLY_END)) {
            double openPrice  = session.get(0).getOpen();
            double sessionLow = session.stream().mapToDouble(OHLCV::getLow).min().orElse(openPrice);
            double flushDown  = (openPrice - sessionLow) / openPrice;

            // Bar must have tested the VWAP zone (low ≤ vwap + 1.5×touch)
            boolean testedVwap = last.getLow() <= vwap + touch * 1.5;

            // This must be the FIRST bar to close above VWAP since the session started.
            // If any prior bar already closed above VWAP, this is a second/third bounce
            // attempt (unreliable dead-cat pattern), not a clean flush recovery.
            boolean firstRecovery = true;
            for (int i = 0; i < n - 1; i++) {
                if (session.get(i).getClose() > vwapArr[i]) { firstRecovery = false; break; }
            }

            boolean flushLong = flushDown >= MIN_FLUSH_PCT
                    && testedVwap
                    && firstRecovery
                    && last.getClose() > vwap
                    && lastGreen && body >= 0.45;

            if (!flushLong) return result;

            isLong  = true;
            modeTag = "or-flush";

        // ── MODE B: Session VWAP Bounce (10:00–14:30, skip 12:00–13:00) ──────
        } else if (!now.isBefore(REGULAR_START) && !now.isAfter(SESSION_END)) {

            // Dead zone: skip strict lunch 12:00–13:00
            if (!now.isBefore(DEAD_ZONE_START) && now.isBefore(DEAD_ZONE_END)) return result;

            // Need enough bars for bias lock
            if (n < BIAS_LOCK_BARS + 1) return result;

            // Session bias from bar 7 (~10:05 ET)
            int    biasIdx  = Math.min(BIAS_LOCK_BARS, n - 2);
            OHLCV  biasBar  = session.get(biasIdx);
            double biasVwap = vwapArr[biasIdx];
            boolean sessionBullish = biasBar.getClose() > biasVwap;
            boolean sessionBearish = biasBar.getClose() < biasVwap;

            double biasPct = Math.abs(biasBar.getClose() - biasVwap) / biasVwap;
            if (biasPct < 0.0015) return result; // ambiguous day — raised from 0.05% to 0.15%

            OHLCV  prev     = session.get(n - 2);
            double prevVwap = vwapArr[n - 2];

            boolean longSetup = sessionBullish
                    && (last.getLow()  <= vwap + touch || prev.getLow()  <= prevVwap + touch)
                    && last.getClose() > vwap
                    && lastGreen && body >= 0.45;

            boolean shortSetup = sessionBearish
                    && (last.getHigh() >= vwap - touch || prev.getHigh() >= prevVwap - touch)
                    && last.getClose() < vwap
                    && lastRed  && body >= 0.45;

            // Chop filter: >2 crossings in last 8 bars = ranging
            if (longSetup || shortSetup) {
                int vwapCrossings = 0;
                int lookback = Math.min(8, n - 1);
                for (int i = n - lookback; i < n - 1; i++) {
                    OHLCV b = session.get(i), bNext = session.get(i + 1);
                    double bV = vwapArr[i], bVn = vwapArr[i + 1];
                    if (b.getClose() > bV  && bNext.getClose() < bVn) vwapCrossings++;
                    if (b.getClose() < bV  && bNext.getClose() > bVn) vwapCrossings++;
                }
                if (vwapCrossings > 1) return result;
            }

            if (!longSetup && !shortSetup) return result;

            isLong  = longSetup;
            modeTag = "or-bounce";

        } else {
            return result; // between EARLY_END (9:55) and REGULAR_START (10:00)
        }

        double entry = round4(last.getClose());

        // SL: bar's own extreme + 0.20×dailyATR buffer
        double stop = isLong
                ? round4(last.getLow()  - atrRef * 0.20)
                : round4(last.getHigh() + atrRef * 0.20);

        double risk = Math.abs(entry - stop);
        if (risk <= 0) return result;
        if (atrRef > 0 && risk > atrRef * MAX_RISK_DAILY_ATR) {
            log.debug("{} OR-VWAP risk {} > 40% dailyATR — skip", ticker, String.format("%.2f", risk));
            return result;
        }

        // TP: max(2R, 30% of dailyATR)
        double tpMove = Math.max(risk * 2.0, atrRef * 0.30);
        double tp = isLong ? round4(entry + tpMove) : round4(entry - tpMove);

        double tpPct = Math.abs(tp   - entry) / entry;
        double slPct = Math.abs(stop - entry) / entry;
        if (tpPct < MIN_TP_PCT || slPct < MIN_SL_PCT) return result;

        // Confidence
        int confidence = 65;

        if (body >= 0.65)      confidence += 5;
        else if (body >= 0.50) confidence += 2;

        if (now.isBefore(LocalTime.of(10, 30))) confidence += 5;
        else if (now.isBefore(LocalTime.of(11, 30))) confidence += 2;

        // Mode A bonus: larger flush = more conviction
        if ("or-flush".equals(modeTag)) {
            double openPrice = session.get(0).getOpen();
            double fl = isLong
                    ? (openPrice - session.stream().mapToDouble(OHLCV::getLow).min().orElse(openPrice)) / openPrice
                    : (session.stream().mapToDouble(OHLCV::getHigh).max().orElse(openPrice) - openPrice) / openPrice;
            if (fl >= 0.008) confidence += 8;
            else if (fl >= 0.004) confidence += 4;
        }

        // Mode B bonus: bias gap strength + prior bar also touching
        if ("or-bounce".equals(modeTag)) {
            int    biasIdx  = Math.min(BIAS_LOCK_BARS, n - 2);
            double biasVwap = vwapArr[biasIdx];
            double biasPct  = Math.abs(session.get(biasIdx).getClose() - biasVwap) / biasVwap;
            if (biasPct >= 0.005)      confidence += 6;
            else if (biasPct >= 0.002) confidence += 3;

            OHLCV prev = session.get(n - 2);
            double prevVwap = vwapArr[n - 2];
            if (isLong  && prev.getLow()  <= prevVwap + touch) confidence += 3;
            if (!isLong && prev.getHigh() >= prevVwap - touch) confidence += 3;
        }

        double rr = tpMove / risk;
        String factors = String.format(
                "or-vwap [%s] | %s | entry=%.2f vwap=%.2f | body=%.0f%% | R:R %.1f | dailyATR=%.2f",
                modeTag, isLong ? "LONG" : "SHORT", entry, vwap, body * 100, rr, atrRef);

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

        log.info("{} OR-VWAP [{}] {} conf={} entry={} sl={} tp={} body={}% vwap={}",
                ticker, modeTag, isLong ? "LONG" : "SHORT", confidence,
                entry, stop, tp, String.format("%.0f", body * 100), String.format("%.2f", vwap));

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

