package com.smcscanner.strategy;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.VolumeProfileCalculator;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.model.indicator.VolumeProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * VWAP Standard-Deviation Band Scalp Detector.
 *
 * Replaces the Bollinger Band model. Three layers must align for an entry:
 *
 *  1. REGIME  — price is on the correct side of session VWAP
 *  2. LEVEL   — price touched a VWAP SD band (VWAP, ±1SD) and rejected it
 *  3. TRIGGER — rejection candle at that level with volume surge
 *
 * Dead zone 11:30–13:30 ET is always skipped — historically low follow-through.
 * TP targets the next VWAP SD band, giving natural 1.5–2:1 R/R on most setups.
 *
 * Layers 4–7 (Volume Profile, order-flow delta, NBBO, sweep) are carried
 * forward from the prior model and adjustable confidence.
 */
@Service
public class ScalpMomentumDetector {
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final LocalTime SESSION_OPEN    = LocalTime.of(9,  35);
    private static final LocalTime DEAD_ZONE_START = LocalTime.of(11, 30);
    private static final LocalTime DEAD_ZONE_END   = LocalTime.of(13, 30);
    private static final LocalTime SESSION_CLOSE   = LocalTime.of(15, 30);

    // Entry must be within this many ATR of a VWAP band to count as "at level"
    private static final double LEVEL_TOUCH_ATR = 0.45;
    // Minimum volume multiplier on the rejection bar
    private static final double MIN_VOL_RATIO   = 1.4;
    // Reject obvious chase entries after two same-direction expansion bars

    private final PolygonClient              polygon;
    private final VolumeProfileCalculator    vpCalc;
    private final MicrostructureQualifier    microQ;

    public ScalpMomentumDetector(PolygonClient polygon, VolumeProfileCalculator vpCalc,
                                 MicrostructureQualifier microQ) {
        this.polygon = polygon;
        this.vpCalc  = vpCalc;
        this.microQ  = microQ;
    }

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        return detect(bars, List.of(), ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, List<OHLCV> spyBars, String ticker, double dailyAtr) {
        return detect(bars, spyBars, ticker, dailyAtr, false);
    }

    public List<TradeSetup> detect(List<OHLCV> bars, List<OHLCV> spyBars, String ticker, double dailyAtr, boolean backtestMode) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < 25) return result;

        List<OHLCV> sessionBars = regularSessionBarsForToday(bars);
        if (sessionBars.size() < 20) return result;

        OHLCV last = sessionBars.get(sessionBars.size() - 1);

        // Staleness guard: skipped in backtest — the last bar date is historical by design.
        // Live: last session bar must be from today's wall-clock date (prevents stale scans).
        if (!backtestMode && !Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDate().equals(LocalDate.now(ET))) {
            return result;
        }

        LocalTime now = Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalTime();

        // Active windows: morning (9:35–11:30) and afternoon (13:30–15:30)
        boolean inMorning   = !now.isBefore(SESSION_OPEN)    && now.isBefore(DEAD_ZONE_START);
        boolean inAfternoon = !now.isBefore(DEAD_ZONE_END)   && now.isBefore(SESSION_CLOSE);
        if (!inMorning && !inAfternoon) return result;

        int   n    = sessionBars.size();
        OHLCV prev = sessionBars.get(n - 2);

        double atr     = Math.max(computeAtr(sessionBars, 8), last.getClose() * 0.0012);
        double avgVol  = averageVolume(sessionBars, n - 7, n - 2);
        double volRatio = last.getVolume() / Math.max(1.0, avgVol);

        // ── VWAP Standard Deviation Bands (session-anchored) ─────────────────
        VwapBands vb = computeVwapBands(sessionBars, n - 1);
        if (vb == null) return result;

        double close  = last.getClose();
        double prevLo = prev.getLow(),  lastLo = last.getLow();
        double prevHi = prev.getHigh(), lastHi = last.getHigh();

        // ── SPY context ───────────────────────────────────────────────────────
        double spyReturn  = intradayReturn(spyBars);
        double tickReturn = intradayReturn(sessionBars);
        double rsLead     = tickReturn - spyReturn;
        boolean spyBull   = spyReturn >  0.0015;
        boolean spyBear   = spyReturn < -0.0015;

        // ── Candle shape ──────────────────────────────────────────────────────
        boolean lastGreen      = close > last.getOpen();
        boolean lastRed        = close < last.getOpen();
        double  lastBodyPct    = bodyPct(last);
        boolean closeNearHigh  = (lastHi - close) <= (lastHi - lastLo) * 0.25;
        boolean closeNearLow   = (close - lastLo)  <= (lastHi - lastLo) * 0.25;

        // ── 5m structure: EMA slope + basic swing check ───────────────────────
        boolean bullStructure = isBullStructure(sessionBars, n);
        boolean bearStructure = isBearStructure(sessionBars, n);

        // ── Level detection ───────────────────────────────────────────────────
        // A "touch" = last or prev bar's wick entered within LEVEL_TOUCH_ATR of the band.
        // "Rejection" = close is back on the correct side of the band.
        double touch = atr * LEVEL_TOUCH_ATR;

        // LONG levels (support): VWAP, VWAP+1SD (extended then pulled back)
        boolean touchedVwapLong  = (lastLo <= vb.vwap + touch || prevLo <= vb.vwap + touch) && close > vb.vwap;
        boolean touchedVwap1uLong = (lastLo <= vb.v1u + touch  || prevLo <= vb.v1u + touch)  && close > vb.v1u;

        // SHORT levels (resistance): VWAP, VWAP-1SD
        boolean touchedVwapShort  = (lastHi >= vb.vwap - touch || prevHi >= vb.vwap - touch) && close < vb.vwap;
        boolean touchedVwap1dShort = (lastHi >= vb.v1d - touch  || prevHi >= vb.v1d - touch)  && close < vb.v1d;

        // Pick the best level for each side
        String longLevel; double longTpBand;
        if (touchedVwapLong) {
            longLevel = "vwap";  longTpBand = vb.v1u;
        } else if (touchedVwap1uLong) {
            longLevel = "vwap+1sd"; longTpBand = vb.v2u;
        } else {
            longLevel = null;    longTpBand = 0;
        }

        String shortLevel; double shortTpBand;
        if (touchedVwapShort) {
            shortLevel = "vwap";   shortTpBand = vb.v1d;
        } else if (touchedVwap1dShort) {
            shortLevel = "vwap-1sd"; shortTpBand = vb.v2d;
        } else {
            shortLevel = null;     shortTpBand = 0;
        }

        // ── Core setup gates ──────────────────────────────────────────────────
        boolean setupLong = longLevel != null
                && lastGreen && lastBodyPct >= 0.40 && closeNearHigh
                && volRatio >= MIN_VOL_RATIO
                && bullStructure
                && !isLateExpansionChase(sessionBars, n, atr, true)
                && !spyBear && rsLead > -0.002;

        boolean setupShort = shortLevel != null
                && lastRed && lastBodyPct >= 0.40 && closeNearLow
                && volRatio >= MIN_VOL_RATIO
                && bearStructure
                && !isLateExpansionChase(sessionBars, n, atr, false)
                && !spyBull && rsLead < 0.002;

        if (!setupLong && !setupShort) return result;

        // If both somehow trigger (very unlikely), prefer whichever has clearer structure
        boolean isLong;
        if (setupLong && setupShort) {
            isLong = bullStructure && !bearStructure;
        } else {
            isLong = setupLong;
        }

        String levelName   = isLong ? longLevel  : shortLevel;
        double tpBand      = isLong ? longTpBand : shortTpBand;
        String setupType   = "vwap-scalp-" + levelName;

        // ── Entry / SL / TP ───────────────────────────────────────────────────
        // SL is placed just beyond the CURRENT bar's rejection wick only.
        // Using prev bar's extreme can produce swing-size SLs on large prior candles.
        // Max risk guard: skip if stop is more than 1.5× ATR away — not a tight scalp.
        double entry = round4(close);
        double stop, tp;

        if (isLong) {
            double slFallback = round4(lastLo - atr * 0.15);
            stop = round4(SwingLevelFinder.swingLowSl(sessionBars, entry, atr, 15, slFallback));
            double risk = Math.abs(entry - stop);
            if (risk <= 0 || risk > atr * 1.5) return result;
            if (isLateExpansionChase(sessionBars, n, atr, true) && (tpBand - entry) < risk * 0.8) return result;
            tp = round4(Math.max(tpBand, entry + risk * 1.5));
        } else {
            double slFallback = round4(lastHi + atr * 0.15);
            stop = round4(SwingLevelFinder.swingHighSl(sessionBars, entry, atr, 15, slFallback));
            double risk = Math.abs(entry - stop);
            if (risk <= 0 || risk > atr * 1.5) return result;
            if (isLateExpansionChase(sessionBars, n, atr, false) && (entry - tpBand) < risk * 0.8) return result;
            tp = round4(Math.min(tpBand, entry - risk * 1.5));
        }

        // ── Base confidence ───────────────────────────────────────────────────
        int confidence = 72;
        if (volRatio >= 2.0)             confidence += 5;
        else if (volRatio >= 1.6)        confidence += 3;
        if (Math.abs(rsLead) >= 0.003)   confidence += 4;
        if (inMorning)                   confidence += 3; // morning momentum is stronger
        if (isLong  && isBullSwing(sessionBars, n)) confidence += 4;
        if (!isLong && isBearSwing(sessionBars, n)) confidence += 4;
        // Bonus for touching VWAP itself (cleaner level than SD band)
        if ("vwap".equals(levelName))    confidence += 3;

        // ══════════════════════════════════════════════════════════════════════
        // LAYER 2 — Volume Profile
        // ══════════════════════════════════════════════════════════════════════
        VolumeProfile vp = vpCalc.calculate(sessionBars);
        String vpLabel = "N/A";
        if (vp != null) {
            double vpoc = vp.getVpoc(), vah = vp.getVah(), val = vp.getVal();
            double nearestDist = Math.min(
                    Math.min(Math.abs(entry - vpoc), Math.abs(entry - vah)),
                    Math.abs(entry - val));
            if (nearestDist < atr * 0.4) {
                confidence += 6;
                vpLabel = String.format("AT KEY VP (vpoc=%.2f vah=%.2f val=%.2f)", vpoc, vah, val);
            } else if (nearestDist < atr * 1.0) {
                confidence += 2;
                vpLabel = String.format("NEAR VP (vpoc=%.2f vah=%.2f val=%.2f)", vpoc, vah, val);
            } else {
                confidence -= 4;
                vpLabel = String.format("NO VP LEVEL (dist=%.2fatr)", nearestDist / atr);
            }
            if (isLong  && entry > vah + atr * 0.3) confidence -= 4;
            if (!isLong && entry < val - atr * 0.3) confidence -= 4;
        }

        // ══════════════════════════════════════════════════════════════════════
        // LAYERS 3-5 — MicrostructureQualifier
        //
        // Replaces three shallow proxies:
        //   • fake OHLCV delta (candle position ≠ tape pressure)
        //   • single NBBO snapshot (one quote ≠ persistent imbalance)
        //   • single large-print sweep (size threshold ≠ burst clustering)
        //
        // In backtest mode these layers are skipped — live tape data is not
        // available for historical bars.  Base confidence drives backtest results.
        // ══════════════════════════════════════════════════════════════════════
        MicrostructureQualifier.MicroScore micro =
                new MicrostructureQualifier.MicroScore(0, backtestMode ? "backtest-parity" : "live-parity");

        if (!backtestMode) {
            List<PolygonClient.TradeRecord> trades = List.of();
            List<PolygonClient.QuoteRecord> quotes = List.of();
            try { trades = polygon.getRecentTrades(ticker, 200); } catch (Exception ignored) {}
            try { quotes = polygon.getRecentQuotes(ticker, 30);  } catch (Exception ignored) {}
            micro = microQ.qualify(isLong, entry, atr, trades, quotes, sessionBars);
        }

        String factors = String.format(
                "%s | vol x%.1f | RS %+.2f%% | VWAP=%.2f ±1SD=[%.2f/%.2f] ±2SD=[%.2f/%.2f]" +
                " | VP: %s | %s",
                setupType, volRatio, rsLead * 100.0,
                vb.vwap, vb.v1d, vb.v1u, vb.v2d, vb.v2u,
                vpLabel, micro.label());

        result.add(TradeSetup.builder()
                .ticker(ticker)
                .direction(isLong ? "long" : "short")
                .entry(entry)
                .stopLoss(stop)
                .takeProfit(tp)
                .confidence(confidence)
                .session("NYSE")
                .volatility("scalp")
                .atr(round4(atr))
                .hasBos(false)
                .hasChoch(false)
                .fvgTop(round4(vb.v1u))
                .fvgBottom(round4(vb.v1d))
                .factorBreakdown(factors)
                .timestamp(Instant.ofEpochMilli(last.getTimestamp()).atZone(ET).toLocalDateTime())
                .build());

        return result;
    }

    // ── VWAP Standard Deviation Bands ────────────────────────────────────────

    private record VwapBands(double vwap, double sd, double v1u, double v1d, double v2u, double v2d) {}

    private VwapBands computeVwapBands(List<OHLCV> bars, int endIdx) {
        int end = Math.min(endIdx, bars.size() - 1);
        double sumPV = 0, sumV = 0, sumPV2 = 0;
        for (int i = 0; i <= end; i++) {
            OHLCV b = bars.get(i);
            double tp = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
            double v  = b.getVolume();
            sumPV  += tp * v;
            sumV   += v;
            sumPV2 += tp * tp * v;
        }
        if (sumV <= 0) return null;
        double vwap     = sumPV / sumV;
        double variance = Math.max(0, (sumPV2 / sumV) - (vwap * vwap));
        double sd       = Math.max(0.01, Math.sqrt(variance)); // floor prevents degenerate early-session values
        return new VwapBands(vwap, sd, vwap + sd, vwap - sd, vwap + 2 * sd, vwap - 2 * sd);
    }

    // ── 5m Structure ─────────────────────────────────────────────────────────

    /** Bullish structure: close above rising 5-bar EMA */
    private boolean isBullStructure(List<OHLCV> bars, int n) {
        if (n < 8) return false;
        double emaNow  = computeEma(bars, n - 1, 5);
        double emaPrev = computeEma(bars, n - 4, 5);
        return bars.get(n - 1).getClose() > emaNow && emaNow > emaPrev;
    }

    private boolean isBearStructure(List<OHLCV> bars, int n) {
        if (n < 8) return false;
        double emaNow  = computeEma(bars, n - 1, 5);
        double emaPrev = computeEma(bars, n - 4, 5);
        return bars.get(n - 1).getClose() < emaNow && emaNow < emaPrev;
    }

    /** Stronger: last swing high > prior AND last swing low > prior (HH/HL) */
    private boolean isBullSwing(List<OHLCV> bars, int n) {
        return checkSwing(bars, n, true);
    }

    private boolean isBearSwing(List<OHLCV> bars, int n) {
        return checkSwing(bars, n, false);
    }

    private boolean checkSwing(List<OHLCV> bars, int n, boolean bullish) {
        int lookback = Math.min(n, 14);
        int start = n - lookback;
        List<Integer> highs = new ArrayList<>(), lows = new ArrayList<>();
        for (int i = start + 1; i < n - 1; i++) {
            if (bars.get(i).getHigh() > bars.get(i-1).getHigh() && bars.get(i).getHigh() > bars.get(i+1).getHigh())
                highs.add(i);
            if (bars.get(i).getLow() < bars.get(i-1).getLow() && bars.get(i).getLow() < bars.get(i+1).getLow())
                lows.add(i);
        }
        if (highs.size() < 2 || lows.size() < 2) return false;
        int h1 = highs.get(highs.size()-1), h2 = highs.get(highs.size()-2);
        int l1 = lows.get(lows.size()-1),   l2 = lows.get(lows.size()-2);
        if (bullish) return bars.get(h1).getHigh() > bars.get(h2).getHigh()
                         && bars.get(l1).getLow()  > bars.get(l2).getLow();
        else         return bars.get(h1).getHigh() < bars.get(h2).getHigh()
                         && bars.get(l1).getLow()  < bars.get(l2).getLow();
    }

    private double computeEma(List<OHLCV> bars, int endIdx, int period) {
        int end = Math.min(endIdx, bars.size() - 1);
        int start = Math.max(0, end - period * 3);
        double k = 2.0 / (period + 1.0);
        double ema = bars.get(start).getClose();
        for (int i = start + 1; i <= end; i++)
            ema = bars.get(i).getClose() * k + ema * (1 - k);
        return ema;
    }

    private boolean isLateExpansionChase(List<OHLCV> bars, int n, double atr, boolean bullish) {
        if (n < 4) return false;
        OHLCV last = bars.get(n - 1);
        OHLCV prev = bars.get(n - 2);
        if (!isExpansionBar(last, atr, bullish) || !isExpansionBar(prev, atr, bullish)) return false;

        OHLCV anchor = bars.get(n - 4);
        if (bullish) {
            double extension = last.getClose() - Math.min(anchor.getLow(), prev.getLow());
            return extension > atr * 1.6;
        }
        double extension = Math.max(anchor.getHigh(), prev.getHigh()) - last.getClose();
        return extension > atr * 1.6;
    }

    private boolean isExpansionBar(OHLCV bar, double atr, boolean bullish) {
        double range = Math.max(0.0001, bar.getHigh() - bar.getLow());
        if (range < atr * 0.85) return false;
        if (bodyPct(bar) < 0.55) return false;
        if (bullish) {
            return bar.getClose() > bar.getOpen()
                    && (bar.getHigh() - bar.getClose()) <= range * 0.30;
        }
        return bar.getClose() < bar.getOpen()
                && (bar.getClose() - bar.getLow()) <= range * 0.30;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<OHLCV> regularSessionBarsForToday(List<OHLCV> bars) {
        OHLCV lastRaw = bars.get(bars.size()-1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp()).atZone(ET).toLocalDate();
        List<OHLCV> result = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            LocalTime t = zdt.toLocalTime();
            if (zdt.toLocalDate().equals(today)
                    && !t.isBefore(LocalTime.of(9, 30))
                    && t.isBefore(LocalTime.of(16, 0)))
                result.add(bar);
        }
        return result;
    }

    private double computeAtr(List<OHLCV> bars, int period) {
        int p = Math.min(period, bars.size()-1);
        if (p <= 0) return 0.0;
        double sum = 0.0;
        for (int i = bars.size()-p; i < bars.size(); i++) {
            OHLCV c = bars.get(i), pv = bars.get(i-1);
            sum += Math.max(c.getHigh()-c.getLow(),
                   Math.max(Math.abs(c.getHigh()-pv.getClose()),
                            Math.abs(c.getLow()-pv.getClose())));
        }
        return sum / p;
    }

    private double averageVolume(List<OHLCV> bars, int from, int to) {
        from = Math.max(0, from); to = Math.min(bars.size()-1, to);
        if (from > to) return 1.0;
        double sum = 0; int count = 0;
        for (int i = from; i <= to; i++) { sum += bars.get(i).getVolume(); count++; }
        return count > 0 ? sum / count : 1.0;
    }

    private double intradayReturn(List<OHLCV> bars) {
        if (bars == null || bars.size() < 2) return 0.0;
        OHLCV f = bars.get(0), l = bars.get(bars.size()-1);
        return f.getOpen() > 0 ? (l.getClose() - f.getOpen()) / f.getOpen() : 0.0;
    }

    private double bodyPct(OHLCV bar) {
        double range = Math.max(0.0001, bar.getHigh() - bar.getLow());
        return Math.abs(bar.getClose() - bar.getOpen()) / range;
    }

    private double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
