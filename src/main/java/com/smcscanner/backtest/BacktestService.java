package com.smcscanner.backtest;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.strategy.SetupDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Intraday SMC backtest: replays historical 5m bars using the exact same
 * SetupDetector logic as live alerts. Detects setups day by day and checks
 * whether TP or SL was hit within that day (or next day for late-day entries).
 */
@Service
public class BacktestService {
    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final PolygonClient  client;
    private final AtrCalculator  atrCalc;
    private final SetupDetector  setupDetector;

    public BacktestService(PolygonClient client, AtrCalculator atrCalc, SetupDetector setupDetector) {
        this.client = client; this.atrCalc = atrCalc; this.setupDetector = setupDetector;
    }

    public BacktestResult run(String ticker, int lookbackDays) {
        // Fetch 5m bars for the full lookback — one API call gets it all
        List<OHLCV> allBars = client.getBarsWithLookback(ticker, "5m", 5000, lookbackDays);
        if (allBars == null || allBars.size() < 30) {
            return BacktestResult.empty(ticker, "Insufficient data (" + (allBars==null?0:allBars.size()) + " bars)");
        }

        // Group bars by calendar date in ET
        TreeMap<LocalDate, List<OHLCV>> byDate = new TreeMap<>();
        for (OHLCV bar : allBars) {
            LocalDate d = Instant.ofEpochMilli(Long.parseLong(bar.getTimestamp()))
                    .atZone(ET).toLocalDate();
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(bar);
        }

        // Fetch daily bars for simple HTF bias (SMA20)
        List<OHLCV> dailyBars = client.getBars(ticker, "1d", 60);

        List<TradeResult> trades = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());

        for (int di = 0; di < dates.size(); di++) {
            LocalDate date = dates.get(di);
            List<OHLCV> dayBars = byDate.get(date);
            if (dayBars.size() < 20) continue;

            // HTF bias + daily ATR using daily bars up to this date
            String htfBias = "neutral";
            double dailyAtr = 0.0;
            if (dailyBars != null && !dailyBars.isEmpty()) {
                long cutoff = date.atStartOfDay(ET).toInstant().toEpochMilli();
                List<OHLCV> htfSlice = dailyBars.stream()
                        .filter(b -> Long.parseLong(b.getTimestamp()) < cutoff)
                        .collect(Collectors.toList());
                if (htfSlice.size() >= 5) {
                    // Daily ATR — used for proper TP/SL sizing in SetupDetector
                    double[] da = atrCalc.computeAtr(htfSlice, Math.min(14, htfSlice.size()-1));
                    for (int i = da.length-1; i >= 0; i--) { if (da[i] > 0) { dailyAtr = da[i]; break; } }
                }
                if (htfSlice.size() >= 20) {
                    double sma = htfSlice.subList(htfSlice.size()-20, htfSlice.size())
                            .stream().mapToDouble(OHLCV::getClose).average().orElse(0);
                    double last = htfSlice.get(htfSlice.size()-1).getClose();
                    htfBias = last > sma * 1.005 ? "bullish" : last < sma * 0.995 ? "bearish" : "neutral";
                }
            }

            // Slide through day's bars — detect first valid setup
            boolean tradePlacedToday = false;
            for (int end = 20; end <= dayBars.size() && !tradePlacedToday; end++) {
                List<OHLCV> window = dayBars.subList(0, end);
                SetupDetector.DetectResult dr = setupDetector.detectSetups(
                        window, htfBias, ticker, false, dailyAtr, true); // backtestMode=true, real dailyAtr for TP/SL
                if (dr.setups().isEmpty()) continue;

                TradeSetup setup = dr.setups().get(0);
                tradePlacedToday = true;

                double entry = setup.getEntry();
                double sl    = setup.getStopLoss();
                double tp    = setup.getTakeProfit();
                String dir   = setup.getDirection();
                String entryTime = toDateTime(dayBars.get(end - 1).getTimestamp());

                // Forward test: rest of today + all of next trading day
                List<OHLCV> fwdBars = new ArrayList<>(dayBars.subList(end, dayBars.size()));
                if (di + 1 < dates.size()) fwdBars.addAll(byDate.get(dates.get(di + 1)));

                String outcome = "EXPIRED";
                String exitTime = null;
                double pnlPct = 0.0;

                for (OHLCV fb : fwdBars) {
                    if ("long".equals(dir)) {
                        if (fb.getLow()  <= sl) { outcome="LOSS"; exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((sl-entry)/entry*100); break; }
                        if (fb.getHigh() >= tp) { outcome="WIN";  exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((tp-entry)/entry*100); break; }
                    } else {
                        if (fb.getHigh() >= sl) { outcome="LOSS"; exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((entry-sl)/entry*100*-1); break; }
                        if (fb.getLow()  <= tp) { outcome="WIN";  exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((entry-tp)/entry*100);  break; }
                    }
                }
                if ("EXPIRED".equals(outcome)) continue; // skip if neither TP nor SL hit

                trades.add(new TradeResult(ticker, dir, entry, sl, tp, outcome, pnlPct,
                        entryTime, exitTime != null ? exitTime : entryTime,
                        setup.getConfidence(), setup.getAtr()));
            }
        }

        log.info("Backtest {} ({} days): {} trades from {} days of 5m data",
                ticker, lookbackDays, trades.size(), byDate.size());
        return BacktestResult.of(ticker, trades, lookbackDays);
    }

    private String toDateTime(String rawTs) {
        try { return Instant.ofEpochMilli(Long.parseLong(rawTs)).atZone(ET).format(DT_FMT) + " ET"; }
        catch (Exception e) { return rawTs; }
    }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ── Result types ──────────────────────────────────────────────────────────

    public record TradeResult(String ticker, String direction, double entry, double sl, double tp,
                               String outcome, double pnlPct,
                               String entryTime, String exitTime,
                               int confidence, double atr) {}

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
            this.avgWinPct  = trades.stream().filter(t -> "WIN".equals(t.outcome()))
                    .mapToDouble(TradeResult::pnlPct).average().orElse(0);
            this.avgLossPct = trades.stream().filter(t -> "LOSS".equals(t.outcome()))
                    .mapToDouble(t -> Math.abs(t.pnlPct())).average().orElse(0);
            this.expectancy = total > 0
                    ? (winRate / 100 * avgWinPct) - ((1 - winRate / 100) * avgLossPct) : 0;
        }

        public static BacktestResult of(String t, List<TradeResult> trades, int days) {
            return new BacktestResult(t, trades, days, null);
        }
        public static BacktestResult empty(String t, String error) {
            return new BacktestResult(t, List.of(), 0, error);
        }
    }
}
