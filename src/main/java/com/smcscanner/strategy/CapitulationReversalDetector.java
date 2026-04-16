package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Capitulation Reversal Detector — catches bounces after sharp waterfall sell-offs.
 *
 * Pattern (all four required):
 *   1. WATERFALL  — price drops ≥ 2.5% in ≤ 15 bars (consecutive selling pressure)
 *   2. CLIMAX     — highest-volume bar in the waterfall is a bearish bar (exhaustion)
 *                   with volume ≥ 2× session average
 *   3. REVERSAL   — within 5 bars of the low: hammer, bullish CHoCH, or engulfing candle
 *   4. FLOOR      — reversal bar closes ABOVE the climax bar's low (confirms the floor held)
 *
 * Levels:
 *   Entry  = reversal-bar close + 5 bps slippage
 *   Stop   = waterfall low − 0.25 × ATR  (hard floor below the low)
 *   Target = waterfall low + 50% retrace of the waterfall height
 *
 * Minimum R:R = 1.5. Confidence 70–88.
 *
 * Strategy key: "cap_reversal"
 * Can be assigned in ticker-profiles.json OR used as fallback when primary finds nothing
 * in a VOLATILE/HIGH-ATR regime.
 */
@Service
public class CapitulationReversalDetector {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Waterfall thresholds
    private static final double MIN_DROP_PCT    = 0.025;  // ≥2.5% price drop required
    private static final int    DROP_BARS       = 15;     // max bars for the entire waterfall
    private static final double MIN_VOL_MULT    = 2.0;    // climax bar must be ≥2× session avg
    private static final int    CLIMAX_LOOKBACK = 3;      // climax bar must be within 3 bars of low

    // Reversal criteria
    private static final int    REVERSAL_WINDOW  = 5;    // bars after the low to find reversal
    private static final double HAMMER_WICK_RATIO = 2.0; // lower wick ≥ 2× body for hammer
    private static final double HAMMER_MIN_CLOSE  = 0.50;// close must be in upper 50% of bar

    // Risk / reward
    private static final double SL_ATR_MULT  = 0.25; // stop = low − 0.25 × intraday ATR
    private static final double TP_RETRACE   = 0.50; // target = 50% retrace of waterfall
    private static final double MIN_RR       = 1.5;  // minimum acceptable R:R
    private static final double ENTRY_SLIP   = 0.0005; // 5 bps fill slippage

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < DROP_BARS + 3) return result;

        OHLCV last = bars.get(bars.size() - 1);
        ZonedDateTime lastZdt = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET);
        LocalDate today   = lastZdt.toLocalDate();
        LocalTime lastTime = lastZdt.toLocalTime();

        // Only fire during regular session, not in last 30 min (no time to manage)
        if (lastTime.isBefore(LocalTime.of(9, 30)) || !lastTime.isBefore(LocalTime.of(15, 30))) {
            return result;
        }

        // Session average volume (today's regular-session bars)
        double sessionAvgVol = bars.stream()
                .filter(b -> {
                    ZonedDateTime z = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET);
                    return z.toLocalDate().equals(today)
                            && !z.toLocalTime().isBefore(LocalTime.of(9, 30));
                })
                .mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        if (sessionAvgVol <= 0) return result;

        // Approximate intraday ATR from daily ATR (daily / 6.5 trading hours / (12 bars/hr for 5m))
        // Use last bar's range as absolute fallback if dailyAtr is zero
        double intradayAtr = dailyAtr > 0 ? dailyAtr / 13.0
                : Math.max(last.getHigh() - last.getLow(), last.getClose() * 0.002);

        int n = bars.size();

        // Scan recent bars for waterfall patterns
        // Slide from earliest possible start up to REVERSAL_WINDOW bars before the end
        int scanStart = Math.max(0, n - DROP_BARS - REVERSAL_WINDOW - 3);

        for (int wStart = scanStart; wStart < n - DROP_BARS; wStart++) {

            // ── Step 1: Identify waterfall high → low ────────────────────────
            int wEnd = Math.min(wStart + DROP_BARS, n - 1);

            // Find the high (peak close) first, then the low after it
            double wfHigh = -Double.MAX_VALUE;
            int    highBar = wStart;
            for (int i = wStart; i <= wEnd; i++) {
                if (bars.get(i).getClose() > wfHigh) {
                    wfHigh  = bars.get(i).getClose();
                    highBar = i;
                }
            }

            double wfLow  = Double.MAX_VALUE;
            int    lowBar = highBar;
            for (int i = highBar; i <= wEnd; i++) {
                if (bars.get(i).getLow() < wfLow) {
                    wfLow  = bars.get(i).getLow();
                    lowBar = i;
                }
            }

            // Must have at least 2 bars in the fall and a meaningful drop
            if (lowBar - highBar < 2) continue;
            if (wfHigh <= 0) continue;
            double dropPct = (wfHigh - wfLow) / wfHigh;
            if (dropPct < MIN_DROP_PCT) continue;

            // ── Step 2: Volume climax ────────────────────────────────────────
            // Highest-volume bar must be within CLIMAX_LOOKBACK bars of the low
            // and must be a bearish bar (confirms panic selling / exhaustion)
            double peakVol   = 0;
            int    climaxBar = lowBar;
            for (int i = highBar; i <= lowBar; i++) {
                if (bars.get(i).getVolume() > peakVol) {
                    peakVol   = bars.get(i).getVolume();
                    climaxBar = i;
                }
            }
            if (peakVol < sessionAvgVol * MIN_VOL_MULT) continue;
            if (lowBar - climaxBar > CLIMAX_LOOKBACK) continue; // climax must be near the bottom
            OHLCV cb = bars.get(climaxBar);
            if (cb.getClose() >= cb.getOpen() * 1.001) continue; // must be a down bar

            // ── Step 3: Reversal signal ──────────────────────────────────────
            for (int rIdx = lowBar + 1; rIdx < Math.min(lowBar + REVERSAL_WINDOW + 1, n); rIdx++) {
                OHLCV rb     = bars.get(rIdx);
                double bodyHi  = Math.max(rb.getOpen(), rb.getClose());
                double bodyLo  = Math.min(rb.getOpen(), rb.getClose());
                double bodySize = bodyHi - bodyLo;
                double lowerWick = Math.max(0, bodyLo - rb.getLow());
                double barRange  = Math.max(0.0001, rb.getHigh() - rb.getLow());
                double closePos  = (rb.getClose() - rb.getLow()) / barRange;

                boolean isHammer    = bodySize > 0
                        && lowerWick >= bodySize * HAMMER_WICK_RATIO
                        && closePos  >= HAMMER_MIN_CLOSE
                        && rb.getClose() > rb.getOpen(); // green hammer required

                boolean isBullChoch = rb.getClose() > rb.getOpen()
                        && rIdx > 0
                        && rb.getClose() > bars.get(rIdx - 1).getHigh();

                boolean isBullEngulf = rb.getClose() > rb.getOpen()
                        && rIdx > 0
                        && rb.getClose() > Math.max(bars.get(rIdx-1).getOpen(), bars.get(rIdx-1).getClose())
                        && rb.getOpen()  < Math.min(bars.get(rIdx-1).getOpen(), bars.get(rIdx-1).getClose());

                if (!isHammer && !isBullChoch && !isBullEngulf) continue;

                // ── Step 4: Floor confirmation ───────────────────────────────
                // Reversal bar must close ABOVE the climax bar's low — proves the panic floor held
                if (rb.getClose() <= cb.getLow()) continue;

                // Only fire on the current (last) bar — no historical signals
                if (rIdx != n - 1) continue;

                // ── Calculate trade levels ───────────────────────────────────
                double entry  = round4(rb.getClose() * (1.0 + ENTRY_SLIP));
                double sl     = round4(Math.min(
                        wfLow   - intradayAtr * SL_ATR_MULT,
                        rb.getLow() - intradayAtr * 0.10));
                double wfHeight = wfHigh - wfLow;
                double tp     = round4(wfLow + wfHeight * TP_RETRACE);

                double risk   = entry - sl;
                double reward = tp - entry;
                if (risk <= 0 || reward / risk < MIN_RR) continue;

                // Entry must be meaningfully below the waterfall high (not a full recovery already)
                if (entry >= wfHigh * 0.97) continue;

                // ── Confidence scoring ───────────────────────────────────────
                int conf = 70;
                if (peakVol >= sessionAvgVol * 3.5) conf += 8;  // massive climax volume
                else if (peakVol >= sessionAvgVol * 2.5) conf += 4;
                if (isBullChoch)  conf += 7;  // CHoCH = strongest reversal (new HH)
                if (isBullEngulf) conf += 5;  // engulf = second best
                if (isHammer)     conf += 3;
                if (dropPct >= 0.05) conf += 4;  // bigger drop → bigger bounce potential
                if (reward / risk >= 2.5) conf += 4;
                conf = Math.min(conf, 88); // cap — this is an aggressive counter-trend trade

                String revType = isBullChoch ? "CHoCH" : isBullEngulf ? "Engulf" : "Hammer";
                String factors = String.format(
                        "cap_reversal-long | drop=%.1f%% in %d bars | climax=%.1f×avg | rev=%s | R:R=%.1f | wf=[%.2f→%.2f]",
                        dropPct * 100, lowBar - highBar,
                        peakVol / sessionAvgVol, revType,
                        reward / risk, wfHigh, wfLow);

                result.add(TradeSetup.builder()
                        .ticker(ticker)
                        .direction("long")
                        .entry(entry)
                        .stopLoss(sl)
                        .takeProfit(tp)
                        .confidence(conf)
                        .session("NYSE")
                        .volatility("high")
                        .atr(round4(intradayAtr))
                        .hasBos(false)
                        .hasChoch(isBullChoch)
                        .fvgTop(round4(wfHigh))
                        .fvgBottom(round4(wfLow))
                        .factorBreakdown(factors)
                        .timestamp(Instant.ofEpochMilli(rb.getTimestamp()).atZone(ET).toLocalDateTime())
                        .build());

                return result; // one setup per scan
            }
        }
        return result;
    }

    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
