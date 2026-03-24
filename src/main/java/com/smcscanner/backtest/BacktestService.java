package com.smcscanner.backtest;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Simple SMC backtest: replays 90 days of daily bars using a sliding window.
 * For each window it detects Sweep → Displacement → FVG → Retest and tracks
 * whether TP or SL was hit in subsequent bars.
 */
@Service
public class BacktestService {
    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final PolygonClient  client;
    private final AtrCalculator  atrCalc;

    // How many daily bars to use as the pattern-detection window
    private static final int PATTERN_WINDOW  = 10;
    // How many bars forward to look for TP/SL hit
    private static final int FORWARD_BARS    = 10;
    private static final double MIN_FVG_PCT  = 0.002; // 0.2% on daily bars

    public BacktestService(PolygonClient client, AtrCalculator atrCalc) {
        this.client = client; this.atrCalc = atrCalc;
    }

    public BacktestResult run(String ticker, int lookbackDays) {
        List<OHLCV> bars = client.getBars(ticker, "1d", lookbackDays);
        if (bars == null || bars.size() < PATTERN_WINDOW + FORWARD_BARS + 5) {
            return BacktestResult.empty(ticker, "Insufficient data (" + (bars==null?0:bars.size()) + " bars)");
        }

        List<TradeResult> trades = new ArrayList<>();
        int n = bars.size();

        for (int end = PATTERN_WINDOW; end <= n - FORWARD_BARS; end++) {
            List<OHLCV> window = bars.subList(end - PATTERN_WINDOW, end);
            double[] atrArr = atrCalc.computeAtr(window, Math.min(14, window.size()-1));
            double atr = 0;
            for (int i = atrArr.length-1; i >= 0; i--) { if (atrArr[i] > 0) { atr = atrArr[i]; break; } }
            if (atr == 0) continue;

            double[] setup = detectSetup(window, atr);
            if (setup == null) continue;

            String dir    = setup[0] == 1 ? "long" : "short";
            double entry  = setup[1];
            double fvgTop = setup[2], fvgBot = setup[3];
            double fvgSize = Math.abs(fvgTop - fvgBot);
            double price  = window.get(window.size()-1).getClose();
            if (fvgSize / price < MIN_FVG_PCT) continue;

            double sl = dir.equals("long") ? entry - atr * 0.4 : entry + atr * 0.4;
            double tp = dir.equals("long") ? entry + atr * 1.2 : entry - atr * 1.2;

            // Forward-test: look for TP or SL hit in next FORWARD_BARS bars
            String outcome = "OPEN";
            double pnlPct  = 0.0;
            int exitBarIdx = -1;
            for (int f = end; f < Math.min(end + FORWARD_BARS, n); f++) {
                OHLCV fb = bars.get(f);
                if (dir.equals("long")) {
                    if (fb.getLow()  <= sl) { outcome = "LOSS"; pnlPct = (sl-entry)/entry*100; exitBarIdx = f; break; }
                    if (fb.getHigh() >= tp) { outcome = "WIN";  pnlPct = (tp-entry)/entry*100; exitBarIdx = f; break; }
                } else {
                    if (fb.getHigh() >= sl) { outcome = "LOSS"; pnlPct = (entry-sl)/entry*100*-1; exitBarIdx = f; break; }
                    if (fb.getLow()  <= tp) { outcome = "WIN";  pnlPct = (entry-tp)/entry*100;  exitBarIdx = f; break; }
                }
            }
            if (outcome.equals("OPEN")) continue; // skip unresolved

            String tradeDate = toDate(bars.get(end - 1).getTimestamp());
            String exitDate  = exitBarIdx >= 0 ? toDate(bars.get(exitBarIdx).getTimestamp()) : tradeDate;
            int daysHeld = exitBarIdx >= 0 ? exitBarIdx - (end - 1) : 0;
            trades.add(new TradeResult(ticker, dir, entry, sl, tp, outcome, Math.round(pnlPct*100)/100.0,
                    tradeDate, exitDate, daysHeld, atr));
        }

        return BacktestResult.of(ticker, trades, lookbackDays);
    }

    /** Detect Sweep→Displacement→FVG on a window of daily bars.
     *  Returns [direction(1=long,-1=short), entryPrice, fvgTop, fvgBot] or null. */
    private double[] detectSetup(List<OHLCV> bars, double atr) {
        int n = bars.size();
        // Find swing high/low of first half
        double swH = Double.MIN_VALUE, swL = Double.MAX_VALUE;
        for (int i = 0; i < n - 4; i++) {
            if (bars.get(i).getHigh() > swH) swH = bars.get(i).getHigh();
            if (bars.get(i).getLow()  < swL) swL = bars.get(i).getLow();
        }
        // Look for sweep in last 4 bars
        for (int i = n - 4; i < n; i++) {
            OHLCV b = bars.get(i);
            boolean bull = b.getLow() < swL && b.getClose() > swL;
            boolean bear = b.getHigh() > swH && b.getClose() < swH;
            if (!bull && !bear) continue;

            // Displacement: next bar must be large range in sweep direction
            if (i + 1 >= n) continue;
            OHLCV d = bars.get(i + 1);
            boolean dispOk = (d.getHigh() - d.getLow()) > atr * 0.8;
            boolean dirOk  = bull ? d.getClose() > d.getOpen() : d.getClose() < d.getOpen();
            if (!dispOk || !dirOk) continue;

            // FVG: check bars i and i+2
            if (i + 2 >= n) continue;
            OHLCV b1 = bars.get(i), b3 = bars.get(Math.min(i+2, n-1));
            double fvgTop, fvgBot;
            if (bull) {
                if (b3.getLow() <= b1.getHigh()) continue; // no gap
                fvgTop = b3.getLow(); fvgBot = b1.getHigh();
            } else {
                if (b1.getLow() >= b3.getHigh()) continue;
                fvgTop = b1.getLow(); fvgBot = b3.getHigh();
            }
            double entry = (fvgTop + fvgBot) / 2.0;
            return new double[]{ bull ? 1 : -1, entry, fvgTop, fvgBot };
        }
        return null;
    }

    private String toDate(String rawTs) {
        try {
            long epochMs = Long.parseLong(rawTs);
            return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (NumberFormatException e) {
            return rawTs;
        }
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record TradeResult(String ticker, String direction, double entry, double sl, double tp,
                               String outcome, double pnlPct, String barIndex, String exitDate,
                               int daysHeld, double atr) {}

    public static class BacktestResult {
        public final String ticker;
        public final List<TradeResult> trades;
        public final int lookbackDays;
        public final String error;
        public final int wins, losses, total;
        public final double winRate, avgWinPct, avgLossPct, expectancy;

        private BacktestResult(String ticker, List<TradeResult> trades, int lookbackDays, String error) {
            this.ticker = ticker; this.trades = trades;
            this.lookbackDays = lookbackDays; this.error = error;
            this.wins    = (int) trades.stream().filter(t -> "WIN".equals(t.outcome())).count();
            this.losses  = (int) trades.stream().filter(t -> "LOSS".equals(t.outcome())).count();
            this.total   = wins + losses;
            this.winRate = total > 0 ? Math.round(wins * 100.0 / total * 10) / 10.0 : 0;
            this.avgWinPct  = trades.stream().filter(t->"WIN".equals(t.outcome()))
                    .mapToDouble(TradeResult::pnlPct).average().orElse(0);
            this.avgLossPct = trades.stream().filter(t->"LOSS".equals(t.outcome()))
                    .mapToDouble(t -> Math.abs(t.pnlPct())).average().orElse(0);
            this.expectancy = total > 0
                    ? (winRate/100 * avgWinPct) - ((1 - winRate/100) * avgLossPct) : 0;
        }

        public static BacktestResult of(String ticker, List<TradeResult> trades, int lookbackDays) {
            return new BacktestResult(ticker, trades, lookbackDays, null);
        }
        public static BacktestResult empty(String ticker, String error) {
            return new BacktestResult(ticker, List.of(), 0, error);
        }
    }
}
