package com.smcscanner.backtest;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.filter.SignalQualityFilter;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.market.MarketContext;
import com.smcscanner.market.MarketContextService;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TickerProfile;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.news.NewsService;
import com.smcscanner.options.OptionsFlowAnalyzer;
import com.smcscanner.strategy.BreakoutStrategyDetector;
import com.smcscanner.strategy.KeyLevelStrategyDetector;
import com.smcscanner.strategy.SetupDetector;
import com.smcscanner.strategy.VwapStrategyDetector;
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

    private final PolygonClient            client;
    private final AtrCalculator            atrCalc;
    private final SetupDetector            setupDetector;
    private final VwapStrategyDetector     vwapDetector;
    private final BreakoutStrategyDetector breakoutDetector;
    private final KeyLevelStrategyDetector keyLevelDetector;
    private final NewsService              newsService;
    private final MarketContextService     marketCtxService;
    private final SignalQualityFilter      qualityFilter;
    private final ScannerConfig            config;
    private final OptionsFlowAnalyzer      optionsAnalyzer;

    public BacktestService(PolygonClient client, AtrCalculator atrCalc, SetupDetector setupDetector,
                           VwapStrategyDetector vwapDetector, BreakoutStrategyDetector breakoutDetector,
                           KeyLevelStrategyDetector keyLevelDetector, NewsService newsService,
                           MarketContextService marketCtxService, SignalQualityFilter qualityFilter,
                           ScannerConfig config, OptionsFlowAnalyzer optionsAnalyzer) {
        this.client = client; this.atrCalc = atrCalc; this.setupDetector = setupDetector;
        this.vwapDetector = vwapDetector; this.breakoutDetector = breakoutDetector;
        this.keyLevelDetector = keyLevelDetector; this.newsService = newsService;
        this.marketCtxService = marketCtxService; this.qualityFilter = qualityFilter;
        this.config = config; this.optionsAnalyzer = optionsAnalyzer;
    }

    public BacktestResult run(String ticker, int lookbackDays) {
        // Fetch 5m bars for the full lookback — one API call gets it all
        List<OHLCV> allBars = client.getBarsWithLookback(ticker, "5m", 50000, lookbackDays);
        if (allBars == null || allBars.size() < 30) {
            return BacktestResult.empty(ticker, "Insufficient data (" + (allBars==null?0:allBars.size()) + " bars)");
        }

        // Group ALL bars by calendar date in ET (including pre-market)
        // SMC strategy benefits from pre-market context for swing/sweep detection
        // VWAP and ORB strategies filter to regular session internally
        TreeMap<LocalDate, List<OHLCV>> byDate = new TreeMap<>();
        for (OHLCV bar : allBars) {
            LocalDate d = Instant.ofEpochMilli(Long.parseLong(bar.getTimestamp())).atZone(ET).toLocalDate();
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(bar);
        }

        // Fetch daily bars: 200 bars (~10 months) so keylevel detector has enough
        // history even when backtesting 90+ days into the past
        List<OHLCV> dailyBars = client.getBars(ticker, "1d", 200);

        // Fetch 15m bars for the full backtest period — used to compute 15m trend
        // bias at each entry point, mirroring the live scanner's alignment check.
        List<OHLCV> all15mBars = ticker.startsWith("X:") ? List.of()
                : client.getBarsWithLookback(ticker, "15m", 10000, lookbackDays + 5);

        // Pre-fetch SPY and VIX bars once for market context computation.
        // getContextAt() slices these in-memory per trade — no extra API calls.
        List<OHLCV> spyBars = marketCtxService.fetchSpyBarsForBacktest(220);
        List<OHLCV> vixBars = marketCtxService.fetchVixBarsForBacktest(220);

        // Per-ticker outcome history for adaptive suppression — mirrors the live
        // AdaptiveSuppressor: bounded to last 6 outcomes so that a win in month 2
        // resets the streak before month 3 (prevents 90-day run from showing fewer
        // trades than a standalone 30-day run due to accumulated early-period losses).
        // LOCAL to this backtest run — does NOT touch adaptive-outcomes.json.
        Map<String, java.util.ArrayDeque<Boolean>> btOutcomes = new HashMap<>();

        List<TradeResult> trades = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());

        for (int di = 0; di < dates.size(); di++) {
            LocalDate date = dates.get(di);
            List<OHLCV> dayBars = byDate.get(date);
            if (dayBars.size() < 20) continue;

            // HTF bias + daily ATR using daily bars up to this date
            String htfBias = "neutral";
            double dailyAtr = 0.0;
            List<OHLCV> htfSlice = List.of(); // exposed for keylevel detector in inner loop
            if (dailyBars != null && !dailyBars.isEmpty()) {
                long cutoff = date.atStartOfDay(ET).toInstant().toEpochMilli();
                htfSlice = dailyBars.stream()
                        .filter(b -> Long.parseLong(b.getTimestamp()) < cutoff)
                        .collect(Collectors.toList());
                if (htfSlice.size() >= 5) {
                    // Daily ATR — used for proper TP/SL sizing in SetupDetector
                    double[] da = atrCalc.computeAtr(htfSlice, Math.min(14, htfSlice.size()-1));
                    for (int i = da.length-1; i >= 0; i--) { if (da[i] > 0) { dailyAtr = da[i]; break; } }
                }
                if (htfSlice.size() >= 20) {
                    double sma  = htfSlice.subList(htfSlice.size()-20, htfSlice.size())
                            .stream().mapToDouble(OHLCV::getClose).average().orElse(0);
                    double last = htfSlice.get(htfSlice.size()-1).getClose();

                    // Primary signal: price vs SMA20 — tightened neutral band to ±0.2%
                    // (was ±0.5%, which left most SNAP-like trending periods as "neutral"
                    //  and allowed counter-trend SMC setups to pass through)
                    htfBias = last > sma * 1.002 ? "bullish"
                            : last < sma * 0.998 ? "bearish"
                            : "neutral";

                    // Secondary signal: 5-day momentum breaks ties on neutral SMA
                    // If price moved > 3% in 5 days the trend is already clear regardless
                    // of SMA lag. This catches early-stage trends before SMA catches up.
                    if ("neutral".equals(htfBias) && htfSlice.size() >= 5) {
                        double prev5 = htfSlice.get(htfSlice.size() - 5).getClose();
                        double mom   = (last - prev5) / prev5;
                        if      (mom >  0.03) htfBias = "bullish";
                        else if (mom < -0.03) htfBias = "bearish";
                    }
                }
            }

            // Slide through day's bars — detect first valid setup
            TickerProfile bp = config.getTickerProfile(ticker);
            String stratType = bp.getStrategyType();
            // Session strategies skip pre-market windows in the loop below
            boolean isSessionStrat = "breakout".equals(stratType)
                                  || "vwap".equals(stratType)
                                  || "keylevel".equals(stratType);
            // Minimum bars before we start checking each strategy
            int minBars = "breakout".equals(stratType) ? 8
                        : "vwap".equals(stratType)     ? 12
                        : "keylevel".equals(stratType) ? 20
                        : 20; // smc
            boolean tradePlacedToday = false;
            for (int end = minBars; end <= dayBars.size() && !tradePlacedToday; end++) {
                // ALL equity strategies: skip pre-market bars — options don't trade before 9:30 ET
                if (!ticker.startsWith("X:")) {
                    ZonedDateTime lastZdt = Instant.ofEpochMilli(
                        Long.parseLong(dayBars.get(end-1).getTimestamp())).atZone(ET);
                    if (lastZdt.toLocalTime().isBefore(LocalTime.of(9, 30))) continue;
                }
                List<OHLCV> window = dayBars.subList(0, end);
                List<TradeSetup> bSetups;
                if ("vwap".equals(stratType)) {
                    bSetups = vwapDetector.detect(window, ticker, dailyAtr);
                } else if ("breakout".equals(stratType)) {
                    bSetups = breakoutDetector.detect(window, ticker, dailyAtr);
                } else if ("keylevel".equals(stratType)) {
                    // Pass daily bars up to this date (htfSlice) as the level-detection source
                    bSetups = keyLevelDetector.detect(window, htfSlice, ticker, dailyAtr);
                } else {
                    SetupDetector.DetectResult dr = setupDetector.detectSetups(
                            window, htfBias, ticker, false, dailyAtr, true); // backtestMode=true, real dailyAtr for TP/SL
                    bSetups = dr.setups();
                }
                if (bSetups.isEmpty()) continue;

                TradeSetup setup = bSetups.get(0);

                // ── Historical context checks (news + market) ────────────────
                long entryEpochMs = Long.parseLong(dayBars.get(end - 1).getTimestamp());

                // ── Time-of-day dead-zone block — mirrors live ScannerService ──
                // 11:xx AM and 1:xx PM ET historically show 0% WR (post-market-open chop,
                // post-lunch drift). Hard block in backtest mirrors the live scanner rule.
                {
                    ZonedDateTime entryZdt = Instant.ofEpochMilli(entryEpochMs).atZone(ET);
                    int entryHour = entryZdt.toLocalTime().getHour();
                    if (!ticker.startsWith("X:") && (entryHour == 11 || entryHour == 13)) {
                        trades.add(new TradeResult(ticker, setup.getDirection(),
                                setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                                "QUALITY_FILTERED", 0.0,
                                toDateTime(dayBars.get(end-1).getTimestamp()),
                                toDateTime(dayBars.get(end-1).getTimestamp()),
                                setup.getConfidence(), setup.getAtr(),
                                0, "⏸ Dead zone 11/13h ET", 0, null,
                                -100, "Time dead-zone (-100)",
                                0, 0, 0, 0));
                        tradePlacedToday = true;
                        continue;
                    }
                }

                // ── 15m alignment check — mirrors live ScannerService logic ──
                // Compute 15m bias using bars strictly before entry timestamp.
                // Block if 15m trend directly opposes the setup direction.
                if (!ticker.startsWith("X:") && !all15mBars.isEmpty()) {
                    List<OHLCV> slice15 = all15mBars.stream()
                            .filter(b -> Long.parseLong(b.getTimestamp()) < entryEpochMs)
                            .collect(java.util.stream.Collectors.toList());
                    if (slice15.size() >= 20) {
                        int sz = slice15.size();
                        double sma15 = slice15.subList(sz - 20, sz).stream()
                                .mapToDouble(OHLCV::getClose).average().orElse(0);
                        double last15 = slice15.get(sz - 1).getClose();
                        String bias15 = last15 > sma15 * 1.002 ? "bullish"
                                      : last15 < sma15 * 0.998 ? "bearish" : "neutral";
                        // 5-bar momentum tiebreaker (same as daily bias logic)
                        if ("neutral".equals(bias15) && sz >= 5) {
                            double prev5 = slice15.get(sz - 5).getClose();
                            double mom = (last15 - prev5) / prev5;
                            if      (mom >  0.015) bias15 = "bullish";
                            else if (mom < -0.015) bias15 = "bearish";
                        }
                        boolean conflicts = ("bullish".equals(bias15) && "short".equals(setup.getDirection()))
                                         || ("bearish".equals(bias15) && "long".equals(setup.getDirection()));
                        if (conflicts) {
                            trades.add(new TradeResult(ticker, setup.getDirection(),
                                    setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                                    "15M_FILTERED", 0.0,
                                    toDateTime(dayBars.get(end-1).getTimestamp()),
                                    toDateTime(dayBars.get(end-1).getTimestamp()),
                                    setup.getConfidence(), setup.getAtr(),
                                    0, "15M_CONFLICT", 0, bias15, 0, bias15,
                                    0, 0, 0, 0));
                            tradePlacedToday = true;
                            continue;
                        }
                    }
                }
                TickerProfile bp2 = config.getTickerProfile(ticker);
                int effectiveMinConf = bp2.resolveMinConfidence(config.getMinConfidence());
                int effectiveMaxConf = bp2.resolveMaxConfidence(); // upper cap for reversed-pattern tickers

                // News: 48h window ending at entry timestamp
                NewsSentiment sentiment = ticker.startsWith("X:") ? NewsSentiment.NONE
                        : newsService.getSentimentAt(ticker, entryEpochMs);
                int newsAdj = sentiment.confidenceDelta(setup.getDirection());

                // News-aligned TP extension: 1.5:1 → 3:1 R:R
                // Mirrors live scanner logic: when news aligns with direction, widen TP
                if (sentiment.isAligned(setup.getDirection()) && !ticker.startsWith("X:")) {
                    double risk  = Math.abs(setup.getEntry() - setup.getStopLoss());
                    double tp3x  = "long".equals(setup.getDirection())
                            ? Math.round((setup.getEntry() + risk * 3.0) * 10000.0) / 10000.0
                            : Math.round((setup.getEntry() - risk * 3.0) * 10000.0) / 10000.0;
                    setup = TradeSetup.builder()
                            .ticker(setup.getTicker()).direction(setup.getDirection())
                            .entry(setup.getEntry()).stopLoss(setup.getStopLoss()).takeProfit(tp3x)
                            .confidence(setup.getConfidence()).session(setup.getSession()).volatility(setup.getVolatility())
                            .atr(setup.getAtr()).hasBos(setup.isHasBos()).hasChoch(setup.isHasChoch())
                            .fvgTop(setup.getFvgTop()).fvgBottom(setup.getFvgBottom()).timestamp(setup.getTimestamp())
                            .build();
                }

                // Market context: SPY RS + VIX regime as-of entry date
                MarketContext context = ticker.startsWith("X:") ? MarketContext.NONE
                        : marketCtxService.getContextAt(ticker, dailyBars, spyBars, vixBars, entryEpochMs);
                int ctxAdj = context.confidenceDelta(setup.getDirection(), stratType);

                // Signal quality: R:R + time-of-day + consecutive loss streak
                // Streak = consecutive losses at the tail of the last-6-outcomes window
                java.util.ArrayDeque<Boolean> hist = btOutcomes.getOrDefault(ticker, new java.util.ArrayDeque<>());
                Boolean[] histArr = hist.toArray(new Boolean[0]);
                int streak = 0;
                for (int k = histArr.length - 1; k >= 0; k--) {
                    if (!histArr[k]) streak++;
                    else break;
                }
                int qualityAdj = ticker.startsWith("X:") ? 0
                        : qualityFilter.computeDelta(setup, entryEpochMs, streak);
                String qualityLabel = qualityFilter.buildLabel(setup, entryEpochMs, streak);

                int totalAdj = newsAdj + ctxAdj + qualityAdj;
                int adjConf  = Math.max(0, Math.min(100, setup.getConfidence() + totalAdj));

                // Skip trade if combined filters knocked confidence below threshold
                if (adjConf < effectiveMinConf) {  // filter any setup below threshold, regardless of adjustment direction
                    String filteredOutcome = (newsAdj < 0 && (ctxAdj < 0 || qualityAdj < 0)) ? "MULTI_FILTERED"
                                           : newsAdj < 0    ? "NEWS_FILTERED"
                                           : qualityAdj < 0 ? "QUALITY_FILTERED"
                                           :                   "CTX_FILTERED";
                    String filteredLabel = buildFilterLabel(sentiment.label(), context.rsLabel(), qualityLabel);
                    trades.add(new TradeResult(ticker, setup.getDirection(),
                            setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                            filteredOutcome, 0.0,
                            toDateTime(dayBars.get(end - 1).getTimestamp()), toDateTime(dayBars.get(end - 1).getTimestamp()),
                            adjConf, setup.getAtr(), newsAdj, sentiment.label(), ctxAdj, context.rsLabel(),
                            qualityAdj, filteredLabel,
                            0, 0, 0, 0)); // no options P&L for filtered trades
                    tradePlacedToday = true;
                    continue;
                }

                // Over-extended gate: skip signals above per-ticker maxConfidence
                // Reverses the reversed-confidence pattern (PLTR/SOFI/NFLX: 85+ underperforms 75-84)
                if (adjConf > effectiveMaxConf) {
                    trades.add(new TradeResult(ticker, setup.getDirection(),
                            setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                            "OVEREXTENDED_FILTERED", 0.0,
                            toDateTime(dayBars.get(end - 1).getTimestamp()), toDateTime(dayBars.get(end - 1).getTimestamp()),
                            adjConf, setup.getAtr(), newsAdj, sentiment.label(), ctxAdj, context.rsLabel(),
                            qualityAdj, "CONF_CAP",
                            0, 0, 0, 0));
                    tradePlacedToday = true;
                    continue;
                }

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

                // Breakeven stop: once price reaches 1:1 (reward = risk), SL moves to entry.
                // Trades that go in our direction then reverse exit at 0% instead of -1x.
                // This fixes avg-loss > avg-win imbalance without changing the strategy signal.
                double risk       = Math.abs(entry - sl);
                double beLevel    = "long".equals(dir) ? entry + risk : entry - risk; // 1:1 price
                boolean beActive  = false;

                for (OHLCV fb : fwdBars) {
                    double hi = fb.getHigh(), lo = fb.getLow();

                    // Activate breakeven stop the moment 1:1 is touched
                    if (!beActive) {
                        if ("long".equals(dir)  && hi >= beLevel) beActive = true;
                        if ("short".equals(dir) && lo <= beLevel) beActive = true;
                    }

                    double activeSl = beActive ? entry : sl;

                    if ("long".equals(dir)) {
                        if (lo <= activeSl) { outcome = beActive ? "BE_STOP" : "LOSS"; exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((activeSl-entry)/entry*100); break; }
                        if (hi >= tp)       { outcome = "WIN";     exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((tp-entry)/entry*100);       break; }
                    } else {
                        if (hi >= activeSl) { outcome = beActive ? "BE_STOP" : "LOSS"; exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((entry-activeSl)/entry*100); break; }
                        if (lo <= tp)       { outcome = "WIN";     exitTime=toDateTime(fb.getTimestamp()); pnlPct=round2((entry-tp)/entry*100);       break; }
                    }
                }

                // Time-based stop: if neither TP nor SL was hit within 2 trading days,
                // exit at the close of the last available forward bar (market-close exit).
                // Previously these were silently skipped as "EXPIRED", which overstated
                // win rates by hiding neutral-to-losing open positions.
                if ("EXPIRED".equals(outcome)) {
                    if (fwdBars.isEmpty()) continue; // truly no forward data — skip
                    OHLCV lastFwd = fwdBars.get(fwdBars.size() - 1);
                    double exitPrice = lastFwd.getClose();
                    exitTime = toDateTime(lastFwd.getTimestamp());
                    pnlPct   = "long".equals(dir)
                            ? round2((exitPrice - entry) / entry * 100)
                            : round2((entry - exitPrice) / entry * 100);
                    outcome  = "TIMEOUT"; // distinguishable from TP/SL exits in the trade list
                }

                // Update bounded outcome history (max 6 entries — same as live AdaptiveSuppressor)
                boolean tradeWon = "WIN".equals(outcome) || ("TIMEOUT".equals(outcome) && pnlPct > 0);
                java.util.ArrayDeque<Boolean> h = btOutcomes.computeIfAbsent(ticker, k -> new java.util.ArrayDeque<>());
                h.addLast(tradeWon);
                if (h.size() > 6) h.pollFirst();

                // Estimate options P&L using delta model (no historical options data available)
                double exitPrice = "long".equals(dir)
                        ? entry * (1 + pnlPct / 100.0)
                        : entry * (1 - pnlPct / 100.0);
                double holdDays = 1.0; // most intraday setups resolve within 1 trading day
                OptionsFlowAnalyzer.BacktestOptionsEstimate optEst =
                        optionsAnalyzer.estimateBacktestOptionsPnl(entry, exitPrice, dir, holdDays, setup.getAtr());

                trades.add(new TradeResult(ticker, dir, entry, sl, tp, outcome, pnlPct,
                        entryTime, exitTime != null ? exitTime : entryTime,
                        adjConf, setup.getAtr(), newsAdj, sentiment.label(),
                        ctxAdj, buildContextLabel(context),
                        qualityAdj, qualityLabel,
                        optEst.entryPremium(), optEst.exitPremium(),
                        optEst.pnlPerContract(), optEst.optionsPnlPct()));
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

    /** Compact context label for the trade log (e.g. "RS+2.4% | VIX 28.1 volatile"). */
    private String buildContextLabel(MarketContext ctx) {
        if (ctx == null || ctx == MarketContext.NONE) return null;
        StringBuilder sb = new StringBuilder();
        if (ctx.rsScore() != 0) sb.append(String.format("RS%+.1f%%", ctx.rsScore() * 100));
        if (ctx.vixLevel() > 0) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.format("VIX %.1f (%s)", ctx.vixLevel(), ctx.vixRegime()));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Label shown on filtered rows — combines news and context reasons. */
    private String buildFilterLabel(String newsLabel, String rsLabel, String vixLabel) {
        StringBuilder sb = new StringBuilder();
        if (newsLabel != null) sb.append(newsLabel);
        if (rsLabel   != null) { if (sb.length() > 0) sb.append(" | "); sb.append(rsLabel); }
        if (vixLabel  != null) { if (sb.length() > 0) sb.append(" | "); sb.append(vixLabel); }
        return sb.length() > 0 ? sb.toString() : "filtered";
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record TradeResult(String ticker, String direction, double entry, double sl, double tp,
                               String outcome, double pnlPct,
                               String entryTime, String exitTime,
                               int confidence, double atr,
                               int newsAdjustment,    // signed delta from news        (e.g. -8)
                               String newsLabel,      // e.g. "🔴 Strong bearish news"
                               int ctxAdjustment,     // signed delta from RS+VIX      (e.g. -6)
                               String ctxLabel,       // e.g. "RS+4.2% | VIX 18.5"
                               int qualityAdjustment, // signed delta from R:R+time+streak (e.g. -15)
                               String qualityLabel,   // e.g. "R:R 1.2 (-10) | 3-loss streak (-15)"
                               // Options P&L estimate (delta model)
                               double optEntryPremium,  // estimated entry premium per share
                               double optExitPremium,   // estimated exit premium per share
                               double optPnlPerContract,// profit/loss per 1 contract (×100)
                               double optPnlPct         // percentage return on premium invested
    ) {}

    public static class BacktestResult {
        public final String ticker;
        public final List<TradeResult> trades;
        public final int lookbackDays;
        public final String error;
        public final int wins, losses, beStops, timeouts, newsFiltered, ctxFiltered, qualityFiltered, total;
        public final double winRate, avgWinPct, avgLossPct, expectancy;
        // Options aggregate stats
        public final double totalOptPnl;        // sum of P&L across all contracts (1 contract each)
        public final double avgOptWinPnl;       // avg $ profit per winning contract
        public final double avgOptLossPnl;      // avg $ loss per losing contract
        public final double optExpectancy;      // avg $ per trade (options)
        public final double optTotalReturn;     // % return on total premium invested
        // Confidence bucket analysis: shows WR and expectancy by score tier
        // Key insight: if 85+ WR >> 75-84 WR, raise min confidence threshold
        public final int    bucket85PlusTotal,  bucket85PlusWins;
        public final int    bucket75to84Total,  bucket75to84Wins;
        public final int    bucketBelow75Total, bucketBelow75Wins;
        public final double bucket85PlusWR,     bucket75to84WR,    bucketBelow75WR;
        public final double bucket85PlusExp,    bucket75to84Exp,   bucketBelow75Exp;

        private BacktestResult(String ticker, List<TradeResult> trades, int lookbackDays, String error) {
            this.ticker = ticker; this.trades = trades;
            this.lookbackDays = lookbackDays; this.error = error;

            // Filtered trades are excluded from win/loss stats — never actually entered.
            this.newsFiltered = (int) trades.stream().filter(t -> "NEWS_FILTERED".equals(t.outcome())).count();
            this.ctxFiltered  = (int) trades.stream().filter(t ->
                    "CTX_FILTERED".equals(t.outcome())).count();
            this.qualityFiltered = (int) trades.stream().filter(t ->
                    "QUALITY_FILTERED".equals(t.outcome()) || "MULTI_FILTERED".equals(t.outcome())
                    || "15M_FILTERED".equals(t.outcome())  || "CORR_FILTERED".equals(t.outcome())
                    || "REGIME_FILTERED".equals(t.outcome())).count();

            // TIMEOUT trades are counted as wins or losses based on their PnL sign.
            // BE_STOP trades exit at breakeven (0% PnL) — counted separately, not wins or losses.
            this.wins     = (int) trades.stream().filter(t ->
                    "WIN".equals(t.outcome()) || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() > 0)).count();
            this.losses   = (int) trades.stream().filter(t ->
                    "LOSS".equals(t.outcome()) || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() <= 0)).count();
            this.beStops  = (int) trades.stream().filter(t -> "BE_STOP".equals(t.outcome())).count();
            this.timeouts = (int) trades.stream().filter(t -> "TIMEOUT".equals(t.outcome())).count();
            this.total    = wins + losses + beStops;
            int decidedTrades = wins + losses; // BE_STOP excluded from win rate (0% P&L, not a win or loss)
            this.winRate  = decidedTrades > 0 ? Math.round(wins * 100.0 / decidedTrades * 10) / 10.0 : 0;
            this.avgWinPct  = trades.stream().filter(t ->
                    "WIN".equals(t.outcome()) || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() > 0))
                    .mapToDouble(TradeResult::pnlPct).average().orElse(0);
            this.avgLossPct = trades.stream().filter(t ->
                    "LOSS".equals(t.outcome()) || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() <= 0))
                    .mapToDouble(t -> Math.abs(t.pnlPct())).average().orElse(0);
            this.expectancy = total > 0
                    ? (winRate / 100 * avgWinPct) - ((1 - winRate / 100) * avgLossPct) : 0;

            // ── Options aggregate P&L ────────────────────────────────────────
            // Only count executed trades (not filtered)
            List<TradeResult> executed = trades.stream()
                    .filter(t -> !"NEWS_FILTERED".equals(t.outcome())
                              && !"CTX_FILTERED".equals(t.outcome())
                              && !"QUALITY_FILTERED".equals(t.outcome())
                              && !"MULTI_FILTERED".equals(t.outcome())
                              && !"15M_FILTERED".equals(t.outcome())
                              && !"CORR_FILTERED".equals(t.outcome())
                              && !"REGIME_FILTERED".equals(t.outcome()))
                    .toList();

            this.totalOptPnl = executed.stream().mapToDouble(TradeResult::optPnlPerContract).sum();
            this.avgOptWinPnl = executed.stream()
                    .filter(t -> t.optPnlPerContract() > 0)
                    .mapToDouble(TradeResult::optPnlPerContract).average().orElse(0);
            this.avgOptLossPnl = executed.stream()
                    .filter(t -> t.optPnlPerContract() <= 0)
                    .mapToDouble(t -> Math.abs(t.optPnlPerContract())).average().orElse(0);
            this.optExpectancy = executed.isEmpty() ? 0 : totalOptPnl / executed.size();
            double totalPremiumInvested = executed.stream()
                    .mapToDouble(t -> t.optEntryPremium() * 100).sum(); // ×100 shares per contract
            this.optTotalReturn = totalPremiumInvested > 0
                    ? Math.round(totalOptPnl / totalPremiumInvested * 100 * 10) / 10.0 : 0;

            // ── Confidence bucket analysis ────────────────────────────────────
            // Excludes filtered trades — only look at actually-executed setups.
            // Counts wins per bucket using the same WIN/TIMEOUT>0 logic as global WR.
            java.util.function.Predicate<TradeResult> isWin = t ->
                    "WIN".equals(t.outcome()) || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() > 0);
            java.util.function.Predicate<TradeResult> isExecuted = t ->
                    !"NEWS_FILTERED".equals(t.outcome()) && !"CTX_FILTERED".equals(t.outcome())
                    && !"QUALITY_FILTERED".equals(t.outcome()) && !"MULTI_FILTERED".equals(t.outcome())
                    && !"15M_FILTERED".equals(t.outcome()) && !"CORR_FILTERED".equals(t.outcome())
                    && !"REGIME_FILTERED".equals(t.outcome());

            List<TradeResult> b85  = executed.stream().filter(t -> t.confidence() >= 85).toList();
            List<TradeResult> b75  = executed.stream().filter(t -> t.confidence() >= 75 && t.confidence() < 85).toList();
            List<TradeResult> bLow = executed.stream().filter(t -> t.confidence() <  75).toList();

            this.bucket85PlusTotal  = b85.size();
            this.bucket85PlusWins   = (int) b85.stream().filter(isWin).count();
            this.bucket75to84Total  = b75.size();
            this.bucket75to84Wins   = (int) b75.stream().filter(isWin).count();
            this.bucketBelow75Total = bLow.size();
            this.bucketBelow75Wins  = (int) bLow.stream().filter(isWin).count();

            this.bucket85PlusWR  = bucket85PlusTotal  > 0 ? Math.round(bucket85PlusWins  * 100.0 / bucket85PlusTotal  * 10) / 10.0 : 0;
            this.bucket75to84WR  = bucket75to84Total  > 0 ? Math.round(bucket75to84Wins  * 100.0 / bucket75to84Total  * 10) / 10.0 : 0;
            this.bucketBelow75WR = bucketBelow75Total > 0 ? Math.round(bucketBelow75Wins * 100.0 / bucketBelow75Total * 10) / 10.0 : 0;

            // Expectancy per bucket: WR × avgWin - (1-WR) × avgLoss
            this.bucket85PlusExp  = computeBucketExp(b85,  isWin);
            this.bucket75to84Exp  = computeBucketExp(b75,  isWin);
            this.bucketBelow75Exp = computeBucketExp(bLow, isWin);
        }

        private static double computeBucketExp(List<TradeResult> bucket,
                                               java.util.function.Predicate<TradeResult> isWin) {
            if (bucket.isEmpty()) return 0;
            double avgW = bucket.stream().filter(isWin).mapToDouble(TradeResult::pnlPct)
                    .average().orElse(0);
            double avgL = bucket.stream().filter(isWin.negate())
                    .filter(t -> !"BE_STOP".equals(t.outcome()))
                    .mapToDouble(t -> Math.abs(t.pnlPct())).average().orElse(0);
            long wins   = bucket.stream().filter(isWin).count();
            long losses = bucket.stream().filter(isWin.negate())
                    .filter(t -> !"BE_STOP".equals(t.outcome())).count();
            int decided = (int)(wins + losses);
            if (decided == 0) return 0;
            double wr = (double) wins / decided;
            return Math.round(((wr * avgW) - ((1 - wr) * avgL)) * 100.0) / 100.0;
        }

        public static BacktestResult of(String t, List<TradeResult> trades, int days) {
            return new BacktestResult(t, trades, days, null);
        }
        public static BacktestResult empty(String t, String error) {
            return new BacktestResult(t, List.of(), 0, error);
        }
    }
}
