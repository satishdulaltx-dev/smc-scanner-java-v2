package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Power Earnings Gap (PEG) Detector.
 *
 * A PEG fires when a stock gaps significantly after earnings on massive institutional
 * volume, breaks to new 52-week highs, and holds its gains into the close.
 * These are the "$20-$30 overnight jump" setups the overnight strategy was originally
 * meant to capture.
 *
 * Entry logic:
 *  - Pre-close scan (3:30–3:55 PM): use as overnight entry — stock is coiling into
 *    close with earnings catalyst, enter at close, exit on next-day gap.
 *  - Intraday open scan (9:30–9:45 AM): enter on first 5-min pullback after gap open.
 *
 * Signal criteria (all of these must be true for high confidence):
 *  1. Gap ≥ 3% from previous close (significant overnight move; 5%+ = strong PEG)
 *  2. Volume ≥ 4x 20-day average daily volume (institutional accumulation/distribution)
 *  3. Price within 5% of 52-week high/low (breakout to new extreme, not mean reversion)
 *  4. Closing strength: close in top 25% (long) or bottom 25% (short) of day's range
 *     (gap holding = institutions still buying; fading = distribution trap)
 *
 * Scoring (0–100):
 *  - Gap ≥ 5%  → +25;  3-5% gap → +15
 *  - Volume ≥ 4x → +35  (mandatory — no volume = no institutional conviction)
 *  - Near 52-week extreme → +25
 *  - Closing strength → +15
 *
 * Thread-safe: all methods are stateless.
 */
@Service
public class PowerEarningsGapDetector {
    private static final Logger log = LoggerFactory.getLogger(PowerEarningsGapDetector.class);

    private static final double MIN_GAP_PCT          = 0.03;   // 3% minimum gap
    private static final double STRONG_GAP_PCT       = 0.05;   // 5%+ = strong PEG
    private static final double MIN_VOLUME_RATIO     = 4.0;    // 4x 20-day avg
    private static final double NEAR_52W_THRESHOLD   = 0.05;   // within 5% of 52-week extreme
    private static final double CLOSING_STRENGTH_PCT = 0.75;   // top 25% of day's range

    /**
     * Full PEG signal with all sub-signals exposed for debugging and Discord alerts.
     *
     * @param detected         true when PEG conditions are met
     * @param direction        "long" (gap up) or "short" (gap down)
     * @param gapPct           gap size as a fraction (e.g. 0.07 = 7%)
     * @param volumeRatio      today's volume / 20-day avg volume
     * @param near52wExtreme   true when price is within 5% of 52-week high (long) or low (short)
     * @param closingStrength  true when close is in top/bottom 25% of day's range
     * @param confidence       0–100 score
     * @param entry            suggested entry price (close of last session bar)
     * @param stopLoss         suggested SL (below gap open for long, above for short)
     * @param takeProfit       suggested TP (2× gap size from entry)
     * @param note             human-readable summary for logging / Discord
     */
    public record PEGSignal(
            boolean detected,
            String direction,
            double gapPct,
            double volumeRatio,
            boolean near52wExtreme,
            boolean closingStrength,
            int confidence,
            double entry,
            double stopLoss,
            double takeProfit,
            String note
    ) {}

    /**
     * Detect a Power Earnings Gap.
     *
     * @param sessionBars5m today's regular-session 5-min bars (up to current time)
     * @param dailyBars     daily bars — needs ≥22 bars for volume avg + 52-week high
     * @param prevClose     previous session's closing price (for gap calculation)
     * @param dailyAtr      current daily ATR (for SL/TP sizing)
     */
    public PEGSignal detect(List<OHLCV> sessionBars5m, List<OHLCV> dailyBars,
                             double prevClose, double dailyAtr) {

        if (sessionBars5m == null || sessionBars5m.isEmpty()
                || dailyBars == null || dailyBars.size() < 22) {
            return noSignal("insufficient_data", 0, 0);
        }

        // ── 1. Gap size ────────────────────────────────────────────────────
        double todayOpen    = sessionBars5m.get(0).getOpen();
        double currentPrice = sessionBars5m.get(sessionBars5m.size() - 1).getClose();
        double gapPct       = prevClose > 0 ? (todayOpen - prevClose) / prevClose : 0;

        boolean gapUp   = gapPct >=  MIN_GAP_PCT;
        boolean gapDown = gapPct <= -MIN_GAP_PCT;
        if (!gapUp && !gapDown) {
            return noSignal("gap_too_small_" + String.format("%.1f%%", gapPct * 100),
                    gapPct, 0);
        }
        String direction = gapUp ? "long" : "short";
        double absGapPct = Math.abs(gapPct);

        // ── 2. Volume: today's cumulative vs 20-day avg daily volume ───────
        // Use 20 bars before the most recent daily bar (current day not yet closed).
        double todayVolume  = sessionBars5m.stream().mapToDouble(OHLCV::getVolume).sum();
        int    volEnd       = dailyBars.size() - 1; // exclude today
        int    volStart     = Math.max(0, volEnd - 20);
        double avgDailyVol  = dailyBars.subList(volStart, volEnd).stream()
                .mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        double volumeRatio  = avgDailyVol > 0 ? todayVolume / avgDailyVol : 0;
        boolean volumeOk    = volumeRatio >= MIN_VOLUME_RATIO;

        // ── 3. Near 52-week extreme ────────────────────────────────────────
        int histEnd         = dailyBars.size() - 1;
        int histStart       = Math.max(0, histEnd - 252);
        List<OHLCV> yearBars = dailyBars.subList(histStart, histEnd);
        double high52w      = yearBars.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
        double low52w       = yearBars.stream().mapToDouble(OHLCV::getLow).min().orElse(Double.MAX_VALUE);
        boolean nearHigh    = high52w > 0 && currentPrice >= high52w * (1 - NEAR_52W_THRESHOLD);
        boolean nearLow     = low52w < Double.MAX_VALUE && currentPrice <= low52w * (1 + NEAR_52W_THRESHOLD);
        boolean near52w     = gapUp ? nearHigh : nearLow;

        // ── 4. Closing strength ────────────────────────────────────────────
        double dayHigh      = sessionBars5m.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
        double dayLow       = sessionBars5m.stream().mapToDouble(OHLCV::getLow).min().orElse(0);
        double dayRange     = dayHigh - dayLow;
        double closingRatio = dayRange > 1e-8 ? (currentPrice - dayLow) / dayRange : 0.5;
        boolean closingStr  = gapUp
                ? closingRatio >= CLOSING_STRENGTH_PCT
                : closingRatio <= (1.0 - CLOSING_STRENGTH_PCT);

        // ── Score ──────────────────────────────────────────────────────────
        int score = 0;
        if (absGapPct >= STRONG_GAP_PCT) score += 25;  // 5%+ gap
        else                              score += 15;  // 3-5% gap
        if (volumeOk)   score += 35;   // 4x volume = mandatory institutional signal
        if (near52w)    score += 25;   // breaking to new extreme
        if (closingStr) score += 15;   // holding the gap

        // Volume is mandatory — no institutional conviction = no PEG
        boolean detected = score >= 50 && volumeOk;

        // ── Entry / SL / TP ────────────────────────────────────────────────
        // Entry at current close.
        // SL just below the gap-open price (for long) — if price fills back to
        //   the open and keeps going, the thesis is broken.
        // TP = entry ± 2× gap size (momentum continuation).
        double entry     = currentPrice;
        double gapMove   = Math.abs(todayOpen - prevClose);
        double slBuffer  = Math.max(dailyAtr * 0.5, gapMove * 0.3); // at least 30% of gap
        double stopLoss  = gapUp
                ? Math.round((todayOpen - slBuffer) * 10000.0) / 10000.0
                : Math.round((todayOpen + slBuffer) * 10000.0) / 10000.0;
        double takeProfit = gapUp
                ? Math.round((entry + gapMove * 2.0) * 10000.0) / 10000.0
                : Math.round((entry - gapMove * 2.0) * 10000.0) / 10000.0;

        String note = String.format(
                "PEG %s gap=%.1f%% vol=%.1fx 52wk=%s closeStr=%s score=%d",
                direction.toUpperCase(), gapPct * 100, volumeRatio,
                near52w ? "✓" : "✗", closingStr ? "✓" : "✗", score);

        log.debug("PowerEarningsGap {}: {} detected={} vol={:.1f}x gap={:.1f}% near52w={} closeStr={}",
                direction, note, detected, volumeRatio, gapPct * 100, near52w, closingStr);

        return new PEGSignal(detected, direction, gapPct, volumeRatio,
                near52w, closingStr, score, entry, stopLoss, takeProfit, note);
    }

    private PEGSignal noSignal(String reason, double gapPct, double volRatio) {
        return new PEGSignal(false, null, gapPct, volRatio, false, false, 0,
                0, 0, 0, "NO_PEG: " + reason);
    }
}
