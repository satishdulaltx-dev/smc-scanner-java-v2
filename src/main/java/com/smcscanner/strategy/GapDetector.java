package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Detects gap-up and gap-down at market open.
 *
 * Gap = today's first bar open vs yesterday's last bar close.
 *
 * Four tradeable setups:
 * - Gap & Go  : gap (≥0.6x ATR) + 15m bar HOLDS direction → trade in gap direction
 * - Gap Trap  : large gap (≥1x ATR) + 15m bar FAILS/reverses → fade (most predictable news-day setup)
 * - Gap Fill  : small gap (<0.5x ATR) + weak volume + 15m rejection + RANGING regime → fade to prev close
 * - Bull Flag : small gap (<0.5x ATR) + TRENDING regime + 15m holds direction → continuation entry
 *
 * Key design: uses a SYNTHETIC 15m bar (aggregate of first 3 five-minute bars) for all
 * directional decisions. A single 5m opening bar is the noisiest candle of the day — full of
 * spikes and fake-outs. After 15 minutes the direction is much more reliable.
 *
 * Gap Trap is the critical case for news-day setups: COIN/AMD/AAPL/CRWD gapped 5-14% on
 * April 8 tariff-pause news but the 15m bar immediately reversed — institutions selling into
 * retail excitement. 15m bearish after gap-up = trapped buyers, strong short signal.
 *
 * Regime-aware fading: Gap Fill (fade) is only allowed in RANGING markets. In TRENDING
 * markets a small gap is a Bull Flag — fading it creates paper-cut losses.
 *
 * Institutional Runner: gapPct ≥ 5% = news/catalyst event. Confidence boosted to 95 max;
 * the gap fill path is never reached (ATR ratio far exceeds the 0.5x fill threshold).
 *
 * Open-Drive override: if all 3 confirm bars close in gap direction above/below todayOpen,
 * the volume requirement for Gap & Go is waived — price action confirms momentum.
 */
@Service
public class GapDetector {
    private static final Logger log = LoggerFactory.getLogger(GapDetector.class);

    public enum GapType { GAP_AND_GO, GAP_TRAP, GAP_FILL, NONE }

    public record GapSignal(
            String ticker,
            GapType type,
            String direction,      // "long" or "short"
            double gapPct,         // gap as % of prev close
            double gapAtrRatio,    // gap size relative to daily ATR
            double prevClose,
            double todayOpen,
            double entryPrice,
            double invalidationPrice,
            double dailyAtr,
            int confidence,
            String note
    ) {}

    // ── Backward-compatible overload (uses RANGING as default regime) ─────────
    public GapSignal detect(List<OHLCV> todayBars, List<OHLCV> prevBars,
                            double dailyAtr, String ticker) {
        return detect(todayBars, prevBars, dailyAtr, ticker, MarketRegimeDetector.Regime.RANGING);
    }

    /**
     * Detect gap signal using a synthetic 15m bar (first 1–3 five-minute bars aggregated).
     *
     * @param todayBars  5m bars from today's session (need at least 1; 3+ gives full 15m view)
     * @param prevBars   5m bars from yesterday's session (need last bar for prevClose + vol avg)
     * @param dailyAtr   daily ATR for sizing
     * @param ticker     ticker symbol
     * @param regime     current market regime — gates Gap Fill and enables Bull Flag entries
     * @return GapSignal or null if no gap detected
     */
    public GapSignal detect(List<OHLCV> todayBars, List<OHLCV> prevBars,
                            double dailyAtr, String ticker,
                            MarketRegimeDetector.Regime regime) {
        if (todayBars == null || todayBars.isEmpty()) return null;
        if (prevBars == null || prevBars.isEmpty()) return null;
        if (dailyAtr <= 0) return null;

        double prevClose = prevBars.get(prevBars.size() - 1).getClose();
        double todayOpen = todayBars.get(0).getOpen();
        if (prevClose <= 0) return null;

        double gapAbs      = todayOpen - prevClose;
        double gapPct      = gapAbs / prevClose * 100.0;
        double gapAtrRatio = Math.abs(gapAbs) / dailyAtr;

        // No meaningful gap
        if (Math.abs(gapPct) < 0.3) return null;

        // ── Synthetic 15m bar: aggregate first 1–3 five-minute bars ──────────
        // 1 bar available (9:35 scan) → use single bar (noisy but early signal)
        // 2 bars available (9:40 scan) → 10m synthetic
        // 3+ bars available (9:45–9:50 scan) → full 15m synthetic (most reliable)
        int confirmBars = Math.min(3, todayBars.size());
        double synHigh = -Double.MAX_VALUE, synLow = Double.MAX_VALUE, synVolume = 0;
        for (int i = 0; i < confirmBars; i++) {
            OHLCV b = todayBars.get(i);
            if (b.getHigh() > synHigh) synHigh = b.getHigh();
            if (b.getLow()  < synLow)  synLow  = b.getLow();
            synVolume += b.getVolume();
        }
        double synOpen   = todayBars.get(0).getOpen();
        double synClose  = todayBars.get(confirmBars - 1).getClose();
        double synRange  = Math.max(0.01, synHigh - synLow);
        double closePos  = (synClose - synLow) / synRange; // 0 = near low, 1 = near high

        // Volume: per-bar average of the 15m window vs yesterday's per-bar average
        double avgVol   = prevBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(0);
        double volRatio = avgVol > 0 ? (synVolume / confirmBars) / avgVol : 1.0;

        // Invalidation prices are still based on the single first bar extremes
        // (tightest possible SL — if price reclaims the full opening bar it's a failed signal)
        double firstBarHigh = todayBars.get(0).getHigh();
        double firstBarLow  = todayBars.get(0).getLow();

        // ── Open-Drive: all confirm bars close in gap direction above/below todayOpen ──
        // When price drives consistently above/below the open for 15 minutes, momentum is
        // confirmed by price action alone — volume requirement is waived.
        boolean openDriveBull = false, openDriveBear = false;
        if (confirmBars >= 3) {
            List<OHLCV> confirmWindow = todayBars.subList(0, confirmBars);
            openDriveBull = confirmWindow.stream().allMatch(b -> b.getClose() > todayOpen);
            openDriveBear = confirmWindow.stream().allMatch(b -> b.getClose() < todayOpen);
        }
        boolean openDriveAligned = (gapPct > 0 && openDriveBull) || (gapPct < 0 && openDriveBear);

        // ── Institutional Runner: ≥5% gap = news/catalyst event ──────────────
        // Don't fade. Don't look for a fill. Trail stop and let it run.
        boolean isRunner = Math.abs(gapPct) >= 5.0;

        // ── Gap & Go: gap holds direction (lowered threshold: 0.6x ATR) ──────
        // Volume OR open-drive required: a stock gapping 0.7x ATR with a clean
        // price drive needs no volume confirmation — momentum is visible in candles.
        // Stricter closePos (0.70 vs 0.60) for weaker gaps (0.6–1.0x ATR) to
        // compensate for smaller signal-to-noise ratio.
        if (gapAtrRatio >= 0.6 && (volRatio >= 1.5 || openDriveAligned)) {
            double closePosMin = (gapAtrRatio >= 1.0 || openDriveAligned) ? 0.60 : 0.70;
            boolean bullishHold = gapPct > 0 && synClose >= synOpen && closePos >= closePosMin;
            boolean bearishHold = gapPct < 0 && synClose <= synOpen && closePos <= (1.0 - closePosMin);
            if (bullishHold || bearishHold) {
                int conf = isRunner ? 88 : (gapAtrRatio >= 1.0 ? 65 : 58);
                if (gapAtrRatio >= 2.0) conf += 10;
                if (volRatio >= 2.5)    conf += 8;
                if (gapAtrRatio >= 1.5 && volRatio >= 2.0) conf += 5;
                if ((gapPct > 0 && closePos >= 0.8) || (gapPct < 0 && closePos <= 0.2)) conf += 5;
                if (confirmBars >= 3)   conf += 3; // full 15m confirmation bonus
                if (openDriveAligned)   conf += 5; // price-action momentum confirmed
                conf = isRunner ? Math.min(95, conf) : Math.min(92, conf);
                String dir   = gapPct > 0 ? "long" : "short";
                double entry = synClose;
                double inval = gapPct > 0 ? firstBarLow : firstBarHigh;
                String note  = String.format(
                        "%sGap %s %.1f%% (%.1fx ATR), vol %.1fx avg%s — %dm bar held gap; scalp continuation",
                        isRunner ? "RUNNER " : "",
                        gapPct > 0 ? "UP" : "DOWN", Math.abs(gapPct), gapAtrRatio, volRatio,
                        openDriveAligned ? " [open-drive]" : "", confirmBars * 5);
                log.info("GAP_AND_GO {} {} gap={}% atrRatio={} vol={}x bars={} openDrive={} runner={} conf={}",
                        ticker, dir.toUpperCase(), String.format("%.2f", gapPct),
                        String.format("%.2f", gapAtrRatio), String.format("%.1f", volRatio),
                        confirmBars, openDriveAligned, isRunner, conf);
                return new GapSignal(ticker, GapType.GAP_AND_GO, dir, gapPct,
                        gapAtrRatio, prevClose, todayOpen, entry, inval, dailyAtr, conf, note);
            }
            // not holding → fall through to Gap Trap check below
        }

        // ── Gap Trap: large gap + 15m FAILS (reverses against gap) ───────────
        // The most predictable news-day setup. Institutions sell into retail gap-up excitement.
        // 15m bar closes bearish after a gap-up = trapped buyers → strong fade (SHORT).
        // 15m bar closes bullish after a gap-down = trapped shorts → strong fade (LONG).
        // Kept at ≥1.0x ATR: fading a 0.6–1.0x ATR failed gap is too noisy.
        // E.g. COIN/AMD/AAPL/CRWD April 8: gapped 5-14%, 15m reversed completely.
        if (gapAtrRatio >= 1.0) {
            boolean gapUpFailed   = gapPct > 0 && synClose < synOpen && closePos <= 0.45;
            boolean gapDownFailed = gapPct < 0 && synClose > synOpen && closePos >= 0.55;
            if (gapUpFailed || gapDownFailed) {
                String fadeDir = gapPct > 0 ? "short" : "long";
                int conf = 68;
                if (gapAtrRatio >= 2.0) conf += 8;
                if (volRatio >= 2.0)    conf += 5;
                if ((gapPct > 0 && closePos <= 0.25) || (gapPct < 0 && closePos >= 0.75)) conf += 5;
                if (confirmBars >= 3)   conf += 4; // full 15m confirmation is most reliable
                conf = Math.min(conf, 92);
                double entry = synClose;
                double inval = gapPct > 0 ? firstBarHigh : firstBarLow;
                String note = String.format(
                        "Gap %s %.1f%% (%.1fx ATR) FAILED — %dm bar reversed (close pos %.0f%%); fading trapped buyers toward $%.2f",
                        gapPct > 0 ? "UP" : "DOWN", Math.abs(gapPct), gapAtrRatio,
                        confirmBars * 5, closePos * 100, prevClose);
                log.info("GAP_TRAP {} {} gap={}% atrRatio={} vol={}x bars={} closePos={} conf={}",
                        ticker, fadeDir.toUpperCase(), String.format("%.2f", gapPct),
                        String.format("%.2f", gapAtrRatio), String.format("%.1f", volRatio),
                        confirmBars, String.format("%.2f", closePos), conf);
                return new GapSignal(ticker, GapType.GAP_TRAP, fadeDir, gapPct,
                        gapAtrRatio, prevClose, todayOpen, entry, inval, dailyAtr, conf, note);
            }
        }

        // ── Small gap (<0.5x ATR): regime-dependent handling ─────────────────
        if (gapAtrRatio < 0.5) {

            // TRENDING / VOLATILE regime: small gap is a Bull Flag continuation entry.
            // Fading a strong stock that gaps up slightly in a trending market is how you
            // rack up $200–$500 paper-cut losses. Instead, trade the hold as a launchpad.
            if (regime == MarketRegimeDetector.Regime.TRENDING
                    || regime == MarketRegimeDetector.Regime.VOLATILE) {
                boolean bullFlag = gapPct > 0 && synClose >= synOpen && closePos >= 0.55;
                boolean bearFlag = gapPct < 0 && synClose <= synOpen && closePos <= 0.45;
                if (bullFlag || bearFlag) {
                    String dir = gapPct > 0 ? "long" : "short";
                    int conf = 58;
                    if (confirmBars >= 3) conf += 3;
                    if (openDriveAligned) conf += 5;
                    double entry = synClose;
                    double inval = gapPct > 0 ? firstBarLow : firstBarHigh;
                    String note = String.format(
                            "Bull/Bear Flag gap %s %.1f%% (%.1fx ATR) in %s regime — base held, NOT fading",
                            gapPct > 0 ? "UP" : "DOWN", Math.abs(gapPct), gapAtrRatio, regime.name());
                    log.info("GAP_FLAG {} {} gap={}% regime={} conf={}", ticker, dir.toUpperCase(),
                            String.format("%.2f", gapPct), regime, conf);
                    return new GapSignal(ticker, GapType.GAP_AND_GO, dir, gapPct,
                            gapAtrRatio, prevClose, todayOpen, entry, inval, dailyAtr, conf, note);
                }
                return null; // small gap in trending market, not held → no trade
            }

            // RANGING regime: fade the gap fill (weak gap + weak volume + 15m rejection)
            if (volRatio < 1.3) {
                String fadeDir = gapPct > 0 ? "short" : "long";
                boolean gapUpRejected   = gapPct > 0 && synClose < synOpen && closePos <= 0.45;
                boolean gapDownRejected = gapPct < 0 && synClose > synOpen && closePos >= 0.55;
                if (!gapUpRejected && !gapDownRejected) return null;
                int conf = 62;
                if (volRatio < 0.8) conf += 5;
                if ((gapPct > 0 && closePos <= 0.25) || (gapPct < 0 && closePos >= 0.75)) conf += 5;
                if (confirmBars >= 3) conf += 3;
                double entry = synClose;
                double inval = gapPct > 0 ? firstBarHigh : firstBarLow;
                String note = String.format("Gap %s %.1f%% (%.1fx ATR), vol %.1fx avg — %dm rejection favors fill toward $%.2f",
                        gapPct > 0 ? "UP" : "DOWN", Math.abs(gapPct), gapAtrRatio, volRatio, confirmBars * 5, prevClose);
                log.info("GAP_FILL {} {} (fade) gap={}% atrRatio={} vol={}x bars={} conf={}",
                        ticker, fadeDir.toUpperCase(), String.format("%.2f", gapPct),
                        String.format("%.2f", gapAtrRatio), String.format("%.1f", volRatio), confirmBars, conf);
                return new GapSignal(ticker, GapType.GAP_FILL, fadeDir, gapPct,
                        gapAtrRatio, prevClose, todayOpen, entry, inval, dailyAtr, conf, note);
            }
        }

        return null;
    }
}
