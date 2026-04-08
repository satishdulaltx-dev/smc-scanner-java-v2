package com.smcscanner.backtest;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.filter.SignalQualityFilter;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.indicator.TechnicalIndicators;
import com.smcscanner.market.MarketContext;
import com.smcscanner.market.MarketContextService;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TickerProfile;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.news.NewsService;
import com.smcscanner.options.OptionsFlowAnalyzer;
import com.smcscanner.strategy.BreakoutStrategyDetector;
import com.smcscanner.strategy.GapDetector;
import com.smcscanner.strategy.GammaPinDetector;
import com.smcscanner.strategy.IndexDivergenceDetector;
import com.smcscanner.strategy.KeyLevelStrategyDetector;
import com.smcscanner.strategy.SetupDetector;
import com.smcscanner.strategy.ThreeDayVwapDetector;
import com.smcscanner.strategy.VolatilitySqueezeDetector;
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
    private static final double ATR_TRAIL_NORMAL = 0.75;
    private static final double ATR_TRAIL_REVERSAL = 0.30;
    private static final int REVERSAL_CLOSES = 2;
    private static final double TRAIL_EXIT_SLIPPAGE_BPS = 5.0; // 0.05% adverse slippage on trail exits
    private static final double HYBRID_BE_R = 1.0;
    private static final double HYBRID_TRAIL_R = 1.5;

    private final PolygonClient            client;
    private final AtrCalculator            atrCalc;
    private final SetupDetector            setupDetector;
    private final VwapStrategyDetector     vwapDetector;
    private final BreakoutStrategyDetector breakoutDetector;
    private final GapDetector              gapDetector;
    private final KeyLevelStrategyDetector  keyLevelDetector;
    private final VolatilitySqueezeDetector vSqueezeDetector;
    private final ThreeDayVwapDetector      vwap3dDetector;
    private final IndexDivergenceDetector   indexDivDetector;
    private final GammaPinDetector          gammaPinDetector;
    private final NewsService              newsService;
    private final MarketContextService     marketCtxService;
    private final SignalQualityFilter      qualityFilter;
    private final ScannerConfig            config;
    private final OptionsFlowAnalyzer      optionsAnalyzer;
    private final TechnicalIndicators      techIndicators;

    public BacktestService(PolygonClient client, AtrCalculator atrCalc, SetupDetector setupDetector,
                           VwapStrategyDetector vwapDetector, BreakoutStrategyDetector breakoutDetector,
                           GapDetector gapDetector,
                           KeyLevelStrategyDetector keyLevelDetector,
                           VolatilitySqueezeDetector vSqueezeDetector, ThreeDayVwapDetector vwap3dDetector,
                           IndexDivergenceDetector indexDivDetector, GammaPinDetector gammaPinDetector,
                           NewsService newsService,
                           MarketContextService marketCtxService, SignalQualityFilter qualityFilter,
                           ScannerConfig config, OptionsFlowAnalyzer optionsAnalyzer,
                           TechnicalIndicators techIndicators) {
        this.client = client; this.atrCalc = atrCalc; this.setupDetector = setupDetector;
        this.vwapDetector = vwapDetector; this.breakoutDetector = breakoutDetector;
        this.gapDetector = gapDetector;
        this.keyLevelDetector = keyLevelDetector;
        this.vSqueezeDetector = vSqueezeDetector; this.vwap3dDetector = vwap3dDetector;
        this.indexDivDetector = indexDivDetector; this.gammaPinDetector = gammaPinDetector;
        this.newsService = newsService;
        this.marketCtxService = marketCtxService; this.qualityFilter = qualityFilter;
        this.config = config; this.optionsAnalyzer = optionsAnalyzer; this.techIndicators = techIndicators;
    }

    public BacktestResult run(String ticker, int lookbackDays) {
        return run(ticker, lookbackDays, BacktestMode.ALL, null, BacktestExitStyle.CLASSIC);
    }

    public BacktestResult run(String ticker, int lookbackDays, BacktestMode mode) {
        return run(ticker, lookbackDays, mode, null, BacktestExitStyle.CLASSIC);
    }

    /** Run backtest with an optional strategy override (ignores ticker-profiles.json strategyType). */
    public BacktestResult run(String ticker, int lookbackDays, BacktestMode mode, String strategyOverride) {
        return run(ticker, lookbackDays, mode, strategyOverride, BacktestExitStyle.CLASSIC);
    }

    /** Run backtest with explicit exit style so classic and live-parity exits can be compared side by side. */
    public BacktestResult run(String ticker, int lookbackDays, BacktestMode mode, String strategyOverride, BacktestExitStyle exitStyle) {
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
            LocalDate d = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET).toLocalDate();
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(bar);
        }

        // Fetch daily bars: 450 bars (~18 months) so SMA 200 + keylevel detector
        // have enough history even when backtesting 180+ days into the past
        List<OHLCV> dailyBars = client.getBars(ticker, "1d", 450);

        // Fetch 15m bars for the full backtest period — used to compute 15m trend
        // bias at each entry point, mirroring the live scanner's alignment check.
        List<OHLCV> all15mBars = ticker.startsWith("X:") ? List.of()
                : client.getBarsWithLookback(ticker, "15m", 10000, lookbackDays + 5);

        // Pre-fetch SPY and VIX bars once for market context computation.
        // getContextAt() slices these in-memory per trade — no extra API calls.
        List<OHLCV> spyBars = marketCtxService.fetchSpyBarsForBacktest(220);
        List<OHLCV> vixBars = marketCtxService.fetchVixBarsForBacktest(220);

        // Pre-fetch SPY 5m bars for intraday RS gate (only if this ticker uses it)
        TickerProfile preProfile = config.getTickerProfile(ticker);
        boolean needsSpy5m = preProfile.isIntradayRsGate()
                || "idiv".equals(preProfile.getStrategyType())
                || "idiv".equals(strategyOverride);
        List<OHLCV> spy5mBars = needsSpy5m
                ? client.getBarsWithLookback("SPY", "5m", 50000, lookbackDays + 5)
                : List.of();
        // Group SPY 5m bars by date for per-day slicing
        TreeMap<LocalDate, List<OHLCV>> spy5mByDate = new TreeMap<>();
        for (OHLCV bar : spy5mBars) {
            LocalDate d = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET).toLocalDate();
            spy5mByDate.computeIfAbsent(d, k -> new ArrayList<>()).add(bar);
        }

        // Pre-fetch correlated asset 15m bars for COIN/MARA (→BTC) and AMD/SMCI (→NVDA).
        // Mirrors live ScannerService cross-asset correlation check.
        String corrAssetTicker = null;
        int corrConflictPenalty = 0, corrAgreementBonus = 0;
        if (!ticker.startsWith("X:")) {
            if (ticker.equals("COIN") || ticker.equals("MARA")) {
                corrAssetTicker = "X:BTCUSD"; corrConflictPenalty = -20; corrAgreementBonus = +5;
            } else if (ticker.equals("AMD") || ticker.equals("SMCI")) {
                corrAssetTicker = "NVDA"; corrConflictPenalty = -15; corrAgreementBonus = +5;
            }
        }
        List<OHLCV> corrAsset15m = (corrAssetTicker != null)
                ? client.getBarsWithLookback(corrAssetTicker, "15m", 10000, lookbackDays + 5)
                : List.of();

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
                        .filter(b -> b.getTimestamp() < cutoff)
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
            String stratType = (strategyOverride != null && !strategyOverride.isBlank())
                    ? strategyOverride : bp.getStrategyType();
            // Session strategies skip pre-market windows in the loop below
            boolean isSessionStrat = "breakout".equals(stratType)
                                  || "vwap".equals(stratType)
                                  || "keylevel".equals(stratType)
                                  || "gap".equals(stratType)
                                  || "vsqueeze".equals(stratType)
                                  || "vwap3d".equals(stratType)
                                  || "idiv".equals(stratType)
                                  || "gammapin".equals(stratType);
            // Minimum bars before we start checking each strategy
            int minBars = "breakout".equals(stratType)  ? 8
                        : "vwap".equals(stratType)      ? 12
                        : "keylevel".equals(stratType)  ? 20
                        : "gap".equals(stratType)       ? 1
                        : "vsqueeze".equals(stratType)  ? 25
                        : "vwap3d".equals(stratType)    ? 20
                        : "idiv".equals(stratType)      ? 12
                        : "gammapin".equals(stratType)  ? 15
                        : 20; // smc
            // Build previous 2 days' bars for 3-day VWAP strategy (computed once per day)
            final List<OHLCV> prevDaysBars;
            if ("vwap3d".equals(stratType)) {
                List<OHLCV> prev = new ArrayList<>();
                for (int k = Math.max(0, di - 2); k < di; k++) prev.addAll(byDate.get(dates.get(k)));
                prevDaysBars = prev;
            } else {
                prevDaysBars = List.of();
            }
            boolean tradePlacedToday = false;
            for (int end = minBars; end <= dayBars.size() && !tradePlacedToday; end++) {
                // ALL equity strategies: skip pre-market bars — options don't trade before 9:30 ET
                if (!ticker.startsWith("X:")) {
                    ZonedDateTime lastZdt = Instant.ofEpochMilli(
                        dayBars.get(end-1).getTimestamp()).atZone(ET);
                    if (lastZdt.toLocalTime().isBefore(LocalTime.of(9, 30))) continue;
                }
                List<OHLCV> window = dayBars.subList(0, end);
                List<TradeSetup> bSetups;
                if ("vwap".equals(stratType)) {
                    bSetups = vwapDetector.detect(window, ticker, dailyAtr);
                } else if ("breakout".equals(stratType)) {
                    bSetups = breakoutDetector.detect(window, ticker, dailyAtr);
                } else if ("gap".equals(stratType)) {
                    List<OHLCV> rthWindow = window.stream()
                            .filter(this::isRegularSessionBar)
                            .collect(Collectors.toList());
                    if (rthWindow.size() != 1 || di == 0) {
                        bSetups = List.of();
                    } else {
                        List<OHLCV> prevRthBars = byDate.getOrDefault(dates.get(di - 1), List.of()).stream()
                                .filter(this::isRegularSessionBar)
                                .collect(Collectors.toList());
                        GapDetector.GapSignal gap = gapDetector.detect(rthWindow, prevRthBars, dailyAtr, ticker);
                        bSetups = gap == null ? List.of() : List.of(buildGapSetup(gap, rthWindow.get(0)));
                    }
                } else if ("keylevel".equals(stratType)) {
                    // Pass daily bars up to this date (htfSlice) as the level-detection source
                    bSetups = keyLevelDetector.detect(window, htfSlice, ticker, dailyAtr, bp);
                } else if ("vsqueeze".equals(stratType)) {
                    bSetups = vSqueezeDetector.detect(window, ticker, dailyAtr);
                } else if ("vwap3d".equals(stratType)) {
                    List<OHLCV> multiDay = new ArrayList<>(prevDaysBars);
                    multiDay.addAll(window);
                    bSetups = vwap3dDetector.detect(multiDay, ticker, dailyAtr);
                } else if ("idiv".equals(stratType)) {
                    long entryTs = window.get(window.size() - 1).getTimestamp();
                    List<OHLCV> spySlice = spy5mByDate.getOrDefault(date, List.of()).stream()
                            .filter(b -> b.getTimestamp() <= entryTs)
                            .collect(java.util.stream.Collectors.toList());
                    bSetups = indexDivDetector.detect(window, spySlice, ticker, dailyAtr);
                } else if ("gammapin".equals(stratType)) {
                    bSetups = gammaPinDetector.detect(window, ticker, dailyAtr);
                } else {
                    SetupDetector.DetectResult dr = setupDetector.detectSetups(
                            window, htfBias, ticker, false, dailyAtr, true); // backtestMode=true, real dailyAtr for TP/SL
                    bSetups = dr.setups();
                }
                if (bSetups.isEmpty()) continue;

                TradeSetup setup = bSetups.get(0);

                // ── Historical context checks (news + market) ────────────────
                long entryEpochMs = dayBars.get(end - 1).getTimestamp();

                // ── 15m alignment check — matches live ScannerService (soft -15 penalty) ──
                // Compute 15m bias using bars strictly before entry timestamp.
                // SOFT penalty of -15 if 15m trend opposes setup — does NOT hard-block.
                // BYPASS for VWAP/vwap3d: mean-reversion intentionally fights the 15m trend.
                int bias15mAdj = 0;
                String bias15mLabel = "neutral";
                boolean is15mApplicable = !"vwap".equals(stratType) && !"vwap3d".equals(stratType);
                if (is15mApplicable && !ticker.startsWith("X:") && !all15mBars.isEmpty()) {
                    List<OHLCV> slice15 = all15mBars.stream()
                            .filter(b -> b.getTimestamp() < entryEpochMs)
                            .collect(java.util.stream.Collectors.toList());
                    if (slice15.size() >= 20) {
                        int sz = slice15.size();
                        double sma15 = slice15.subList(sz - 20, sz).stream()
                                .mapToDouble(OHLCV::getClose).average().orElse(0);
                        double last15 = slice15.get(sz - 1).getClose();
                        bias15mLabel = last15 > sma15 * 1.002 ? "bullish"
                                     : last15 < sma15 * 0.998 ? "bearish" : "neutral";
                        if ("neutral".equals(bias15mLabel) && sz >= 5) {
                            double prev5 = slice15.get(sz - 5).getClose();
                            double mom = (last15 - prev5) / prev5;
                            if      (mom >  0.015) bias15mLabel = "bullish";
                            else if (mom < -0.015) bias15mLabel = "bearish";
                        }
                        boolean conflicts = ("bullish".equals(bias15mLabel) && "short".equals(setup.getDirection()))
                                         || ("bearish".equals(bias15mLabel) && "long".equals(setup.getDirection()));
                        if (conflicts) bias15mAdj = -15; // soft penalty, mirrors live
                    }
                }

                // ── Intraday RS (soft adjustment) — mirrors live ScannerService ──
                // For mega-caps: confidence bonus when diverging from SPY,
                // penalty when not. Includes absolute trend anchor to prevent
                // "falling knife" longs (buying just because bleeding slower).
                int intradayRsAdj = 0;
                if (bp.isIntradayRsGate() && !ticker.startsWith("X:")) {
                    List<OHLCV> spyDay = spy5mByDate.getOrDefault(date, List.of());
                    List<OHLCV> spyWindow = spyDay.stream()
                            .filter(b -> b.getTimestamp() <= entryEpochMs)
                            .collect(Collectors.toList());
                    double intradayRs = marketCtxService.computeIntradayRsFromBars(window, spyWindow);
                    intradayRsAdj = marketCtxService.computeIntradayRsDelta(intradayRs, window, setup.getDirection());
                }

                TickerProfile bp2 = config.getTickerProfile(ticker);
                int effectiveMinConf = bp2.resolveMinConfidence(config.getMinConfidence());
                int effectiveMaxConf = bp2.resolveMaxConfidence(); // upper cap for reversed-pattern tickers

                // News: 48h window ending at entry timestamp
                NewsSentiment sentiment = ticker.startsWith("X:") ? NewsSentiment.NONE
                        : newsService.getSentimentAt(ticker, entryEpochMs);
                int newsAdj = sentiment.confidenceDelta(setup.getDirection(), stratType);

                // News-aligned TP extension: widen TP to 3:1 R:R (but respect ticker tpRrRatio)
                // If profile sets tpRrRatio (e.g. JPM=1.0), don't override — the low ratio is intentional
                boolean hasTpOverride = bp.getTpRrRatio() != null;
                if (sentiment.isAligned(setup.getDirection()) && !ticker.startsWith("X:") && !hasTpOverride) {
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

                // ── Technical indicator adjustments (SMA 200 + RSI + candle + volume) ──
                // Mirrors the live ScannerService logic for accurate backtesting.
                int sma200Adj = 0, rsiAdj = 0, candleAdj = 0, volAdj = 0;
                if (!ticker.startsWith("X:")) {
                    double currentPrice = window.get(window.size() - 1).getClose();
                    sma200Adj = techIndicators.sma200Delta(htfSlice, currentPrice, setup.getDirection());
                    rsiAdj    = techIndicators.rsiDelta(window, setup.getDirection());
                    candleAdj = techIndicators.candlePatternDelta(window, setup.getDirection());
                    volAdj    = techIndicators.volumeDelta(window);
                }

                // ── Triple alignment bonus/penalty — mirrors live ScannerService ──
                // daily + 15m + setup all agree → +10; all three conflict → -10
                int alignmentAdj = 0;
                if (!ticker.startsWith("X:")) {
                    String dir = setup.getDirection();
                    boolean dailyAligned = ("bullish".equals(htfBias) && "long".equals(dir))
                                       || ("bearish".equals(htfBias) && "short".equals(dir));
                    boolean m15Aligned   = ("bullish".equals(bias15mLabel) && "long".equals(dir))
                                       || ("bearish".equals(bias15mLabel) && "short".equals(dir));
                    if (dailyAligned && m15Aligned) alignmentAdj = +10;
                    else if (!dailyAligned && !m15Aligned
                             && !"neutral".equals(htfBias) && !"neutral".equals(bias15mLabel)) alignmentAdj = -10;
                }

                // ── Options flow (not available in backtest — stub zero) ──────
                // Live uses real-time options chain data; no historical equivalent.
                int flowAdj = 0;

                // ── Regime delta — mirrors AdaptiveSuppressor.getRegimeDelta() ──
                // Rolling win-rate over last 6 outcomes; catches slow degradation
                // invisible to a streak counter (e.g. 3 wins then 3 losses = 50% WR).
                int regimeAdj = 0;
                if (!ticker.startsWith("X:")) {
                    java.util.ArrayDeque<Boolean> regHist = btOutcomes.getOrDefault(ticker, new java.util.ArrayDeque<>());
                    if (regHist.size() >= 4) {
                        long wins = regHist.stream().filter(b -> Boolean.TRUE.equals(b)).count();
                        double wr = (double) wins / regHist.size();
                        if (wr < 0.20) regimeAdj = -25;
                        else if (wr < 0.33) regimeAdj = -15;
                        else if (wr < 0.45) regimeAdj = -8;
                    }
                }

                // ── Cross-asset correlation — mirrors live corrAdj logic ──────
                // COIN/MARA track BTC (-20 conflict / +5 agree).
                // AMD/SMCI track NVDA (-15 conflict / +5 agree).
                int corrAdj = 0;
                if (corrAssetTicker != null && !corrAsset15m.isEmpty()) {
                    List<OHLCV> corrSlice = corrAsset15m.stream()
                            .filter(b -> b.getTimestamp() < entryEpochMs)
                            .collect(Collectors.toList());
                    if (corrSlice.size() >= 10) {
                        int sz = corrSlice.size();
                        double smaCorr = corrSlice.subList(sz - Math.min(20, sz), sz).stream()
                                .mapToDouble(OHLCV::getClose).average().orElse(0);
                        double lastCorr = corrSlice.get(sz - 1).getClose();
                        String corrBias = lastCorr > smaCorr * 1.002 ? "bullish"
                                        : lastCorr < smaCorr * 0.998 ? "bearish" : "neutral";
                        String dir = setup.getDirection();
                        boolean conflicts = ("bullish".equals(corrBias) && "short".equals(dir))
                                         || ("bearish".equals(corrBias) && "long".equals(dir));
                        boolean agrees    = ("bullish".equals(corrBias) && "long".equals(dir))
                                         || ("bearish".equals(corrBias) && "short".equals(dir));
                        if (conflicts) corrAdj = corrConflictPenalty;
                        else if (agrees) corrAdj = corrAgreementBonus;
                    }
                }

                // ── Dead zone penalty — mirrors live deadZoneAdj ──────────────
                // 11:xx AM and 1:xx PM ET have lower historical WR → -15.
                // SignalQualityFilter also penalises lunch (11:30-13:30 → -5); both stack in live.
                int deadZoneAdj = 0;
                if (!ticker.startsWith("X:")) {
                    int entryHour = Instant.ofEpochMilli(entryEpochMs).atZone(ET).toLocalTime().getHour();
                    if (entryHour == 11 || entryHour == 13) deadZoneAdj = -15;
                }

                // ── Late-day filter (after 3:30 PM ET → skip intraday entry) ──
                // Live routes these to swing channel instead of taking intraday.
                // In backtest: skip the entry entirely to match live behaviour.
                if (!ticker.startsWith("X:")) {
                    LocalTime entryLt = Instant.ofEpochMilli(entryEpochMs).atZone(ET).toLocalTime();
                    if (entryLt.getHour() >= 16 || (entryLt.getHour() == 15 && entryLt.getMinute() >= 30)) {
                        log.debug("{} LATE_DAY_SKIP: entry at {} — matches live swing reroute", ticker, entryLt);
                        break; // done for today — live wouldn't take this as intraday
                    }
                }

                int totalAdj = newsAdj + ctxAdj + qualityAdj + flowAdj + regimeAdj + corrAdj + intradayRsAdj + deadZoneAdj + bias15mAdj + alignmentAdj + sma200Adj + rsiAdj + candleAdj + volAdj;
                // Penalty floor: secondary filters can reduce base confidence by at most 25%.
                // A strong base setup (80+) should never be killed by stacking RS + news + quality.
                // Floor = 75% of base confidence. e.g. base=80 → floor=60, base=70 → floor=52.
                int penaltyFloor = (int)(setup.getConfidence() * 0.75);
                int rawAdj = setup.getConfidence() + totalAdj;
                int adjConf  = Math.max(penaltyFloor, Math.min(100, rawAdj));

                // ── VIX-aware dynamic minimum confidence gate ─────────────────
                // Mirrors live: raise min bar by 5 pts above VIX 25, another 5 above 35.
                int vixBoost = 0;
                if (!ticker.startsWith("X:") && context.vixLevel() > 25) vixBoost  = 5;
                if (!ticker.startsWith("X:") && context.vixLevel() > 35) vixBoost += 5;
                int dynamicMinConf = effectiveMinConf + vixBoost;

                // Skip trade if combined filters knocked confidence below threshold
                if (adjConf < dynamicMinConf) {
                    log.debug("{} CONF_FILTERED: base={} news={} ctx={} qual={} iRS={} regime={} corr={} dz={} → adj={} floor={} (min={} vixBoost={})",
                            ticker, setup.getConfidence(), newsAdj, ctxAdj, qualityAdj, intradayRsAdj, regimeAdj, corrAdj, deadZoneAdj, adjConf, penaltyFloor, effectiveMinConf, vixBoost);
                    String filteredOutcome = regimeAdj < -8 ? "REGIME_FILTERED"
                                           : bias15mAdj < 0 && (newsAdj < 0 || ctxAdj < 0 || qualityAdj < 0) ? "MULTI_FILTERED"
                                           : bias15mAdj < 0 ? "15M_FILTERED"
                                           : (newsAdj < 0 && (ctxAdj < 0 || qualityAdj < 0)) ? "MULTI_FILTERED"
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
                            0, 0, 0, 0, 0)); // no options P&L or contracts for filtered trades
                    tradePlacedToday = true;
                    continue;
                }

                // Over-extended gate: skip signals above per-ticker maxConfidence
                // Reverses the reversed-confidence pattern (PLTR/SOFI/NFLX: 85+ underperforms 75-84)
                if (adjConf > effectiveMaxConf) {
                    trades.add(new TradeResult(ticker, setup.getDirection(),
                            setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                            "CONF_CAP_FILTERED", 0.0,
                            toDateTime(dayBars.get(end - 1).getTimestamp()), toDateTime(dayBars.get(end - 1).getTimestamp()),
                            adjConf, setup.getAtr(), newsAdj, sentiment.label(), ctxAdj, context.rsLabel(),
                            qualityAdj, "CONF_CAP",
                            0, 0, 0, 0, 0));
                    tradePlacedToday = true;
                    continue;
                }

                // ── Conviction-based contract count — mirrors live ScannerService ──
                // 90+ → 3 contracts, 82-89 → 2, else → 1. Same tiers as suggestedOverride.
                int contracts;
                if      (adjConf >= 90) contracts = 3;
                else if (adjConf >= 82) contracts = 2;
                else                   contracts = 1;
                log.debug("{} CONVICTION: conf={} → {} contract(s)", ticker, adjConf, contracts);

                tradePlacedToday = true;

                double entry = setup.getEntry();
                double sl    = setup.getStopLoss();
                double tp    = setup.getTakeProfit();
                String dir   = setup.getDirection();
                String entryTime = toDateTime(dayBars.get(end - 1).getTimestamp());

                // Forward test: rest of today + all of next trading day
                // Filter to regular session only (9:30 AM–4:00 PM ET) — pre-market/after-hours
                // bars have thin liquidity and unrealistic fills; exits there are not executable.
                List<OHLCV> fwdBarsRaw = new ArrayList<>(dayBars.subList(end, dayBars.size()));
                if (di + 1 < dates.size()) fwdBarsRaw.addAll(byDate.get(dates.get(di + 1)));
                List<OHLCV> fwdBars = new ArrayList<>();
                for (OHLCV fb : fwdBarsRaw) {
                    ZonedDateTime fbZdt = Instant.ofEpochMilli(fb.getTimestamp()).atZone(ET);
                    LocalTime fbTime = fbZdt.toLocalTime();
                    if (!fbTime.isBefore(LocalTime.of(9, 30)) && fbTime.isBefore(LocalTime.of(16, 0))) {
                        fwdBars.add(fb);
                    }
                }
                // Mode-specific hold limit: INTRADAY/OPTIONS cap at 48 bars (~4h)
                if (fwdBars.size() > mode.maxForwardBars()) {
                    fwdBars = fwdBars.subList(0, mode.maxForwardBars());
                }

                ExitResult exit = switch (exitStyle) {
                    case LIVE_PARITY -> simulateLiveParityExit(window, fwdBars, entry, sl, tp, dir);
                    case HYBRID -> simulateHybridExit(window, fwdBars, entry, sl, tp, dir);
                    case CLASSIC -> simulateClassicExit(fwdBars, entry, sl, tp, dir);
                };
                if (exit == null) continue;
                String outcome = exit.outcome();
                String exitTime = exit.exitTime();
                double pnlPct = exit.pnlPct();

                // Update bounded outcome history (max 6 entries — same as live AdaptiveSuppressor)
                if (!"BE_STOP".equals(outcome)) {
                    boolean tradeWon = "WIN".equals(outcome)
                            || "TRAIL_WIN".equals(outcome)
                            || ("TIMEOUT".equals(outcome) && pnlPct > 0);
                    java.util.ArrayDeque<Boolean> h = btOutcomes.computeIfAbsent(ticker, k -> new java.util.ArrayDeque<>());
                    h.addLast(tradeWon);
                    if (h.size() > 6) h.pollFirst();
                }

                // Estimate options P&L using delta model (no historical options data available)
                // pnlPerContract is for 1 contract; scale by conviction-sized count.
                double exitPrice = "long".equals(dir)
                        ? entry * (1 + pnlPct / 100.0)
                        : entry * (1 - pnlPct / 100.0);
                double holdDays = 1.0; // most intraday setups resolve within 1 trading day
                OptionsFlowAnalyzer.BacktestOptionsEstimate optEst =
                        optionsAnalyzer.estimateBacktestOptionsPnl(entry, exitPrice, dir, holdDays, setup.getAtr());
                double scaledPnlPerContract = round2(optEst.pnlPerContract() * contracts);

                trades.add(new TradeResult(ticker, dir, entry, sl, tp, outcome, pnlPct,
                        entryTime, exitTime != null ? exitTime : entryTime,
                        adjConf, setup.getAtr(), newsAdj, sentiment.label(),
                        ctxAdj, buildContextLabel(context),
                        qualityAdj, qualityLabel,
                        optEst.entryPremium(), optEst.exitPremium(),
                        scaledPnlPerContract, optEst.optionsPnlPct(),
                        contracts));
            }
        }

        log.info("Backtest {} ({} days, mode={}): {} trades from {} days of 5m data",
                ticker, lookbackDays, mode, trades.size(), byDate.size());
        return BacktestResult.of(ticker, trades, lookbackDays, mode);
    }

    private ExitResult simulateClassicExit(List<OHLCV> fwdBars, double entry, double sl, double tp, String dir) {
        String outcome = "EXPIRED";
        String exitTime = null;
        double pnlPct = 0.0;
        double risk = Math.abs(entry - sl);
        double beLevel = "long".equals(dir) ? entry + risk : entry - risk;
        boolean beActive = false;

        for (OHLCV fb : fwdBars) {
            double hi = fb.getHigh(), lo = fb.getLow();

            if (!beActive) {
                if ("long".equals(dir) && hi >= beLevel) beActive = true;
                if ("short".equals(dir) && lo <= beLevel) beActive = true;
            }

            double activeSl = beActive ? entry : sl;

            if ("long".equals(dir)) {
                if (lo <= activeSl) { outcome = beActive ? "BE_STOP" : "LOSS"; exitTime = toDateTime(fb.getTimestamp()); pnlPct = round2((activeSl - entry) / entry * 100); break; }
                if (hi >= tp)       { outcome = "WIN"; exitTime = toDateTime(fb.getTimestamp()); pnlPct = round2((tp - entry) / entry * 100); break; }
            } else {
                if (hi >= activeSl) { outcome = beActive ? "BE_STOP" : "LOSS"; exitTime = toDateTime(fb.getTimestamp()); pnlPct = round2((entry - activeSl) / entry * 100); break; }
                if (lo <= tp)       { outcome = "WIN"; exitTime = toDateTime(fb.getTimestamp()); pnlPct = round2((entry - tp) / entry * 100); break; }
            }
        }

        if ("EXPIRED".equals(outcome)) {
            if (fwdBars.isEmpty()) return null;
            OHLCV lastFwd = fwdBars.get(fwdBars.size() - 1);
            double exitPrice = lastFwd.getClose();
            exitTime = toDateTime(lastFwd.getTimestamp());
            pnlPct = "long".equals(dir)
                    ? round2((exitPrice - entry) / entry * 100)
                    : round2((entry - exitPrice) / entry * 100);
            outcome = "TIMEOUT";
        }

        return new ExitResult(outcome, exitTime, pnlPct);
    }

    private ExitResult simulateLiveParityExit(List<OHLCV> entryWindow, List<OHLCV> fwdBars,
                                              double entry, double sl, double tp, String dir) {
        if (fwdBars.isEmpty()) return null;
        boolean isLong = "long".equals(dir);
        double activeSl = sl;
        double peakClose = entry;
        int reversalCount = 0;
        List<OHLCV> atrWindow = new ArrayList<>(entryWindow);

        for (OHLCV fb : fwdBars) {
            atrWindow.add(fb);
            if (atrWindow.size() > 20) atrWindow.remove(0);

            double hi = fb.getHigh();
            double lo = fb.getLow();
            double close = fb.getClose();
            double atr = computeAtr5m(atrWindow);
            if (atr <= 0) atr = Math.abs(entry - sl);

            // In live trading, the current stop level exists before this bar completes.
            // If the bar breaches it intrabar, assume we are out before granting any
            // benefit from the bar close or a newly tightened stop.
            boolean stopBreached = isLong ? lo <= activeSl : hi >= activeSl;
            if (stopBreached) {
                double slippedStop = applyTrailSlippage(activeSl, isLong);
                double pnlPct = isLong
                        ? round2((slippedStop - entry) / entry * 100)
                        : round2((entry - slippedStop) / entry * 100);
                String outcome = Math.abs(pnlPct) < 0.05 ? "BE_STOP" : (pnlPct > 0 ? "TRAIL_WIN" : "TRAIL_LOSS");
                return new ExitResult(outcome, toDateTime(fb.getTimestamp()), pnlPct);
            }

            if (isLong && close > peakClose) peakClose = close;
            if (!isLong && close < peakClose) peakClose = close;

            boolean reversalClose = isLong
                    ? close < peakClose - atr * 0.20
                    : close > peakClose + atr * 0.20;
            reversalCount = reversalClose ? reversalCount + 1 : 0;

            double atrMult = reversalCount >= REVERSAL_CLOSES ? ATR_TRAIL_REVERSAL : ATR_TRAIL_NORMAL;
            double targetStop = isLong ? peakClose - atr * atrMult : peakClose + atr * atrMult;
            boolean inProfit = isLong ? targetStop > entry : targetStop < entry;
            boolean improving = isLong ? targetStop > activeSl : targetStop < activeSl;
            if (inProfit && improving) activeSl = targetStop;
        }

        OHLCV lastFwd = fwdBars.get(fwdBars.size() - 1);
        double exitPrice = lastFwd.getClose();
        double pnlPct = isLong
                ? round2((exitPrice - entry) / entry * 100)
                : round2((entry - exitPrice) / entry * 100);
        return new ExitResult("TIMEOUT", toDateTime(lastFwd.getTimestamp()), pnlPct);
    }

    private ExitResult simulateHybridExit(List<OHLCV> entryWindow, List<OHLCV> fwdBars,
                                          double entry, double sl, double tp, String dir) {
        if (fwdBars.isEmpty()) return null;
        boolean isLong = "long".equals(dir);
        double risk = Math.abs(entry - sl);
        double beLevel = isLong ? entry + risk * HYBRID_BE_R : entry - risk * HYBRID_BE_R;
        double trailArmLevel = isLong ? entry + risk * HYBRID_TRAIL_R : entry - risk * HYBRID_TRAIL_R;

        boolean beActive = false;
        boolean trailActive = false;
        double activeSl = sl;
        double peakClose = entry;
        int reversalCount = 0;
        List<OHLCV> atrWindow = new ArrayList<>(entryWindow);

        for (OHLCV fb : fwdBars) {
            atrWindow.add(fb);
            if (atrWindow.size() > 20) atrWindow.remove(0);

            double hi = fb.getHigh();
            double lo = fb.getLow();
            double close = fb.getClose();

            double currentStop = trailActive ? activeSl : (beActive ? entry : sl);
            boolean stopBreached = isLong ? lo <= currentStop : hi >= currentStop;
            if (stopBreached) {
                double exitPrice = trailActive ? applyTrailSlippage(currentStop, isLong) : currentStop;
                double pnlPct = isLong
                        ? round2((exitPrice - entry) / entry * 100)
                        : round2((entry - exitPrice) / entry * 100);
                String outcome;
                if (trailActive) outcome = pnlPct > 0 ? "TRAIL_WIN" : "TRAIL_LOSS";
                else outcome = beActive ? "BE_STOP" : "LOSS";
                return new ExitResult(outcome, toDateTime(fb.getTimestamp()), pnlPct);
            }

            if (!beActive) {
                if (isLong && hi >= beLevel) beActive = true;
                if (!isLong && lo <= beLevel) beActive = true;
            }

            // Keep the classic large-target payout alive until the trade has moved
            // far enough to justify active trailing.
            if (!trailActive) {
                if (isLong && hi >= tp) {
                    double pnlPct = round2((tp - entry) / entry * 100);
                    return new ExitResult("WIN", toDateTime(fb.getTimestamp()), pnlPct);
                }
                if (!isLong && lo <= tp) {
                    double pnlPct = round2((entry - tp) / entry * 100);
                    return new ExitResult("WIN", toDateTime(fb.getTimestamp()), pnlPct);
                }
            }

            if (!trailActive) {
                if (isLong && hi >= trailArmLevel) trailActive = true;
                if (!isLong && lo <= trailArmLevel) trailActive = true;
                if (trailActive) {
                    peakClose = close;
                    activeSl = beActive ? entry : sl;
                }
            }

            if (trailActive) {
                if (isLong && close > peakClose) peakClose = close;
                if (!isLong && close < peakClose) peakClose = close;

                double atr = computeAtr5m(atrWindow);
                if (atr <= 0) atr = risk;

                boolean reversalClose = isLong
                        ? close < peakClose - atr * 0.20
                        : close > peakClose + atr * 0.20;
                reversalCount = reversalClose ? reversalCount + 1 : 0;

                double atrMult = reversalCount >= REVERSAL_CLOSES ? ATR_TRAIL_REVERSAL : ATR_TRAIL_NORMAL;
                double targetStop = isLong ? peakClose - atr * atrMult : peakClose + atr * atrMult;
                boolean improving = isLong ? targetStop > activeSl : targetStop < activeSl;
                boolean protectsAtLeastBe = isLong ? targetStop >= entry : targetStop <= entry;
                if (improving && protectsAtLeastBe) activeSl = targetStop;
            }
        }

        OHLCV lastFwd = fwdBars.get(fwdBars.size() - 1);
        double exitPrice = lastFwd.getClose();
        double pnlPct = isLong
                ? round2((exitPrice - entry) / entry * 100)
                : round2((entry - exitPrice) / entry * 100);
        return new ExitResult("TIMEOUT", toDateTime(lastFwd.getTimestamp()), pnlPct);
    }

    private double applyTrailSlippage(double stopPrice, boolean isLong) {
        double slip = stopPrice * (TRAIL_EXIT_SLIPPAGE_BPS / 10_000.0);
        return isLong ? stopPrice - slip : stopPrice + slip;
    }

    private double computeAtr5m(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period < 1) return 0.0;
        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            OHLCV curr = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(),
                    Math.max(Math.abs(curr.getHigh() - prev.getClose()),
                             Math.abs(curr.getLow() - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    private TradeSetup buildGapSetup(GapDetector.GapSignal gap, OHLCV firstSessionBar) {
        boolean isGapAndGo = gap.type() == GapDetector.GapType.GAP_AND_GO;
        double entry = gap.entryPrice();
        double sl = gap.invalidationPrice();
        double risk = Math.abs(entry - sl);
        double tp = isGapAndGo
                ? ("long".equals(gap.direction()) ? entry + risk * 1.5 : entry - risk * 1.5)
                : gap.prevClose();
        tp = Math.round(tp * 10_000.0) / 10_000.0;

        return TradeSetup.builder()
                .ticker(gap.ticker())
                .direction(gap.direction())
                .entry(entry)
                .stopLoss(sl)
                .takeProfit(tp)
                .confidence(gap.confidence())
                .session("NYSE")
                .volatility("gap")
                .atr(gap.dailyAtr())
                .factorBreakdown(gap.note())
                .timestamp(Instant.ofEpochMilli(firstSessionBar.getTimestamp()).atZone(ET).toLocalDateTime())
                .build();
    }

    private boolean isRegularSessionBar(OHLCV bar) {
        ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
        LocalTime time = zdt.toLocalTime();
        return !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(16, 0));
    }

    private record ExitResult(String outcome, String exitTime, double pnlPct) {}

    private static boolean isWinOutcome(TradeResult t) {
        return "WIN".equals(t.outcome())
                || "TRAIL_WIN".equals(t.outcome())
                || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() > 0);
    }

    private static boolean isLossOutcome(TradeResult t) {
        return "LOSS".equals(t.outcome())
                || "TRAIL_LOSS".equals(t.outcome())
                || ("TIMEOUT".equals(t.outcome()) && t.pnlPct() <= 0);
    }

    private String toDateTime(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ET).format(DT_FMT) + " ET";
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
                               // Options P&L estimate (delta model, scaled by contract count)
                               double optEntryPremium,  // estimated entry premium per share
                               double optExitPremium,   // estimated exit premium per share
                               double optPnlPerContract,// profit/loss per 1 contract (×100 shares)
                               double optPnlPct,        // percentage return on premium invested
                               int contracts            // conviction-scaled contract count (1–3)
    ) {}

    public static class BacktestResult {
        public final String ticker;
        public final List<TradeResult> trades;
        public final int lookbackDays;
        public final String error;
        public final BacktestMode mode;
        public final int wins, losses, beStops, timeouts, newsFiltered, ctxFiltered, qualityFiltered, total;
        public final double winRate, avgWinPct, avgLossPct, expectancy;
        // Options aggregate stats (P&L already scaled by conviction contract count)
        public final double totalOptPnl;        // total P&L across all trades (conviction-scaled)
        public final double avgOptWinPnl;       // avg $ profit per winning trade (scaled)
        public final double avgOptLossPnl;      // avg $ loss per losing trade (scaled)
        public final double optExpectancy;      // avg $ per trade (options, scaled)
        public final double optTotalReturn;     // % return on total premium invested
        public final double avgContracts;       // average contracts per executed trade
        // Confidence bucket analysis: shows WR and expectancy by score tier
        // Key insight: if 85+ WR >> 75-84 WR, raise min confidence threshold
        public final int    bucket85PlusTotal,  bucket85PlusWins;
        public final int    bucket75to84Total,  bucket75to84Wins;
        public final int    bucketBelow75Total, bucketBelow75Wins;
        public final double bucket85PlusWR,     bucket75to84WR,    bucketBelow75WR;
        public final double bucket85PlusExp,    bucket75to84Exp,   bucketBelow75Exp;

        private BacktestResult(String ticker, List<TradeResult> trades, int lookbackDays, String error, BacktestMode mode) {
            this.ticker = ticker; this.trades = trades;
            this.lookbackDays = lookbackDays; this.error = error;
            this.mode = mode != null ? mode : BacktestMode.ALL;

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
            this.wins     = (int) trades.stream().filter(BacktestService::isWinOutcome).count();
            this.losses   = (int) trades.stream().filter(BacktestService::isLossOutcome).count();
            this.beStops  = (int) trades.stream().filter(t -> "BE_STOP".equals(t.outcome())).count();
            this.timeouts = (int) trades.stream().filter(t -> "TIMEOUT".equals(t.outcome())).count();
            this.total    = wins + losses + beStops;
            int decidedTrades = wins + losses; // BE_STOP excluded from win rate (0% P&L, not a win or loss)
            this.winRate  = decidedTrades > 0 ? Math.round(wins * 100.0 / decidedTrades * 10) / 10.0 : 0;
            this.avgWinPct  = trades.stream().filter(BacktestService::isWinOutcome)
                    .mapToDouble(TradeResult::pnlPct).average().orElse(0);
            this.avgLossPct = trades.stream().filter(BacktestService::isLossOutcome)
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

            // optPnlPerContract is already conviction-scaled (contracts × per-contract P&L)
            this.totalOptPnl = executed.stream().mapToDouble(TradeResult::optPnlPerContract).sum();
            this.avgOptWinPnl = executed.stream()
                    .filter(t -> t.optPnlPerContract() > 0)
                    .mapToDouble(TradeResult::optPnlPerContract).average().orElse(0);
            this.avgOptLossPnl = executed.stream()
                    .filter(t -> t.optPnlPerContract() <= 0)
                    .mapToDouble(t -> Math.abs(t.optPnlPerContract())).average().orElse(0);
            this.optExpectancy = executed.isEmpty() ? 0 : totalOptPnl / executed.size();
            // Premium invested = entry premium × 100 shares × contracts used
            double totalPremiumInvested = executed.stream()
                    .mapToDouble(t -> t.optEntryPremium() * 100 * Math.max(1, t.contracts())).sum();
            this.optTotalReturn = totalPremiumInvested > 0
                    ? Math.round(totalOptPnl / totalPremiumInvested * 100 * 10) / 10.0 : 0;
            this.avgContracts = executed.isEmpty() ? 1.0
                    : executed.stream().mapToInt(t -> Math.max(1, t.contracts())).average().orElse(1.0);

            // ── Confidence bucket analysis ────────────────────────────────────
            // Excludes filtered trades — only look at actually-executed setups.
            // Counts wins per bucket using the same WIN/TIMEOUT>0 logic as global WR.
            java.util.function.Predicate<TradeResult> isWin = BacktestService::isWinOutcome;
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

        public static BacktestResult of(String t, List<TradeResult> trades, int days, BacktestMode mode) {
            return new BacktestResult(t, trades, days, null, mode);
        }
        public static BacktestResult of(String t, List<TradeResult> trades, int days) {
            return new BacktestResult(t, trades, days, null, BacktestMode.ALL);
        }
        public static BacktestResult empty(String t, String error) {
            return new BacktestResult(t, List.of(), 0, error, BacktestMode.ALL);
        }
    }
}
