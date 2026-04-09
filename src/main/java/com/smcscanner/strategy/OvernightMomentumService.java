package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Overnight Gap Momentum Filter.
 *
 * Rather than relying on luck, this service asks: "Is this ticker *coiling*
 * for a gap at tonight's close?" Three quantifiable signals raise the
 * probability above random:
 *
 *  1. Closing Range Extremity — close in top/bottom 15% of daily candle.
 *     Price closing at the extreme means there is "unmet demand/supply"
 *     that will spill over to the next open (Wyckoff accumulation/distribution).
 *
 *  2. Late-Session Volume Acceleration — last 30 min avg > 180% of morning
 *     average (bars 2–12). Institutions "load" into the close when they have
 *     conviction; flat end-of-day volume = no institutional backing.
 *
 *  3. Relative Strength Lead — ticker outperforms SPY by ≥ 0.1% in the
 *     final hour. Rising while the market fades = institutional accumulation.
 *     Falling harder than SPY into close = distribution.
 *
 *  4. News Catalyst — earnings, FDA event, or macro news aligned with
 *     setup direction. Without a catalyst, ALL 3 technical signals must fire.
 *     With a catalyst, ANY 1 technical confirmation is sufficient.
 *
 * When holding overnight the stop-loss is tightened to the day's midpoint.
 * This caps downside if a gap-up reverses the next morning, preventing the
 * "100 to 80" scenario Gemini warned about.
 *
 * Thread-safe: all methods are stateless.
 */
@Service
public class OvernightMomentumService {
    private static final Logger log = LoggerFactory.getLogger(OvernightMomentumService.class);

    /** Close must be within this ratio from the extreme (0.85 = top/bottom 15% of daily range). */
    private static final double CLOSING_RANGE_THRESHOLD = 0.85;
    /** Last-30-min avg volume must exceed morning-session avg by this multiple. */
    private static final double VOL_ACCEL_MULTIPLIER = 1.80;
    /** Minimum RS outperformance vs SPY in final hour to qualify. */
    private static final double RS_OUTPERFORM_THRESHOLD = 0.001;  // 0.1%

    /**
     * Signal returned by {@link #evaluate}.
     *
     * @param shouldHold  true → hold position overnight
     * @param gapScore    0–100 gap probability score
     * @param reason      space-separated labels of signals that fired
     * @param suggestedSl tightened stop-loss at the day's midpoint price
     */
    public record HoldSignal(boolean shouldHold, int gapScore, String reason, double suggestedSl) {}

    /**
     * Evaluate whether to hold an open position overnight.
     *
     * @param sessionBars    today's regular-session 5-min bars (up to current time), most recent last
     * @param direction      "long" or "short"
     * @param hasCatalyst    true when news sentiment is aligned (earnings / FDA / macro catalyst)
     * @param spySessionBars today's SPY regular-session 5-min bars (pass empty list to skip RS check)
     */
    public HoldSignal evaluate(List<OHLCV> sessionBars, String direction,
                                boolean hasCatalyst, List<OHLCV> spySessionBars) {

        if (sessionBars == null || sessionBars.size() < 10) {
            return new HoldSignal(false, 0, "insufficient_bars", 0.0);
        }

        OHLCV last  = sessionBars.get(sessionBars.size() - 1);
        double dayHigh  = sessionBars.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
        double dayLow   = sessionBars.stream().mapToDouble(OHLCV::getLow).min().orElse(0);
        double dayRange = dayHigh - dayLow;
        double dayMid   = Math.round(((dayHigh + dayLow) / 2.0) * 10000.0) / 10000.0;

        if (dayRange < 1e-8) return new HoldSignal(false, 0, "no_range", dayMid);

        boolean isLong = "long".equals(direction);

        // ── 1. Closing range extremity ─────────────────────────────────────────
        // LONG: close in top 15% of day's range → unmet buy demand spills to open
        // SHORT: close in bottom 15% of day's range → unmet sell pressure spills
        double crRatio = (last.getClose() - dayLow) / dayRange;
        boolean closingRangeOk = isLong
                ? crRatio >= CLOSING_RANGE_THRESHOLD
                : crRatio <= (1.0 - CLOSING_RANGE_THRESHOLD);

        // ── 2. Late-session volume acceleration ───────────────────────────────
        // Last 30 min = last 6 five-minute bars.
        // Morning baseline = bars 2–12 (skip opening candle — it's always high and noisy).
        int n = sessionBars.size();
        double lastVolAvg = sessionBars.subList(Math.max(0, n - 6), n).stream()
                .mapToDouble(OHLCV::getVolume).average().orElse(0);

        int morningEnd   = Math.min(12, n);
        int morningStart = Math.min(1, morningEnd);
        double morningVolAvg = (morningEnd > morningStart)
                ? sessionBars.subList(morningStart, morningEnd).stream()
                        .mapToDouble(OHLCV::getVolume).average().orElse(1.0)
                : lastVolAvg; // fallback when not enough morning bars

        boolean volumeAccelOk = morningVolAvg > 0 && lastVolAvg > morningVolAvg * VOL_ACCEL_MULTIPLIER;

        // ── 3. Relative strength lead in final hour ────────────────────────────
        // Compare ticker % change vs SPY % change over the last ~60 min (12 bars).
        // LONG: ticker must outperform SPY by ≥ 0.1% (institutions buying while market fades)
        // SHORT: ticker must underperform SPY by ≥ 0.1% (distribution into strength)
        boolean rsTrendOk = false;
        if (spySessionBars != null && spySessionBars.size() >= 4) {
            int lookback     = Math.min(12, Math.min(n, spySessionBars.size()));
            List<OHLCV> tSlice = sessionBars.subList(n - lookback, n);
            List<OHLCV> sSlice = spySessionBars.subList(spySessionBars.size() - lookback,
                                                          spySessionBars.size());
            double tOpen = tSlice.get(0).getOpen();
            double sOpen = sSlice.get(0).getOpen();
            if (tOpen > 0 && sOpen > 0) {
                double tChg = (tSlice.get(lookback - 1).getClose() - tOpen) / tOpen;
                double sChg = (sSlice.get(lookback - 1).getClose() - sOpen) / sOpen;
                rsTrendOk = isLong
                        ? tChg > sChg + RS_OUTPERFORM_THRESHOLD
                        : tChg < sChg - RS_OUTPERFORM_THRESHOLD;
            }
        }

        // ── Gap score 0–100 ────────────────────────────────────────────────────
        int score = 0;
        if (hasCatalyst)    score += 30;
        if (closingRangeOk) score += 30;
        if (volumeAccelOk)  score += 25;
        if (rsTrendOk)      score += 15;

        // ── Hold decision ──────────────────────────────────────────────────────
        // With catalyst   : any 1 technical confirmation + score ≥ 55
        // Without catalyst: all 3 technical signals required + score ≥ 70
        boolean shouldHold = hasCatalyst
                ? score >= 55 && (closingRangeOk || volumeAccelOk)
                : score >= 70 && closingRangeOk && volumeAccelOk && rsTrendOk;

        // ── Reason string ──────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        if (hasCatalyst)    sb.append("CATALYST ");
        if (closingRangeOk) sb.append("CLOSE_EXTREME ");
        if (volumeAccelOk)  sb.append("VOL_ACCEL ");
        if (rsTrendOk)      sb.append("RS_LEAD");
        String reason = sb.toString().trim();
        if (reason.isEmpty()) reason = "no_signals";

        log.debug("OvernightMomentum dir={} score={} hold={} reason=[{}] cr={} volMult={}",
                direction, score, shouldHold, reason,
                String.format("%.2f", crRatio),
                morningVolAvg > 0 ? String.format("%.2f", lastVolAvg / morningVolAvg) : "n/a");

        return new HoldSignal(shouldHold, score, reason, dayMid);
    }
}
