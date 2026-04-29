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
import com.smcscanner.strategy.CapitulationReversalDetector;
import com.smcscanner.strategy.LiquiditySweepFlipDetector;
import com.smcscanner.strategy.PdhPdlDetector;
import com.smcscanner.strategy.OpeningRangeVwapDetector;
import com.smcscanner.strategy.GapDetector;
import com.smcscanner.strategy.GammaPinDetector;
import com.smcscanner.strategy.IndexDivergenceDetector;
import com.smcscanner.strategy.KeyLevelStrategyDetector;
import com.smcscanner.strategy.MarketRegimeDetector;
import com.smcscanner.strategy.MultiTimeframeAnalyzer;
import com.smcscanner.strategy.OvernightMomentumService;
import com.smcscanner.strategy.PivotPointService;
import com.smcscanner.strategy.PowerEarningsGapDetector;
import com.smcscanner.strategy.PressureService;
import com.smcscanner.strategy.ScalpMomentumDetector;
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
    private static final double ATR_TRAIL_NORMAL = 1.50;    // trend continuation: wider trail (was 0.75)
    private static final double ATR_TRAIL_REVERSAL = 0.50;  // tighten on reversal closes (was 0.30)
    private static final int REVERSAL_CLOSES = 2;
    private static final double TRAIL_EXIT_SLIPPAGE_BPS = 5.0;  // 0.05% adverse slippage on trail exits
    // Live orders are placed as marketable limits at the ask price.
    // Typical ask premium above last close is 3–5% for liquid options.
    // We model the underlying stock entry with a modest 5 BPS slippage (unchanged),
    // since TP/SL are tracked on the underlying, not the option premium.
    // Fill is always assumed in backtest (matches the ask-based limit that fills immediately).
    private static final double ENTRY_SLIPPAGE_BPS      = 5.0;  // 0.05% on underlying entry price
    private static final double HYBRID_BE_R = 1.0;    // move SL to breakeven at 1.0R profit
    private static final double HYBRID_TRAIL_R = 2.5; // arm trailing stop at 2.5R (was 1.5)
    // Scalp-specific trail constants (mirrors AlpacaOrderService SCALP_* values)
    private static final double SCALP_TRAIL_R      = 2.0;  // arm trail at 2.0R for scalps (was 0.90)
    private static final double SCALP_TRAIL_NORMAL = 0.75; // scalp trail ATR mult (was 0.35)

    private final PolygonClient            client;
    private final AtrCalculator            atrCalc;
    private final SetupDetector            setupDetector;
    private final VwapStrategyDetector     vwapDetector;
    private final BreakoutStrategyDetector breakoutDetector;
    private final ScalpMomentumDetector    scalpDetector;
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
    private final MarketRegimeDetector     regimeDetector;
    private final PivotPointService        pivotService;
    private final PressureService          pressureService;
    private final OvernightMomentumService overnightService;
    private final PowerEarningsGapDetector        pegDetector;
    private final MultiTimeframeAnalyzer          mtf;
    private final CapitulationReversalDetector    capReversalDetector;
    private final LiquiditySweepFlipDetector      sweepFlipDetector;
    private final PdhPdlDetector                  pdhPdlDetector;
    private final OpeningRangeVwapDetector        orVwapDetector;

    public BacktestService(PolygonClient client, AtrCalculator atrCalc, SetupDetector setupDetector,
                           VwapStrategyDetector vwapDetector, BreakoutStrategyDetector breakoutDetector,
                           ScalpMomentumDetector scalpDetector,
                           GapDetector gapDetector,
                           KeyLevelStrategyDetector keyLevelDetector,
                           VolatilitySqueezeDetector vSqueezeDetector, ThreeDayVwapDetector vwap3dDetector,
                           IndexDivergenceDetector indexDivDetector, GammaPinDetector gammaPinDetector,
                           NewsService newsService,
                           MarketContextService marketCtxService, SignalQualityFilter qualityFilter,
                           ScannerConfig config, OptionsFlowAnalyzer optionsAnalyzer,
                           TechnicalIndicators techIndicators,
                           MarketRegimeDetector regimeDetector, PivotPointService pivotService,
                           PressureService pressureService, OvernightMomentumService overnightService,
                           PowerEarningsGapDetector pegDetector,
                           MultiTimeframeAnalyzer mtf,
                           CapitulationReversalDetector capReversalDetector,
                           LiquiditySweepFlipDetector sweepFlipDetector,
                           PdhPdlDetector pdhPdlDetector,
                           OpeningRangeVwapDetector orVwapDetector) {
        this.client = client; this.atrCalc = atrCalc; this.setupDetector = setupDetector;
        this.vwapDetector = vwapDetector; this.breakoutDetector = breakoutDetector; this.scalpDetector = scalpDetector;
        this.gapDetector = gapDetector;
        this.keyLevelDetector = keyLevelDetector;
        this.vSqueezeDetector = vSqueezeDetector; this.vwap3dDetector = vwap3dDetector;
        this.indexDivDetector = indexDivDetector; this.gammaPinDetector = gammaPinDetector;
        this.newsService = newsService;
        this.marketCtxService = marketCtxService; this.qualityFilter = qualityFilter;
        this.config = config; this.optionsAnalyzer = optionsAnalyzer; this.techIndicators = techIndicators;
        this.regimeDetector = regimeDetector; this.pivotService = pivotService;
        this.pressureService = pressureService; this.overnightService = overnightService;
        this.pegDetector = pegDetector; this.mtf = mtf;
        this.capReversalDetector = capReversalDetector;
        this.sweepFlipDetector = sweepFlipDetector; this.pdhPdlDetector = pdhPdlDetector;
        this.orVwapDetector = orVwapDetector;
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
        // ── ALL mode = scalp + intraday + swing combined ───────────────────────
        // Each tier runs independently with its own sub-profile (strategy, minConf,
        // SL/TP params). Trades from all 3 tiers are merged and sorted by entry time.
        // This matches live: ScannerService fires scalp, intraday, and swing signals
        // independently — you can take a scalp AND an intraday trade on the same day.
        if (mode == BacktestMode.ALL && (strategyOverride == null || strategyOverride.isBlank())) {
            return runCombinedAll(ticker, lookbackDays, exitStyle);
        }

        // ── Live-parity skip gate ─────────────────────────────────────────────
        // Enforce the same per-mode skip flags as the live scanner so that a
        // backtest never shows trades that would never fire as live alerts.
        if (!ticker.startsWith("X:")) {
            TickerProfile gateProfile = config.getTickerProfile(ticker);
            boolean rootSkip = gateProfile.isSkip();
            // Determine mode key: explicit BacktestMode takes priority, then strategy override
            String modeKey;
            if (mode == BacktestMode.SCALP) {
                modeKey = "scalp";
            } else if (mode == BacktestMode.SWING) {
                modeKey = "swing";
            } else if (mode == BacktestMode.INTRADAY) {
                modeKey = "intraday";
            } else if (strategyOverride != null && !strategyOverride.isBlank()) {
                modeKey = switch (strategyOverride.toLowerCase()) {
                    case "scalp"                                     -> "scalp";
                    case "smc","vwap","keylevel","breakout","gap",
                         "peg","vsqueeze","vwap3d","idiv","gammapin","choch-primary" -> "intraday";
                    default                                          -> null;
                };
            } else {
                modeKey = null; // ALL mode + Profile default — no mode-level gate
            }
            if (modeKey != null) {
                TickerProfile.ModeProfile mp = gateProfile.resolveMode(modeKey);
                if (mp.isEffectiveSkip(rootSkip)) {
                    String reason = mp.resolveSkipReason(gateProfile.getSkipReason());
                    log.info("Backtest skip gate: {} {} disabled — {}", ticker, modeKey, reason);
                    return BacktestResult.empty(ticker, ticker + " " + modeKey + " disabled: " + reason);
                }
            }
        }

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

        // Fetch 15m bars for the full backtest period — used for 15m alignment check
        // and fractal anchor squeeze detection (mirrors live ScannerService).
        List<OHLCV> all15mBars = ticker.startsWith("X:") ? List.of()
                : client.getBarsWithLookback(ticker, "15m", 10000, lookbackDays + 5);

        // Fetch hourly bars — used to compute HTF bias via structure analysis,
        // matching live ScannerService which calls mtf.getHtfBias(hourlyBars).
        // Daily bars are kept only for ATR + keylevel history (not bias).
        List<OHLCV> allHourlyBars = ticker.startsWith("X:") ? List.of()
                : client.getBarsWithLookback(ticker, "60m", 3000, lookbackDays + 10);

        // Pre-fetch SPY and VIX bars once for market context computation.
        // getContextAt() slices these in-memory per trade — no extra API calls.
        List<OHLCV> spyBars = marketCtxService.fetchSpyBarsForBacktest(220);
        List<OHLCV> vixBars = marketCtxService.fetchVixBarsForBacktest(220);

        // Pre-fetch SPY 5m bars for intraday RS gate (only if this ticker uses it)
        TickerProfile preProfile = config.getTickerProfile(ticker);
        String scalpSubStratForSpy = preProfile.resolveMode("scalp").resolveStrategy(preProfile.getStrategyType());
        boolean needsSpy5m = !ticker.startsWith("X:")  // always fetch for non-crypto (SPY directional gate applies to all)
                || preProfile.isIntradayRsGate()
                || "idiv".equals(preProfile.getStrategyType())
                || "idiv".equals(strategyOverride)
                || "scalp".equals(preProfile.getStrategyType())
                || "scalp".equals(strategyOverride)
                || "scalp".equals(scalpSubStratForSpy)  // sub-profile uses scalp (e.g. COIN)
                || "gap".equals(preProfile.getStrategyType())
                || "gap".equals(strategyOverride);
        List<OHLCV> spy5mBars = needsSpy5m
                ? client.getBarsWithLookback("SPY", "5m", 50000, lookbackDays + 5)
                : List.of();
        // Group SPY 5m bars by date for per-day slicing
        TreeMap<LocalDate, List<OHLCV>> spy5mByDate = new TreeMap<>();
        for (OHLCV bar : spy5mBars) {
            LocalDate d = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET).toLocalDate();
            spy5mByDate.computeIfAbsent(d, k -> new ArrayList<>()).add(bar);
        }

        // Fetch 1m bars for scalp strategies — needed to match live (which trails on 1m).
        // Capped at 90 days: 90d × 6.5h × 60min = ~35k bars/ticker, safe for Railway.
        // Falls back to 5m fwdBars for dates outside this 90d window.
        // Also fetch 1m bars when SCALP mode uses a scalp sub-profile strategy
        String scalpSubStrat = preProfile.resolveMode("scalp").resolveStrategy(preProfile.getStrategyType());
        boolean needsScalp1m = !ticker.startsWith("X:")
                && ("scalp".equals(preProfile.getStrategyType()) || "scalp".equals(strategyOverride)
                    || (mode == BacktestMode.SCALP && "scalp".equals(scalpSubStrat)));
        int scalp1mLookback = Math.min(lookbackDays, 90);
        List<OHLCV> all1mBars = needsScalp1m
                ? client.getBarsWithLookback(ticker, "1m", 100_000, scalp1mLookback)
                : List.of();
        TreeMap<LocalDate, List<OHLCV>> byDate1m = new TreeMap<>();
        for (OHLCV bar : all1mBars) {
            LocalDate d = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET).toLocalDate();
            byDate1m.computeIfAbsent(d, k -> new ArrayList<>()).add(bar);
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
                // HTF bias via hourly structure analysis — matches live ScannerService
                // which calls mtf.getHtfBias(hourlyBars) using swing/BOS detection.
                // Previously used daily SMA20 which diverged from live on intraday reversals.
                if (!allHourlyBars.isEmpty()) {
                    long cutoffH = date.atStartOfDay(ET).toInstant().toEpochMilli();
                    List<OHLCV> hourlySlice = allHourlyBars.stream()
                            .filter(b -> b.getTimestamp() < cutoffH)
                            .collect(Collectors.toList());
                    htfBias = mtf.getHtfBias(hourlySlice);
                }
            }

            // Slide through day's bars — detect first valid setup
            TickerProfile bp = config.getTickerProfile(ticker);
            // Resolve strategy type: sub-profile overrides root for explicit modes.
            // SCALP mode → scalp sub-profile strategyType (e.g. "scalp")
            // INTRADAY   → intraday sub-profile strategyType (e.g. "smc" or "vwap")
            // SWING      → swing sub-profile strategyType
            String stratType;
            if (strategyOverride != null && !strategyOverride.isBlank()) {
                stratType = strategyOverride;
            } else if (mode == BacktestMode.SCALP) {
                stratType = bp.resolveMode("scalp").resolveStrategy(bp.getStrategyType());
            } else if (mode == BacktestMode.INTRADAY) {
                stratType = bp.resolveMode("intraday").resolveStrategy(bp.getStrategyType());
            } else if (mode == BacktestMode.SWING) {
                stratType = bp.resolveMode("swing").resolveStrategy(bp.getStrategyType());
            } else {
                stratType = bp.getStrategyType();
            }
            // Session strategies skip pre-market windows in the loop below
            boolean isSessionStrat = "breakout".equals(stratType)
                                  || "scalp".equals(stratType)
                                  || "vwap".equals(stratType)
                                  || "keylevel".equals(stratType)
                                  || "gap".equals(stratType)
                                  || "peg".equals(stratType)
                                  || "vsqueeze".equals(stratType)
                                  || "vwap3d".equals(stratType)
                                  || "idiv".equals(stratType)
                                  || "gammapin".equals(stratType)
                                  || "or-vwap".equals(stratType);
            // Minimum bars before we start checking each strategy
            int minBars = "breakout".equals(stratType)  ? 8
                        : "scalp".equals(stratType)     ? 22
                        : "vwap".equals(stratType)      ? 12
                        : "keylevel".equals(stratType)  ? 20
                        : "gap".equals(stratType)       ? 20
                        : "peg".equals(stratType)       ? 2   // 2nd RTH bar = 9:35 AM confirmation
                        : "vsqueeze".equals(stratType)  ? 25
                        : "vwap3d".equals(stratType)    ? 20
                        : "idiv".equals(stratType)      ? 12
                        : "gammapin".equals(stratType)  ? 15
                        : "or-vwap".equals(stratType)   ? 2   // Mode A fires from 2nd RTH bar (9:35)
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
                // ALL equity strategies: skip pre-market and stop at regular session close.
                // Without the after-hours break, session-based detectors (keylevel, vwap, etc.)
                // keep re-evaluating on stale RTH data as after-hours bars extend the window —
                // this can produce incorrect SL/TP values using distant historical levels.
                if (!ticker.startsWith("X:")) {
                    LocalTime barTime = Instant.ofEpochMilli(
                        dayBars.get(end-1).getTimestamp()).atZone(ET).toLocalTime();
                    if (barTime.isBefore(LocalTime.of(9, 30))) continue;
                    if (!barTime.isBefore(LocalTime.of(16, 0))) break; // stop at RTH close
                }
                List<OHLCV> window = dayBars.subList(0, end);
                long barEpochMs = dayBars.get(end - 1).getTimestamp();

                // ── Per-ticker dead zone hard block ───────────────────────────
                // skipHours is set per-ticker in ticker-profiles.json based on
                // observed loss-by-hour from backtest loss analysis. Never global.
                // Unlike the soft deadZoneAdj (-15), this skips the bar entirely —
                // the loop continues so signals in later hours can still fire.
                if (!ticker.startsWith("X:") && bp.isSkipHour(
                        Instant.ofEpochMilli(barEpochMs).atZone(ET).toLocalTime().getHour())) {
                    log.trace("{} SKIP_HOUR_BLOCK: bar at {}", ticker,
                            Instant.ofEpochMilli(barEpochMs).atZone(ET).toLocalTime());
                    continue;
                }

                // Resolve effective strategy: time-routing overrides base stratType per bar
                String effectiveStrat = (strategyOverride == null || strategyOverride.isBlank()) && bp.hasTimeRouting()
                        ? bp.resolveStrategyForTime(barEpochMs)
                        : stratType;
                // Regime computed before strategy detection so gap strategy can use it
                MarketRegimeDetector.Regime btRegime = ticker.startsWith("X:") ? MarketRegimeDetector.Regime.RANGING
                        : regimeDetector.detectForBacktest(window);
                List<TradeSetup> bSetups;
                if ("scalp".equals(effectiveStrat)) {
                    List<OHLCV> spySlice = spy5mByDate.getOrDefault(date, List.of()).stream()
                            .filter(this::isRegularSessionBar)
                            .filter(b -> b.getTimestamp() <= barEpochMs)
                            .collect(Collectors.toList());
                    bSetups = scalpDetector.detect(window, spySlice, ticker, dailyAtr, true);
                } else if ("vwap".equals(effectiveStrat)) {
                    // MOMENTUM tickers trend through VWAP deviations — shorts structurally lose.
                    // STABLE_LARGE_CAP and others mean-revert cleanly; both directions allowed.
                    boolean vwapLongOnly = bp.isVwapLongOnly() || "MOMENTUM".equals(bp.getCharacter());
                    bSetups = vwapDetector.detect(window, ticker, dailyAtr, true, vwapLongOnly);
                } else if ("breakout".equals(effectiveStrat)) {
                    bSetups = breakoutDetector.detect(window, ticker, dailyAtr, true);
                } else if ("gap".equals(effectiveStrat)) {
                    // Pre-close overnight entry: scan at 3:30–3:55 PM ET for stocks
                    // predicted to gap the next morning. Enter near close, hold overnight,
                    // exit next day when the gap materialises (e.g. AAPL jumps $20-30).
                    ZonedDateTime barZdt = Instant.ofEpochMilli(dayBars.get(end - 1).getTimestamp()).atZone(ET);
                    LocalTime barLocalTime = barZdt.toLocalTime();
                    boolean isPreCloseWindow = !barLocalTime.isBefore(LocalTime.of(15, 30))
                            && barLocalTime.isBefore(LocalTime.of(16, 0));
                    if (!isPreCloseWindow || di >= dates.size() - 1) {
                        bSetups = List.of();
                    } else {
                        List<OHLCV> rthBars = window.stream()
                                .filter(this::isRegularSessionBar)
                                .collect(Collectors.toList());
                        final long scanTs = dayBars.get(end - 1).getTimestamp();
                        List<OHLCV> spyForEntry = spy5mByDate.getOrDefault(date, List.of()).stream()
                                .filter(this::isRegularSessionBar)
                                .filter(b -> b.getTimestamp() <= scanTs)
                                .collect(Collectors.toList());
                        bSetups = List.of();
                        for (String tryDir : List.of("long", "short")) {
                            boolean hasCat = !ticker.startsWith("X:")
                                    && newsService.getSentimentAt(ticker, scanTs).isAligned(tryDir);
                            OvernightMomentumService.HoldSignal sig =
                                    overnightService.evaluate(ticker, rthBars, tryDir, hasCat, spyForEntry);
                            if (sig.shouldHold()) {
                                double ep  = rthBars.get(rthBars.size() - 1).getClose();
                                double bsl = sig.suggestedSl() > 0 ? sig.suggestedSl()
                                        : ("long".equals(tryDir)
                                                ? rthBars.stream().mapToDouble(OHLCV::getLow).min().orElse(ep * 0.98)
                                                : rthBars.stream().mapToDouble(OHLCV::getHigh).max().orElse(ep * 1.02));
                                double btp = "long".equals(tryDir)
                                        ? ep + 2.0 * dailyAtr
                                        : ep - 2.0 * dailyAtr;
                                bSetups = List.of(TradeSetup.builder()
                                        .ticker(ticker).direction(tryDir)
                                        .entry(ep).stopLoss(bsl).takeProfit(btp)
                                        .confidence(sig.gapScore())
                                        .session("NYSE").volatility("gap").atr(dailyAtr)
                                        .factorBreakdown("Gap predict [" + sig.reason() + "] score=" + sig.gapScore())
                                        .timestamp(barZdt.toLocalDateTime())
                                        .build());
                                break;
                            }
                        }
                    }
                } else if ("peg".equals(effectiveStrat)) {
                    // Power Earnings Gap: scan at 9:30–10:00 AM (first 6 bars).
                    // Requires gap ≥3% on 4x volume vs 20d avg, near 52-week high,
                    // and closing strength. Entry at close of 6th bar (first 30 min).
                    ZonedDateTime pegZdt = Instant.ofEpochMilli(dayBars.get(end - 1).getTimestamp()).atZone(ET);
                    LocalTime pegTime = pegZdt.toLocalTime();
                    // 9:35–9:45 AM: second RTH bar confirms gap is holding, not fading
                    boolean isOpenWindow = !pegTime.isBefore(LocalTime.of(9, 35))
                            && pegTime.isBefore(LocalTime.of(9, 46));
                    List<OHLCV> pegSession = window.stream()
                            .filter(this::isRegularSessionBar)
                            .collect(Collectors.toList());
                    double pegPrevClose = htfSlice.isEmpty() ? 0.0
                            : htfSlice.get(htfSlice.size() - 1).getClose();
                    // Gate: require news sentiment aligned with gap direction
                    // (bullish news for gap-up, bearish for gap-down = earnings catalyst).
                    // This filters out macro/tariff selloff gaps that aren't earnings-driven.
                    final long pegScanTs = dayBars.get(end - 1).getTimestamp();
                    NewsSentiment pegSentiment = newsService.getSentimentAt(ticker, pegScanTs);
                    if (!isOpenWindow || pegSession.size() < 3 || pegPrevClose <= 0) {
                        bSetups = List.of();
                    } else {
                        PowerEarningsGapDetector.PEGSignal peg =
                                pegDetector.detect(pegSession, htfSlice, pegPrevClose, dailyAtr);
                        // Block only when news is clearly CONFLICTING (e.g. bearish news on a gap-up).
                        // Historical sentiment data rarely hits the 0.4 threshold for earnings beats,
                        // so requiring isAligned() would kill all valid signals.
                        boolean newsOk = !pegSentiment.isConflicting(peg.direction());
                        if (peg.detected() && newsOk) {
                            bSetups = List.of(TradeSetup.builder()
                                    .ticker(ticker).direction(peg.direction())
                                    .entry(peg.entry()).stopLoss(peg.stopLoss()).takeProfit(peg.takeProfit())
                                    .confidence(peg.confidence())
                                    .session("NYSE").volatility("gap").atr(dailyAtr)
                                    .factorBreakdown(peg.note())
                                    .timestamp(pegZdt.toLocalDateTime())
                                    .build());
                        } else {
                            bSetups = List.of();
                        }
                    }
                } else if ("keylevel".equals(effectiveStrat)) {
                    // Pass daily bars up to this date (htfSlice) as the level-detection source
                    bSetups = keyLevelDetector.detect(window, htfSlice, ticker, dailyAtr, bp, true);
                } else if ("vsqueeze".equals(effectiveStrat)) {
                    bSetups = vSqueezeDetector.detect(window, ticker, dailyAtr, true);
                } else if ("vwap3d".equals(effectiveStrat)) {
                    List<OHLCV> multiDay = new ArrayList<>(prevDaysBars);
                    multiDay.addAll(window);
                    bSetups = vwap3dDetector.detect(multiDay, ticker, dailyAtr);
                } else if ("idiv".equals(effectiveStrat)) {
                    long entryTs = window.get(window.size() - 1).getTimestamp();
                    List<OHLCV> spySlice = spy5mByDate.getOrDefault(date, List.of()).stream()
                            .filter(b -> b.getTimestamp() <= entryTs)
                            .collect(java.util.stream.Collectors.toList());
                    bSetups = indexDivDetector.detect(window, spySlice, ticker, dailyAtr);
                } else if ("gammapin".equals(effectiveStrat)) {
                    bSetups = gammaPinDetector.detect(window, ticker, dailyAtr);
                } else if ("cap_reversal".equals(effectiveStrat)) {
                    bSetups = capReversalDetector.detect(window, ticker, dailyAtr);
                } else if ("or-vwap".equals(effectiveStrat)) {
                    bSetups = orVwapDetector.detect(window, ticker, dailyAtr, true);
                } else if ("choch-primary".equals(effectiveStrat)) {
                    bSetups = setupDetector.detectChochPrimary(window, ticker, dailyAtr, true);
                } else {
                    SetupDetector.DetectResult dr = setupDetector.detectSetups(
                            window, htfBias, ticker, false, dailyAtr, true); // backtestMode=true, real dailyAtr for TP/SL
                    bSetups = dr.setups();
                }

                // ── Capitulation reversal overlay — mirrors live ScannerService ──
                // Skip for or-vwap: overlays would fill in wrong-direction trades labeled as or-vwap
                if (bSetups.isEmpty() && !ticker.startsWith("X:") && !"or-vwap".equals(effectiveStrat)
                        && btRegime != MarketRegimeDetector.Regime.VOLATILE) {
                    bSetups = capReversalDetector.detect(window, ticker, dailyAtr);
                }

                // ── Pattern overlays: sweep-flip, PDH/PDL, CHOCH primary ────────
                // Mirrors live ScannerService overlay block — fires for all non-crypto tickers.
                if (bSetups.isEmpty() && !ticker.startsWith("X:") && !"or-vwap".equals(effectiveStrat)
                        && !"choch-primary".equals(effectiveStrat)) {
                    java.util.List<TradeSetup> ov = new java.util.ArrayList<>();
                    ov.addAll(sweepFlipDetector.detect(window, ticker, dailyAtr, true));
                    ov.addAll(pdhPdlDetector.detect(window, ticker, dailyAtr, true));
                    ov.addAll(setupDetector.detectChochPrimary(window, ticker, dailyAtr, true));
                    if (!ov.isEmpty()) {
                        ov.sort(java.util.Comparator.comparingInt(TradeSetup::getConfidence).reversed());
                        bSetups = java.util.List.of(ov.get(0));
                    }
                }

                if (btRegime == MarketRegimeDetector.Regime.LOW_LIQUIDITY) {
                    log.debug("{} REGIME_LOW_LIQUIDITY {} — skipping bar window", ticker, date);
                    continue;
                }

                // ── Regime-based fallback — mirrors live ScannerService ────────
                // Gap strategy is time-sensitive: only valid at the 9:30 AM open.
                // Fallback would generate SMC/keylevel setups at 3 PM under the "gap" umbrella — wrong.
                // Only fall back when strategy is the default "smc" — explicit profile strategies
                // (vwap, breakout, keylevel, etc.) must not be silently replaced by the regime fallback.
                if (bSetups.isEmpty() && "smc".equals(effectiveStrat) && !ticker.startsWith("X:") && !"gap".equals(effectiveStrat) && !"peg".equals(effectiveStrat)) {
                    String fallbackStrat = regimeDetector.suggestStrategy(btRegime, effectiveStrat);
                    if (fallbackStrat != null && !fallbackStrat.equals(effectiveStrat)) {
                        bSetups = switch (fallbackStrat) {
                            case "scalp" -> {
                                List<OHLCV> spySlice = spy5mByDate.getOrDefault(date, List.of()).stream()
                                        .filter(this::isRegularSessionBar)
                                        .filter(b -> b.getTimestamp() <= barEpochMs)
                                        .collect(Collectors.toList());
                                yield scalpDetector.detect(window, spySlice, ticker, dailyAtr);
                            }
                            case "smc" -> {
                                SetupDetector.DetectResult fr = setupDetector.detectSetups(
                                        window, htfBias, ticker, false, dailyAtr, true);
                                yield fr.setups();
                            }
                            case "keylevel" -> keyLevelDetector.detect(window, htfSlice, ticker, dailyAtr, bp, true);
                            case "vsqueeze" -> vSqueezeDetector.detect(window, ticker, dailyAtr, true);
                            default -> java.util.List.of();
                        };
                        if (!bSetups.isEmpty()) log.debug("{} BT_FALLBACK: {} → {}", ticker, effectiveStrat, fallbackStrat);
                    }
                }

                if (bSetups.isEmpty()) continue;

                TradeSetup setup = bSetups.get(0);

                // or-bounce setups (Mode B) are unreliable in crash conditions: VWAP loses
                // gravity when the stock has declined sharply over multiple days. The VWAP
                // regime detector can't catch this since it only sees same-day bars.
                // Instead, use the 5-day daily return: if > 7% decline in 5 sessions,
                // skip or-bounce (crash mode — bounces are dead cats, shorts are overextended).
                if ("or-vwap".equals(effectiveStrat)
                        && setup.getFactorBreakdown() != null
                        && setup.getFactorBreakdown().contains("or-bounce")
                        && htfSlice.size() >= 6) {
                    OHLCV htfLast = htfSlice.get(htfSlice.size() - 1);
                    OHLCV htfPast = htfSlice.get(htfSlice.size() - 6);
                    double fiveDayReturn = (htfLast.getClose() - htfPast.getClose()) / htfPast.getClose();
                    if (fiveDayReturn < -0.07) {
                        log.debug("{} OR_BOUNCE_CRASH_SKIP {}: 5d return={:.1f}%% — crash regime, skipping Mode B",
                                ticker, date, fiveDayReturn * 100);
                        continue;
                    }
                }

                // ── VOLATILE regime SL widening — mirrors live ScannerService ─
                // Root cause of 66% sub-1:1 R:R: SL was widened by slFactor but TP
                // was left unchanged, converting 1.5:1 trades to <1:1 in volatile
                // regimes (e.g. April 2026 tariff market). Fix: scale TP by same factor
                // so R:R is preserved. Wider SL + proportionally wider TP = same R:R,
                // just more room for the trade to develop in choppy conditions.
                if (btRegime == MarketRegimeDetector.Regime.VOLATILE && !ticker.startsWith("X:")) {
                    double slFactor = regimeDetector.slExpansionFactor(btRegime);
                    double btEntry  = setup.getEntry();
                    double slDist   = Math.abs(setup.getStopLoss() - btEntry) * slFactor;
                    double tpDist   = Math.abs(setup.getTakeProfit() - btEntry) * slFactor; // preserve R:R
                    double newSl    = "long".equals(setup.getDirection()) ? btEntry - slDist : btEntry + slDist;
                    double newTp    = "long".equals(setup.getDirection()) ? btEntry + tpDist : btEntry - tpDist;
                    newSl = Math.round(newSl * 10000.0) / 10000.0;
                    newTp = Math.round(newTp * 10000.0) / 10000.0;
                    setup = TradeSetup.builder()
                            .ticker(setup.getTicker()).direction(setup.getDirection())
                            .entry(setup.getEntry()).stopLoss(newSl).takeProfit(newTp)
                            .confidence(setup.getConfidence()).session(setup.getSession()).volatility(setup.getVolatility())
                            .atr(setup.getAtr()).hasBos(setup.isHasBos()).hasChoch(setup.isHasChoch())
                            .fvgTop(setup.getFvgTop()).fvgBottom(setup.getFvgBottom()).timestamp(setup.getTimestamp())
                            .factorBreakdown(setup.getFactorBreakdown())
                            .build();
                }

                // ── Gap long block — mirrors live ScannerService ──────────────
                // Previous close = last daily bar before this date.
                // Session bars = window filtered to regular session.
                if (!ticker.startsWith("X:") && "long".equals(setup.getDirection())) {
                    List<OHLCV> btSessionBars = window.stream()
                            .filter(this::isRegularSessionBar)
                            .collect(Collectors.toList());
                    double btPrevClose = htfSlice.isEmpty() ? 0.0
                            : htfSlice.get(htfSlice.size() - 1).getClose();
                    if (pressureService.isLongBlockedByGap(btSessionBars, btPrevClose)) {
                        log.debug("{} GAP_LONG_BLOCK_BT {}: gap fill pending, skipping", ticker, date);
                        continue; // try later bar when conditions lift
                    }
                }

                // ── SPY intraday directional gate — mirrors live ScannerService ─
                // When SPY moves >1.5% intraday, counter-trend setups fail at 2x normal rate.
                // Hard-veto for non-crypto, non-SPY, non-QQQ tickers only.
                if (!ticker.startsWith("X:") && !"SPY".equals(ticker) && !"QQQ".equals(ticker)) {
                    List<OHLCV> spyDay = spy5mByDate.getOrDefault(date, List.of());
                    List<OHLCV> spySession = spyDay.stream().filter(this::isRegularSessionBar).collect(Collectors.toList());
                    if (spySession.size() >= 3) {
                        double spyOpen = spySession.get(0).getOpen();
                        double spyCur  = spySession.get(spySession.size() - 1).getClose();
                        double spyMove = spyOpen > 0 ? (spyCur - spyOpen) / spyOpen : 0;
                        if (spyMove > 0.015 && "short".equals(setup.getDirection())) {
                            log.debug("{} SPY_BIAS_BLOCK_BT {}: SPY +{:.1f}%% — SHORT vetoed", ticker, date, spyMove * 100);
                            continue;
                        }
                        if (spyMove < -0.015 && "long".equals(setup.getDirection())) {
                            log.debug("{} SPY_BIAS_BLOCK_BT {}: SPY {:.1f}%% — LONG vetoed", ticker, date, spyMove * 100);
                            continue;
                        }
                    }
                }

                // ── Volume pressure trap detection — mirrors live ──────────────
                int trapAdj = !ticker.startsWith("X:") ? pressureService.computeTrapAdj(
                        window, setup.getDirection(), effectiveStrat, btRegime) : 0;

                // ── ATR exhaustion gate — mirrors live ────────────────────────
                int exhaustionAdj = 0;
                if (!ticker.startsWith("X:")) {
                    List<OHLCV> btSessionBarsEx = window.stream()
                            .filter(this::isRegularSessionBar)
                            .collect(Collectors.toList());
                    PressureService.ExhaustionResult exh =
                            pressureService.checkExhaustion(btSessionBarsEx, htfSlice);
                    if (exh.exhausted()) {
                        exhaustionAdj = -10;
                        double btEntry = setup.getEntry();
                        double risk    = Math.abs(setup.getStopLoss() - btEntry);
                        double capTp   = "long".equals(setup.getDirection()) ? btEntry + risk : btEntry - risk;
                        capTp = Math.round(capTp * 10000.0) / 10000.0;
                        setup = TradeSetup.builder()
                                .ticker(setup.getTicker()).direction(setup.getDirection())
                                .entry(setup.getEntry()).stopLoss(setup.getStopLoss()).takeProfit(capTp)
                                .confidence(setup.getConfidence()).session(setup.getSession()).volatility(setup.getVolatility())
                                .atr(setup.getAtr()).hasBos(setup.isHasBos()).hasChoch(setup.isHasChoch())
                                .fvgTop(setup.getFvgTop()).fvgBottom(setup.getFvgBottom()).timestamp(setup.getTimestamp())
                                .build();
                    }
                }

                // ── Historical context checks (news + market) ────────────────
                long entryEpochMs = dayBars.get(end - 1).getTimestamp();

                // ── 15m alignment check — matches live ScannerService (soft -15 penalty) ──
                // Compute 15m bias using bars strictly before entry timestamp.
                // SOFT penalty of -15 if 15m trend opposes setup — does NOT hard-block.
                // BYPASS for VWAP/vwap3d: mean-reversion intentionally fights the 15m trend.
                int bias15mAdj = 0;
                String bias15mLabel = "neutral";
                // slice15Ref computed unconditionally so the fractal anchor squeeze check
                // fires for ALL strategies including VWAP/vwap3d — matches live ScannerService.
                List<OHLCV> slice15Ref = ticker.startsWith("X:") || all15mBars.isEmpty()
                        ? List.of()
                        : all15mBars.stream()
                                .filter(b -> b.getTimestamp() < entryEpochMs)
                                .collect(java.util.stream.Collectors.toList());
                boolean is15mApplicable = !"vwap".equals(effectiveStrat) && !"vwap3d".equals(effectiveStrat);
                if (is15mApplicable && !ticker.startsWith("X:") && !slice15Ref.isEmpty()) {
                    List<OHLCV> slice15 = slice15Ref;
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
                double btIntradayRs = 0.0;
                if (bp.isIntradayRsGate() && !ticker.startsWith("X:")) {
                    List<OHLCV> spyDay = spy5mByDate.getOrDefault(date, List.of());
                    List<OHLCV> spyWindow = spyDay.stream()
                            .filter(b -> b.getTimestamp() <= entryEpochMs)
                            .collect(Collectors.toList());
                    btIntradayRs = marketCtxService.computeIntradayRsFromBars(window, spyWindow);
                    intradayRsAdj = marketCtxService.computeIntradayRsDelta(btIntradayRs, window, setup.getDirection());
                }

                // ── RS continuous TP multiplier [0.7 → 1.5] — mirrors live ──
                if (bp.isIntradayRsGate() && !ticker.startsWith("X:") && btIntradayRs > 0) {
                    double rsMultiplier = 1.0;
                    if      (btIntradayRs > 1.3) rsMultiplier = Math.min(1.5, btIntradayRs);
                    else if (btIntradayRs < 0.8) rsMultiplier = Math.max(0.7, btIntradayRs);
                    if (rsMultiplier != 1.0) {
                        double btEntry  = setup.getEntry();
                        double tpDist   = Math.abs(setup.getTakeProfit() - btEntry) * rsMultiplier;
                        double newTp    = "long".equals(setup.getDirection()) ? btEntry + tpDist : btEntry - tpDist;
                        newTp = Math.round(newTp * 10000.0) / 10000.0;
                        setup = TradeSetup.builder()
                                .ticker(setup.getTicker()).direction(setup.getDirection())
                                .entry(setup.getEntry()).stopLoss(setup.getStopLoss()).takeProfit(newTp)
                                .confidence(setup.getConfidence()).session(setup.getSession()).volatility(setup.getVolatility())
                                .atr(setup.getAtr()).hasBos(setup.isHasBos()).hasChoch(setup.isHasChoch())
                                .fvgTop(setup.getFvgTop()).fvgBottom(setup.getFvgBottom()).timestamp(setup.getTimestamp())
                                .build();
                    }
                }

                TickerProfile bp2 = config.getTickerProfile(ticker);
                // Sub-profile minConf: scalp/intraday/swing sub-profiles override root.
                int rootMinConf = bp2.resolveMinConfidence(config.getMinConfidence());
                int effectiveMinConf;
                if (mode == BacktestMode.SCALP) {
                    effectiveMinConf = bp2.resolveMode("scalp").resolveMinConfidence(rootMinConf, config.getMinConfidence());
                } else if (mode == BacktestMode.INTRADAY) {
                    effectiveMinConf = bp2.resolveMode("intraday").resolveMinConfidence(rootMinConf, config.getMinConfidence());
                } else if (mode == BacktestMode.SWING) {
                    effectiveMinConf = bp2.resolveMode("swing").resolveMinConfidence(rootMinConf, config.getMinConfidence());
                } else {
                    effectiveMinConf = rootMinConf;
                }
                // Mode-aware maxConf: intraday/scalp/swing sub-profiles can raise the cap independently
                int rootMaxConf = bp2.resolveMaxConfidence();
                int effectiveMaxConf;
                if (mode == BacktestMode.SCALP) {
                    effectiveMaxConf = bp2.resolveMode("scalp").resolveMaxConfidence(rootMaxConf);
                } else if (mode == BacktestMode.INTRADAY) {
                    effectiveMaxConf = bp2.resolveMode("intraday").resolveMaxConfidence(rootMaxConf);
                } else if (mode == BacktestMode.SWING) {
                    effectiveMaxConf = bp2.resolveMode("swing").resolveMaxConfidence(rootMaxConf);
                } else {
                    effectiveMaxConf = rootMaxConf;
                }

                // News: 48h window ending at entry timestamp
                NewsSentiment sentiment = ticker.startsWith("X:") ? NewsSentiment.NONE
                        : newsService.getSentimentAt(ticker, entryEpochMs);
                int newsAdj = sentiment.confidenceDelta(setup.getDirection(), effectiveStrat);

                // ── Ticker DNA gates — mirrors live ScannerService ────────────
                if (!ticker.startsWith("X:")) {
                    // EXTERNAL_CORRELATED (MARA): SMC/FVG patterns = BTC noise, not order-flow
                    if (bp2.isSmcBlocked()) {
                        String strat = setup.getVolatility(); // strategy label lives in volatility field
                        if (!"scalp".equals(strat) && !"keylevel".equals(strat)) {
                            log.debug("{} DNA_BLOCK_BT EXTERNAL_CORRELATED: SMC blocked on {}", ticker, date);
                            continue;
                        }
                    }
                    // STABLE_LARGE_CAP (AAPL/MSFT/AMZN/GOOGL): options decay before slow stock hits TP.
                    // Only fire when news is aligned (confirmed catalyst day).
                    if (bp2.isCatalystRequired()) {
                        if (!sentiment.isAligned(setup.getDirection())) {
                            log.debug("{} DNA_BLOCK_BT STABLE_LARGE_CAP: no catalyst on {} — skipping", ticker, date);
                            continue;
                        }
                    }
                }

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

                // ── Fractal Anchor — mirrors live ScannerService ──────────────
                // 15m SQUEEZE → cap TP to 1:1. Uses slice15Ref computed above.
                if (!ticker.startsWith("X:") && !slice15Ref.isEmpty()
                        && regimeDetector.detectSqueeze(slice15Ref)) {
                    double fa_entry = setup.getEntry();
                    double fa_risk  = Math.abs(setup.getStopLoss() - fa_entry);
                    double fa_oneR  = "long".equals(setup.getDirection()) ? fa_entry + fa_risk : fa_entry - fa_risk;
                    fa_oneR = Math.round(fa_oneR * 10000.0) / 10000.0;
                    if (Math.abs(setup.getTakeProfit() - fa_entry) > fa_risk + 0.0001) {
                        setup = TradeSetup.builder()
                                .ticker(setup.getTicker()).direction(setup.getDirection())
                                .entry(setup.getEntry()).stopLoss(setup.getStopLoss()).takeProfit(fa_oneR)
                                .confidence(setup.getConfidence()).session(setup.getSession()).volatility(setup.getVolatility())
                                .atr(setup.getAtr()).hasBos(setup.isHasBos()).hasChoch(setup.isHasChoch())
                                .fvgTop(setup.getFvgTop()).fvgBottom(setup.getFvgBottom()).timestamp(setup.getTimestamp())
                                .build();
                    }
                }

                // Market context: SPY RS + VIX regime as-of entry date
                MarketContext context = ticker.startsWith("X:") ? MarketContext.NONE
                        : marketCtxService.getContextAt(ticker, dailyBars, spyBars, vixBars, entryEpochMs);
                int ctxAdj = context.confidenceDelta(setup.getDirection(), effectiveStrat);

                // Hard gate: choch-primary SHORT against bullish news OR positive RS = structural counter-momentum.
                // Visual review (04/08+04/16 META) confirmed 100% loss rate on these setups —
                // alignment bonus (+10) was overriding the news/RS penalties, so hard gate is required.
                boolean isChochPrimaryShort = "short".equals(setup.getDirection())
                        && setup.getFactorBreakdown() != null
                        && setup.getFactorBreakdown().startsWith("choch-primary-short");
                if (isChochPrimaryShort && (sentiment.isBullish() || context.isRsConflicting(setup.getDirection()))) {
                    log.debug("{} CHOCH_SHORT_BLOCKED: {} — bullishNews={} rsConflict={}",
                            ticker, date, sentiment.isBullish(), context.isRsConflicting(setup.getDirection()));
                    trades.add(new TradeResult(ticker, setup.getDirection(), effectiveStrat,
                            setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                            "QUALITY_FILTERED", 0.0,
                            toDateTime(dayBars.get(end - 1).getTimestamp()), toDateTime(dayBars.get(end - 1).getTimestamp()),
                            dayBars.get(end - 1).getTimestamp(), dayBars.get(end - 1).getTimestamp(),
                            setup.getFactorBreakdown(),
                            setup.getConfidence(), setup.getAtr(), newsAdj, sentiment.label(), ctxAdj, context.rsLabel(),
                            0, "CHOCH_SHORT_BLOCKED", 0, 0, 0, 0, 0));
                    continue;
                }

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

                // ── Late-day filter (3:00 PM ET → skip intraday entry) ──────────
                // 18% of losses are 3:00-3:30 PM entries. MMs position for close in
                // this window — spreads widen, false breakouts spike, no time for TP.
                // Exception: gap strategy entries ARE intentionally at 3:30-3:55 PM.
                if (!ticker.startsWith("X:") && !"gap".equals(effectiveStrat) && !"peg".equals(effectiveStrat)) {
                    LocalTime entryLt = Instant.ofEpochMilli(entryEpochMs).atZone(ET).toLocalTime();
                    if (entryLt.getHour() >= 15) {
                        log.debug("{} LATE_DAY_SKIP: entry at {} — 3pm+ intraday blocked", ticker, entryLt);
                        break; // done for today
                    }
                }

                // ── Regime-strategy alignment — mirrors live ScannerService ──
                int regimeStratAdj = !ticker.startsWith("X:")
                        ? regimeDetector.computeStrategyAlignment(btRegime, effectiveStrat) : 0;

                // ── Multi-day pivot level adjustment — mirrors live ────────────
                int pivotAdj = !ticker.startsWith("X:")
                        ? pivotService.computePivotAdj(htfSlice, setup.getEntry(), setup.getDirection()) : 0;

                // ── Confluence veto — mirrors live ScannerService ─────────────
                int negPrimaryCountBt = 0;
                if (trapAdj        < 0) negPrimaryCountBt++;
                if (bias15mAdj     < 0) negPrimaryCountBt++;
                if (regimeStratAdj < 0) negPrimaryCountBt++;
                if (pivotAdj       < 0) negPrimaryCountBt++;
                if (corrAdj        < 0) negPrimaryCountBt++;
                if (sma200Adj      < 0) negPrimaryCountBt++;
                int confluenceVetoAdj = negPrimaryCountBt >= 4 ? -20 : negPrimaryCountBt == 3 ? -10 : 0;

                // ── DNA character confidence penalty — mirrors live ScannerService ─
                // FINANCIAL (V, JPM): -20 (high option premium vs small % moves)
                // SPECULATIVE_LOW_PRICE (SOFI): -10 (wick-out risk even with wider SL)
                // GS changed FINANCIAL→MOMENTUM so it doesn't get penalised.
                int dnaAdj = !ticker.startsWith("X:") ? bp2.characterConfPenalty() : 0;

                int totalAdj = newsAdj + ctxAdj + qualityAdj + flowAdj + regimeAdj + corrAdj + intradayRsAdj + deadZoneAdj + bias15mAdj + alignmentAdj + sma200Adj + rsiAdj + candleAdj + volAdj + regimeStratAdj + pivotAdj + trapAdj + exhaustionAdj + confluenceVetoAdj + dnaAdj;
                // Penalty floor: secondary filters can reduce base confidence by at most 25%.
                // A strong base setup (80+) should never be killed by stacking RS + news + quality.
                // Floor = 75% of base confidence. e.g. base=80 → floor=60, base=70 → floor=52.
                int penaltyFloor = (int)(setup.getConfidence() * 0.75);
                int rawAdj = setup.getConfidence() + totalAdj;
                int adjConf  = Math.max(penaltyFloor, Math.min(100, rawAdj));

                // ── VIX-aware dynamic minimum confidence gate ─────────────────
                // Raises min bar in high-VIX environments. Skipped for vwap/scalp —
                // those strategies already benefit from high VIX (larger Z-score deviations,
                // tighter Bollinger squeezes) and have their own internal quality gates.
                int vixBoost = 0;
                boolean vixEligible = !ticker.startsWith("X:")
                        && !"vwap".equals(effectiveStrat) && !"scalp".equals(effectiveStrat);
                if (vixEligible && context.vixLevel() > 25) vixBoost  = 5;
                if (vixEligible && context.vixLevel() > 35) vixBoost += 2;
                int dynamicMinConf = effectiveMinConf + vixBoost;

                // Skip trade if combined filters knocked confidence below threshold.
                // Gap strategy: score is already gated by OvernightMomentumService.shouldHold()
                // which enforces its own 55/45 threshold — skip the intraday conf gate.
                // or-vwap bypasses the low-conf gate ONLY when market context is positive (ctxAdj > 0).
                // Negative or neutral ctx (crash regime, misaligned RS) means we still apply the conf gate.
                boolean orVwapConfBypass = "or-vwap".equals(effectiveStrat) && ctxAdj > 0;
                if (adjConf < dynamicMinConf && !"gap".equals(effectiveStrat) && !"peg".equals(effectiveStrat) && !orVwapConfBypass) {
                    log.debug("{} CONF_FILTERED: base={} news={} ctx={} qual={} iRS={} regime={} corr={} dz={} → adj={} floor={} (min={} vixBoost={})",
                            ticker, setup.getConfidence(), newsAdj, ctxAdj, qualityAdj, intradayRsAdj, regimeAdj, corrAdj, deadZoneAdj, adjConf, penaltyFloor, effectiveMinConf, vixBoost);
                    String filteredOutcome = confluenceVetoAdj < 0 ? "CONFLUENCE_FILTERED"
                                           : trapAdj < -8        ? "TRAP_FILTERED"
                                           : regimeAdj < -8      ? "REGIME_FILTERED"
                                           : bias15mAdj < 0 && (newsAdj < 0 || ctxAdj < 0 || qualityAdj < 0) ? "MULTI_FILTERED"
                                           : bias15mAdj < 0 ? "15M_FILTERED"
                                           : (newsAdj < 0 && (ctxAdj < 0 || qualityAdj < 0)) ? "MULTI_FILTERED"
                                           : newsAdj < 0    ? "NEWS_FILTERED"
                                           : qualityAdj < 0 ? "QUALITY_FILTERED"
                                           :                   "CTX_FILTERED";
                    String filteredLabel = buildFilterLabel(sentiment.label(), context.rsLabel(), qualityLabel);
                    trades.add(new TradeResult(ticker, setup.getDirection(), effectiveStrat,
                            setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                            filteredOutcome, 0.0,
                            toDateTime(dayBars.get(end - 1).getTimestamp()), toDateTime(dayBars.get(end - 1).getTimestamp()),
                            dayBars.get(end - 1).getTimestamp(), dayBars.get(end - 1).getTimestamp(),
                            setup.getFactorBreakdown(),
                            adjConf, setup.getAtr(), newsAdj, sentiment.label(), ctxAdj, context.rsLabel(),
                            qualityAdj, filteredLabel,
                            0, 0, 0, 0, 0)); // no options P&L or contracts for filtered trades
                    // Do NOT set tradePlacedToday — a filtered trade should not block later
                    // valid setups on the same day. Only actual executions consume the day slot.
                    continue;
                }

                // Over-extended gate: skip signals above per-ticker maxConfidence
                // Reverses the reversed-confidence pattern (PLTR/SOFI/NFLX: 85+ underperforms 75-84)
                if (adjConf > effectiveMaxConf && !"or-vwap".equals(effectiveStrat)) {
                    trades.add(new TradeResult(ticker, setup.getDirection(), effectiveStrat,
                            setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit(),
                            "CONF_CAP_FILTERED", 0.0,
                            toDateTime(dayBars.get(end - 1).getTimestamp()), toDateTime(dayBars.get(end - 1).getTimestamp()),
                            dayBars.get(end - 1).getTimestamp(), dayBars.get(end - 1).getTimestamp(),
                            setup.getFactorBreakdown(),
                            adjConf, setup.getAtr(), newsAdj, sentiment.label(), ctxAdj, context.rsLabel(),
                            qualityAdj, "CONF_CAP",
                            0, 0, 0, 0, 0));
                    // Do NOT set tradePlacedToday — capped signals should not block later valid setups
                    continue;
                }

                // ── 1 contract per trade ──
                // Conviction-scaled contracts — mirrors live ScannerService (lines 781-784)
                // 90+ → 3, 82-89 → 2, <82 → 1 (same thresholds as live)
                int contracts = adjConf >= 90 ? 3 : adjConf >= 82 ? 2 : 1;
                log.debug("{} CONVICTION: conf={} → {} contract(s)", ticker, adjConf, contracts);

                tradePlacedToday = true;

                // Apply entry slippage: live orders are marketable limits at the ask price.
                // Model underlying entry with 5 BPS adverse slippage; TP/SL resolved on underlying.
                double slippageFactor = ENTRY_SLIPPAGE_BPS / 10000.0;
                double entry = "long".equals(setup.getDirection())
                        ? setup.getEntry() * (1 + slippageFactor)
                        : setup.getEntry() * (1 - slippageFactor);
                double sl    = setup.getStopLoss();
                double tp    = setup.getTakeProfit();
                String dir   = setup.getDirection();
                String entryTime = toDateTime(dayBars.get(end - 1).getTimestamp());

                // ── Overnight hold gate — mirrors live OvernightMomentumService ─────────────
                // Only extend simulation into the next trading day when the session is "coiling":
                // closing at extreme + late volume surge + RS lead (and/or news catalyst).
                // Previously the backtest always added next-day bars, which inflated wins from
                // lucky overnight gaps. Now only *earned* overnight holds roll forward.
                // Crypto runs 24/7 → always allow next-day extension.
                // Gap trades enter at 9:30 AM but the overnight decision is made at 3:55 PM.
                // Use the full day's RTH bars so the overnight service sees closing price action,
                // late volume, and RS vs SPY — not just the 1-2 bars available at open.
                // All other strategies use the bars available at entry time (no look-ahead).
                List<OHLCV> sessionBarsForOvernight;
                if ("gap".equals(effectiveStrat) && !ticker.startsWith("X:")) {
                    sessionBarsForOvernight = dayBars.stream().filter(this::isRegularSessionBar).collect(Collectors.toList());
                } else {
                    sessionBarsForOvernight = ticker.startsWith("X:") ? List.of()
                            : window.stream().filter(this::isRegularSessionBar).collect(Collectors.toList());
                }
                List<OHLCV> spySessionForOvernight = spy5mByDate.getOrDefault(date, List.of()).stream()
                        .filter(this::isRegularSessionBar)
                        .filter(b -> b.getTimestamp() <= entryEpochMs)
                        .collect(Collectors.toList());
                boolean hasCatalyst = sentiment.isAligned(dir);
                OvernightMomentumService.HoldSignal holdSignal = ticker.startsWith("X:")
                        ? new OvernightMomentumService.HoldSignal(true, 100, "crypto_24_7", 0.0)
                        : overnightService.evaluate(ticker, sessionBarsForOvernight, dir, hasCatalyst, spySessionForOvernight);

                // Forward test: rest of today; next day only when overnight hold qualifies.
                // Filter to regular session only (9:30 AM–4:00 PM ET) — pre-market/after-hours
                // bars have thin liquidity and unrealistic fills; exits there are not executable.
                List<OHLCV> fwdBarsRaw = new ArrayList<>(dayBars.subList(end, dayBars.size()));
                if (di + 1 < dates.size() && holdSignal.shouldHold()) {
                    fwdBarsRaw.addAll(byDate.get(dates.get(di + 1)));
                    // Tighten SL to day's midpoint: protects against gap-up then full reversal.
                    // Only tighten (never widen) — if current SL is already tighter, keep it.
                    double dayMid = holdSignal.suggestedSl();
                    if (dayMid > 0) {
                        if ("long".equals(dir) && dayMid > sl) {
                            log.debug("{} OVERNIGHT_HOLD: SL tightened {} → {} (score={} reason={})",
                                    ticker, sl, dayMid, holdSignal.gapScore(), holdSignal.reason());
                            sl = dayMid;
                        } else if ("short".equals(dir) && dayMid < sl) {
                            log.debug("{} OVERNIGHT_HOLD: SL tightened {} → {} (score={} reason={})",
                                    ticker, sl, dayMid, holdSignal.gapScore(), holdSignal.reason());
                            sl = dayMid;
                        }
                    }
                } else if (di + 1 < dates.size()) {
                    log.debug("{} NO_OVERNIGHT_HOLD: closing at session end (score={} reason={})",
                            ticker, holdSignal.gapScore(), holdSignal.reason());
                }

                // Gap overnight: extend fwdBarsRaw into next day BEFORE building fwdBars,
                // otherwise the cap/filter below runs on the old list and next-day bars are ignored.
                if ("gap".equals(effectiveStrat) && di + 1 < dates.size()) {
                    List<OHLCV> nextDayBars = byDate.get(dates.get(di + 1));
                    if (nextDayBars != null && !fwdBarsRaw.contains(nextDayBars.get(0))) {
                        fwdBarsRaw.addAll(nextDayBars);
                    }
                }

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

                // ── Gap-Open Stop (Fix B) ─────────────────────────────────────────
                // If the stock gaps >3% at the next morning's open, immediately move
                // SL from previous-day midpoint to today's opening price.
                // Prevents "gap-up then fill-and-fail" from giving back all the profit.
                // Only applied when an overnight hold was taken (next-day bars are present).
                if (holdSignal.shouldHold() && !fwdBars.isEmpty()) {
                    // Find the first next-day RTH bar (date > trade entry date)
                    LocalDate entryDate = Instant.ofEpochMilli(entryEpochMs).atZone(ET).toLocalDate();
                    for (OHLCV nextBar : fwdBars) {
                        LocalDate barDate = Instant.ofEpochMilli(nextBar.getTimestamp()).atZone(ET).toLocalDate();
                        if (barDate.isAfter(entryDate)) {
                            double nextOpen = nextBar.getOpen();
                            double gapOpenPct = "long".equals(dir)
                                    ? (nextOpen - entry) / entry
                                    : (entry - nextOpen) / entry;
                            if (gapOpenPct >= 0.03) {
                                // Gap ≥ 3%: protect the gap by moving SL to today's open
                                double newSl = "long".equals(dir)
                                        ? Math.max(sl, nextOpen * 0.999) // just below gap open
                                        : Math.min(sl, nextOpen * 1.001);
                                if (("long".equals(dir) && newSl > sl) || ("short".equals(dir) && newSl < sl)) {
                                    log.debug("{} GAP_OPEN_STOP: gap={}% SL {} → {} (locked at gap open)",
                                            ticker, String.format("%.1f", gapOpenPct * 100), sl, newSl);
                                    sl = newSl;
                                }
                            }
                            break; // only check the first next-day bar
                        }
                    }
                }

                // ── SL price floor — mirrors live ScannerService DNA gates ────
                // Universal floor: 1.5% of price for any stock under $30.
                // Ticker-character override (SPECULATIVE_LOW_PRICE) adds extra floor (2%).
                // Prevents wick-out on low-price volatile stocks (SOFI: tiny ATR SL → dead).
                if (!ticker.startsWith("X:")) {
                    double minSlPct = Math.max(bp2.minSlPricePct(),
                            entry < 30.0 ? 0.015 : 0.0);
                    if (minSlPct > 0) {
                        double slDist = Math.abs(sl - entry);
                        double minSlDist = entry * minSlPct;
                        if (slDist < minSlDist) {
                            double newSl = "long".equals(dir)
                                    ? Math.round((entry - minSlDist) * 10000.0) / 10000.0
                                    : Math.round((entry + minSlDist) * 10000.0) / 10000.0;
                            log.debug("{} DNA_SL_FLOOR_BT {}: SL widened {} → {} (minSlPct={}%)",
                                    ticker, date, sl, newSl, minSlPct * 100);
                            sl = newSl;
                        }
                    }
                }

                boolean scalpManaged = mode == BacktestMode.SCALP || "scalp".equals(setup.getVolatility());

                // For scalp: use 1m forward bars when available (matches live 1m trailing).
                // Falls back to 5m when 1m data isn't loaded (non-scalp runs or dates > 90d ago).
                List<OHLCV> fwdBarsForExit = fwdBars;
                List<OHLCV> windowForExit  = window;
                if (scalpManaged && byDate1m.containsKey(date)) {
                    List<OHLCV> day1mBars = byDate1m.get(date);
                    fwdBarsForExit = day1mBars.stream()
                            .filter(b -> b.getTimestamp() > entryEpochMs)
                            .filter(b -> { LocalTime t = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET).toLocalTime();
                                          return !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0)); })
                            .collect(Collectors.toList());
                    List<OHLCV> pre1m = day1mBars.stream()
                            .filter(b -> b.getTimestamp() <= entryEpochMs)
                            .collect(Collectors.toList());
                    if (!pre1m.isEmpty()) {
                        int wStart = Math.max(0, pre1m.size() - 30);
                        windowForExit = pre1m.subList(wStart, pre1m.size());
                    }
                    if (fwdBarsForExit.isEmpty()) fwdBarsForExit = fwdBars; // fallback
                }

                ExitResult exit = switch (exitStyle) {
                    case LIVE_PARITY -> scalpManaged
                            ? simulateScalpExit(windowForExit, fwdBarsForExit, entry, sl, tp, dir)
                            : simulateLiveParityExit(window, fwdBars, entry, sl, tp, dir);
                    case HYBRID -> scalpManaged
                            ? simulateScalpExit(windowForExit, fwdBarsForExit, entry, sl, tp, dir)
                            : simulateHybridExit(window, fwdBars, entry, sl, tp, dir);
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
                // Skip options model for crypto — no listed options market on X: tickers.
                double exitPrice = "long".equals(dir)
                        ? entry * (1 + pnlPct / 100.0)
                        : entry * (1 - pnlPct / 100.0);
                OptionsFlowAnalyzer.BacktestOptionsEstimate optEst;
                double scaledPnlPerContract;
                if (ticker.startsWith("X:")) {
                    optEst = new OptionsFlowAnalyzer.BacktestOptionsEstimate(0, 0, 0, 0);
                    scaledPnlPerContract = 0;
                } else {
                    double holdDays = 1.0; // most intraday setups resolve within 1 trading day
                    optEst = optionsAnalyzer.estimateBacktestOptionsPnl(entry, exitPrice, dir, holdDays, setup.getAtr(), contracts);
                    scaledPnlPerContract = round2(optEst.pnlPerContract() * contracts);
                }

                // Stock outcome is ground truth — the underlying hitting TP or SL is the real
                // signal. The options P&L estimate uses assumed delta/theta/spread and is not
                // backed by real historical option quotes, so it must not override the outcome.
                // It is kept as a side metric (opt_pnl_scaled) for display only.
                String finalOutcome = outcome;
                double finalPnlPct  = pnlPct;

                trades.add(new TradeResult(ticker, dir, effectiveStrat, entry, sl, tp, finalOutcome, finalPnlPct,
                        entryTime, exitTime != null ? exitTime : entryTime,
                        entryEpochMs, resolveExitEpochMs(fwdBars, exitTime, entryEpochMs),
                        setup.getFactorBreakdown(),
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

            double close = fb.getClose();
            double atr = computeAtr5m(atrWindow);
            if (atr <= 0) atr = Math.abs(entry - sl);

            // Mirror live trailing behavior: the scheduler processes confirmed 5m
            // candle closes, not intrabar highs/lows, and closes after the stop
            // has been violated on a confirmed close.
            boolean stopBreached = isLong ? close <= activeSl : close >= activeSl;
            if (stopBreached) {
                double pnlPct = isLong
                        ? round2((close - entry) / entry * 100)
                        : round2((entry - close) / entry * 100);
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

            double high  = fb.getHigh();
            double low   = fb.getLow();
            double close = fb.getClose();

            // Track intrabar high/low for peak — live trailing sees actual wicks, not just closes
            if (isLong  && high  > peakClose) peakClose = high;
            if (!isLong && low   < peakClose) peakClose = low;

            if (!beActive) {
                boolean touchedBe = isLong ? peakClose >= beLevel : peakClose <= beLevel;
                if (touchedBe) beActive = true;
            }

            double currentStop = trailActive ? activeSl : (beActive ? entry : sl);

            // ── SL check (intrabar) — live stop orders fire on touch, not close ──
            boolean stopBreached = isLong ? low <= currentStop : high >= currentStop;
            if (stopBreached) {
                double exitPrice = currentStop; // fill at stop level, matching live behavior
                double pnlPct = isLong
                        ? round2((exitPrice - entry) / entry * 100)
                        : round2((entry - exitPrice) / entry * 100);
                String outcome;
                if (trailActive) outcome = pnlPct > 0 ? "TRAIL_WIN" : "TRAIL_LOSS";
                else outcome = beActive ? "BE_STOP" : "LOSS";
                return new ExitResult(outcome, toDateTime(fb.getTimestamp()), pnlPct);
            }

            // ── TP check (intrabar, only before trail arms) ──────────────────────
            if (!trailActive) {
                boolean tpHit = isLong ? high >= tp : low <= tp;
                if (tpHit) {
                    double pnlPct = isLong
                            ? round2((tp - entry) / entry * 100)
                            : round2((entry - tp) / entry * 100);
                    return new ExitResult("WIN", toDateTime(fb.getTimestamp()), pnlPct);
                }
            }

            if (!trailActive) {
                if (isLong && peakClose >= trailArmLevel) trailActive = true;
                if (!isLong && peakClose <= trailArmLevel) trailActive = true;
                if (trailActive) {
                    peakClose = close;
                    activeSl = trailArmLevel;
                }
            }

            if (trailActive) {
                double atr = computeAtr5m(atrWindow);
                if (atr <= 0) atr = risk;

                boolean reversalClose = isLong
                        ? close < peakClose - atr * 0.20
                        : close > peakClose + atr * 0.20;
                reversalCount = reversalClose ? reversalCount + 1 : 0;

                double atrMult = reversalCount >= REVERSAL_CLOSES ? ATR_TRAIL_REVERSAL : ATR_TRAIL_NORMAL;
                double targetStop = isLong ? peakClose - atr * atrMult : peakClose + atr * atrMult;
                if (isLong) targetStop = Math.max(targetStop, trailArmLevel);
                else         targetStop = Math.min(targetStop, trailArmLevel);
                boolean improving = isLong ? targetStop > activeSl : targetStop < activeSl;
                if (improving) activeSl = targetStop;
            }
        }

        OHLCV lastFwd = fwdBars.get(fwdBars.size() - 1);
        double exitPrice = lastFwd.getClose();
        double pnlPct = isLong
                ? round2((exitPrice - entry) / entry * 100)
                : round2((entry - exitPrice) / entry * 100);
        return new ExitResult("TIMEOUT", toDateTime(lastFwd.getTimestamp()), pnlPct);
    }

    /**
     * Scalp exit: mirrors simulateHybridExit but uses scalp-specific trail constants
     * (HYBRID_BE_R=1.0, SCALP_TRAIL_R=2.0 arm, SCALP_TRAIL_NORMAL=0.75 ATR trail).
     * Keeps full parity with live AlpacaOrderService scalp trailing on 1m bars.
     */
    private ExitResult simulateScalpExit(List<OHLCV> entryWindow, List<OHLCV> fwdBars,
                                         double entry, double sl, double tp, String dir) {
        if (fwdBars.isEmpty()) return null;
        boolean isLong = "long".equals(dir);
        double risk = Math.abs(entry - sl);
        if (risk <= 0) return null;

        double beLevel       = isLong ? entry + risk * HYBRID_BE_R   : entry - risk * HYBRID_BE_R;
        double trailArmLevel = isLong ? entry + risk * SCALP_TRAIL_R : entry - risk * SCALP_TRAIL_R;

        boolean beActive    = false;
        boolean trailActive = false;
        double  activeSl    = sl;
        double  peakClose   = entry;
        int     reversalCount = 0;
        // Use 30-bar window for 1m ATR (1m bars are ~3-5× smaller than 5m bars)
        List<OHLCV> atrWindow = new ArrayList<>(entryWindow);

        for (OHLCV fb : fwdBars) {
            atrWindow.add(fb);
            if (atrWindow.size() > 30) atrWindow.remove(0);

            double high  = fb.getHigh();
            double low   = fb.getLow();
            double close = fb.getClose();

            // Track intrabar high/low — live sees actual wicks, not just closes
            if (isLong  && high  > peakClose) peakClose = high;
            if (!isLong && low   < peakClose) peakClose = low;

            if (!beActive && (isLong ? peakClose >= beLevel : peakClose <= beLevel)) beActive = true;

            double currentStop = trailActive ? activeSl : (beActive ? entry : sl);

            // ── SL check (intrabar) ───────────────────────────────────────────────
            boolean stopBreached = isLong ? low <= currentStop : high >= currentStop;
            if (stopBreached) {
                double exitPrice = currentStop;
                double pnlPct = isLong
                        ? round2((exitPrice - entry) / entry * 100)
                        : round2((entry - exitPrice) / entry * 100);
                String outcome;
                if (trailActive) outcome = pnlPct > 0 ? "TRAIL_WIN" : "TRAIL_LOSS";
                else outcome = beActive ? "BE_STOP" : "LOSS";
                return new ExitResult(outcome, toDateTime(fb.getTimestamp()), pnlPct);
            }

            // ── TP check (intrabar, only before trail arms) ──────────────────────
            if (!trailActive) {
                boolean tpHit = isLong ? high >= tp : low <= tp;
                if (tpHit) {
                    double pnlPct = isLong
                            ? round2((tp - entry) / entry * 100)
                            : round2((entry - tp) / entry * 100);
                    return new ExitResult("WIN", toDateTime(fb.getTimestamp()), pnlPct);
                }
            }

            // Arm trail at 2.0R (SCALP_TRAIL_R); floor activeSl at trailArmLevel immediately
            if (!trailActive) {
                if (isLong ? peakClose >= trailArmLevel : peakClose <= trailArmLevel) {
                    trailActive = true;
                    peakClose   = close;
                    activeSl    = trailArmLevel;
                }
            }

            if (trailActive) {
                double atr = computeAtr5m(atrWindow);
                if (atr <= 0) atr = risk;

                boolean reversalClose = isLong
                        ? close < peakClose - atr * 0.20
                        : close > peakClose + atr * 0.20;
                reversalCount = reversalClose ? reversalCount + 1 : 0;

                double atrMult   = reversalCount >= REVERSAL_CLOSES ? ATR_TRAIL_REVERSAL : SCALP_TRAIL_NORMAL;
                double targetStop = isLong ? peakClose - atr * atrMult : peakClose + atr * atrMult;
                if (isLong) targetStop = Math.max(targetStop, trailArmLevel);
                else        targetStop = Math.min(targetStop, trailArmLevel);
                boolean improving = isLong ? targetStop > activeSl : targetStop < activeSl;
                if (improving) activeSl = targetStop;
            }
        }

        OHLCV lastFwd = fwdBars.get(fwdBars.size() - 1);
        double exitPrice = lastFwd.getClose();
        double pnlPct = isLong
                ? round2((exitPrice - entry) / entry * 100)
                : round2((entry - exitPrice) / entry * 100);
        return new ExitResult("TIMEOUT", toDateTime(lastFwd.getTimestamp()), pnlPct);
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
        // Institutional runners (≥5% gap) use 3R TP so the trade survives to EOD
        // and the overnight hold gate can decide whether to extend into next day.
        // Standard Gap & Go uses 1.5R (intraday scalp target).
        boolean isRunner = Math.abs(gap.gapPct()) >= 5.0;
        double tpMultiplier = isRunner ? 3.0 : 1.5;
        double tp = isGapAndGo
                ? ("long".equals(gap.direction()) ? entry + risk * tpMultiplier : entry - risk * tpMultiplier)
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

    private static boolean isFilteredOutcome(TradeResult t) {
        return t != null && t.outcome() != null && t.outcome().endsWith("_FILTERED");
    }

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
    private long resolveExitEpochMs(List<OHLCV> fwdBars, String exitTime, long fallbackEpochMs) {
        if (fwdBars == null || fwdBars.isEmpty() || exitTime == null || exitTime.isBlank()) return fallbackEpochMs;
        for (OHLCV bar : fwdBars) {
            if (toDateTime(bar.getTimestamp()).equals(exitTime)) return bar.getTimestamp();
        }
        return fwdBars.get(fwdBars.size() - 1).getTimestamp();
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

    public record TradeResult(String ticker, String direction, String strategy, double entry, double sl, double tp,
                               String outcome, double pnlPct,
                               String entryTime, String exitTime,
                               long entryEpochMs, long exitEpochMs,
                               String factorBreakdown,
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
                    "CTX_FILTERED".equals(t.outcome()) || "OPTIONS_FILTERED".equals(t.outcome())).count();
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
            // Only count executed trades (not filtered) — exclude anything ending in _FILTERED
            List<TradeResult> executed = trades.stream()
                    .filter(t -> !isFilteredOutcome(t))
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

    /**
     * Run scalp + intraday + swing as three independent sub-backtests and merge results.
     * Each tier uses its own sub-profile (strategy type, minConf, SL/TP params).
     * Mirrors live ScannerService: scalp, intraday, and swing signals fire independently —
     * you can have both a scalp trade and an intraday trade on the same ticker in the same day.
     *
     * Deduplication: if two tiers use the exact same strategy and fire on the same day
     * at the same entry price, only keep the one with the widest hold window (swing > intraday > scalp).
     * This prevents triple-counting when scalp/intraday/swing all map to "smc".
     */
    private BacktestResult runCombinedAll(String ticker, int lookbackDays, BacktestExitStyle exitStyle) {
        TickerProfile bp = config.getTickerProfile(ticker);
        String scalpStrat   = bp.resolveMode("scalp").resolveStrategy(bp.getStrategyType());
        String intradayStrat = bp.resolveMode("intraday").resolveStrategy(bp.getStrategyType());
        String swingStrat   = bp.resolveMode("swing").resolveStrategy(bp.getStrategyType());

        BacktestResult scalpResult   = run(ticker, lookbackDays, BacktestMode.SCALP,    null, exitStyle);
        BacktestResult intradayResult = run(ticker, lookbackDays, BacktestMode.INTRADAY, null, exitStyle);
        BacktestResult swingResult   = run(ticker, lookbackDays, BacktestMode.SWING,    null, exitStyle);

        // Merge: swing > intraday > scalp priority when strategies are identical.
        // Use entryEpochMs bucketed to the same 5-min bar (within 5 min = same signal).
        // Key = date string (MM/dd) + strategy + direction — if two tiers share all three,
        // only the wider-hold-window tier is kept.
        List<TradeResult> merged = new ArrayList<>();
        // Add swing unconditionally — widest window, highest quality exit
        merged.addAll(swingResult.trades);

        // Track which (timestamp-minute, strategy, direction) slots swing already claimed.
        // Use entry epoch bucketed to the minute for precise dedup (not just date).
        // Applies to both executed AND filtered outcomes so filtered signals don't appear twice
        // when intraday and swing share the same strategy.
        Set<String> swingClaimed = new HashSet<>();
        for (TradeResult t : swingResult.trades) {
            long bucket = (t.entryEpochMs() / 60000L) * 60000L; // floor to minute
            swingClaimed.add(bucket + "|" + t.strategy() + "|" + t.direction());
        }

        // Add intraday only if a different strategy OR swing didn't already claim that slot
        Set<String> intradayClaimed = new HashSet<>(swingClaimed);
        for (TradeResult t : intradayResult.trades) {
            long bucket = (t.entryEpochMs() / 60000L) * 60000L;
            String key  = bucket + "|" + t.strategy() + "|" + t.direction();
            if (intradayStrat.equals(swingStrat) && swingClaimed.contains(key)) continue;
            merged.add(t);
            intradayClaimed.add(key);
        }

        // Add scalp only if it uses a distinct strategy OR neither swing nor intraday claimed the slot
        for (TradeResult t : scalpResult.trades) {
            long bucket = (t.entryEpochMs() / 60000L) * 60000L;
            String key  = bucket + "|" + t.strategy() + "|" + t.direction();
            if (scalpStrat.equals(swingStrat)    && swingClaimed.contains(key))    continue;
            if (scalpStrat.equals(intradayStrat) && intradayClaimed.contains(key)) continue;
            merged.add(t);
        }

        merged.sort(Comparator.comparingLong(TradeResult::entryEpochMs));

        log.info("Backtest {} ({} days, ALL combined): scalp={} intraday={} swing={} → merged={} (deduped by same-strat-same-day)",
                ticker, lookbackDays,
                scalpResult.trades.size(), intradayResult.trades.size(),
                swingResult.trades.size(), merged.size());

        return BacktestResult.of(ticker, merged, lookbackDays, BacktestMode.ALL);
    }
}
