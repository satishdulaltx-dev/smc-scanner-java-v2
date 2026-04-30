package com.smcscanner.strategy;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.broker.AlpacaOrderService;
import com.smcscanner.filter.AdaptiveSuppressor;
import com.smcscanner.filter.SignalQualityFilter;
import com.smcscanner.market.EarningsCalendar;
import com.smcscanner.market.MarketContext;
import com.smcscanner.market.MarketContextService;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.news.NewsService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.indicator.TechnicalIndicators;
import com.smcscanner.model.*;
import com.smcscanner.options.OptionsFlowAnalyzer;
import com.smcscanner.options.OptionsFlowResult;
import com.smcscanner.options.OptionsRecommendation;
import com.smcscanner.state.SharedState;
import com.smcscanner.tracking.LiveTradeLog;
import com.smcscanner.tracking.PerformanceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ScannerService {
    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);

    private final ScannerConfig          config;
    private final PolygonClient          client;
    private final SetupDetector          setupDetector;
    private final CryptoStrategyService  crypto;
    private final MultiTimeframeAnalyzer mtf;
    private final DiscordAlertService    discord;
    private final AlertDedup             dedup;
    private final PerformanceTracker     tracker;
    private final LiveTradeLog           liveLog;
    private final SharedState            state;
    private final AtrCalculator          atrCalc;
    private final VwapStrategyDetector      vwap;
    private final BreakoutStrategyDetector  breakout;
    private final ScalpMomentumDetector     scalpMomentum;
    private final KeyLevelStrategyDetector  keyLevel;
    private final VolatilitySqueezeDetector vSqueeze;
    private final ThreeDayVwapDetector      vwap3d;
    private final IndexDivergenceDetector   indexDiv;
    private final GammaPinDetector          gammaPin;
    private final NewsService              news;
    private final MarketContextService     marketCtx;
    private final SignalQualityFilter      qualityFilter;
    private final AdaptiveSuppressor       adaptive;
    private final OptionsFlowAnalyzer      optionsFlow;
    private final EarningsCalendar         earnings;
    private final SwingTradeDetector       swingDetector;
    private final RangeDetector            rangeDetector;
    private final AlpacaOrderService       alpaca;
    private final TechnicalIndicators      techIndicators;
    private final GapDetector              gapDetector;
    private final MarketRegimeDetector     regimeDetector;
    private final PivotPointService        pivotService;
    private final PressureService          pressureService;
    private final OvernightMomentumService overnightService;
    private final PowerEarningsGapDetector        pegDetector;
    private final CapitulationReversalDetector    capReversal;
    private final LiquiditySweepFlipDetector      sweepFlip;
    private final PdhPdlDetector                  pdhPdl;
    private final OpeningRangeVwapDetector        orVwap;
    private final LiquidityMapService             liquidityMap;

    public ScannerService(ScannerConfig config, PolygonClient client, SetupDetector setupDetector,
                          CryptoStrategyService crypto, MultiTimeframeAnalyzer mtf,
                          DiscordAlertService discord, AlertDedup dedup,
                          PerformanceTracker tracker, LiveTradeLog liveLog, SharedState state, AtrCalculator atrCalc,
                          VwapStrategyDetector vwap, BreakoutStrategyDetector breakout,
                          ScalpMomentumDetector scalpMomentum,
                          KeyLevelStrategyDetector keyLevel,
                          VolatilitySqueezeDetector vSqueeze, ThreeDayVwapDetector vwap3d,
                          IndexDivergenceDetector indexDiv, GammaPinDetector gammaPin,
                          NewsService news,
                          MarketContextService marketCtx, SignalQualityFilter qualityFilter,
                          AdaptiveSuppressor adaptive, OptionsFlowAnalyzer optionsFlow,
                          EarningsCalendar earnings, SwingTradeDetector swingDetector,
                          RangeDetector rangeDetector, AlpacaOrderService alpaca,
                          TechnicalIndicators techIndicators, GapDetector gapDetector,
                          MarketRegimeDetector regimeDetector, PivotPointService pivotService,
                          PressureService pressureService, OvernightMomentumService overnightService,
                          PowerEarningsGapDetector pegDetector,
                          CapitulationReversalDetector capReversal,
                          LiquiditySweepFlipDetector sweepFlip,
                          PdhPdlDetector pdhPdl,
                          OpeningRangeVwapDetector orVwap,
                          LiquidityMapService liquidityMap) {
        this.config=config; this.client=client; this.setupDetector=setupDetector; this.crypto=crypto;
        this.mtf=mtf; this.discord=discord; this.dedup=dedup; this.tracker=tracker; this.liveLog=liveLog; this.state=state;
        this.atrCalc=atrCalc; this.vwap=vwap; this.breakout=breakout; this.scalpMomentum=scalpMomentum; this.keyLevel=keyLevel;
        this.vSqueeze=vSqueeze; this.vwap3d=vwap3d; this.indexDiv=indexDiv; this.gammaPin=gammaPin;
        this.news=news; this.marketCtx=marketCtx; this.qualityFilter=qualityFilter; this.adaptive=adaptive;
        this.optionsFlow=optionsFlow; this.earnings=earnings; this.swingDetector=swingDetector;
        this.rangeDetector=rangeDetector; this.alpaca=alpaca; this.techIndicators=techIndicators;
        this.gapDetector=gapDetector; this.regimeDetector=regimeDetector; this.pivotService=pivotService;
        this.pressureService=pressureService; this.overnightService=overnightService;
        this.pegDetector=pegDetector; this.capReversal=capReversal;
        this.sweepFlip=sweepFlip; this.pdhPdl=pdhPdl; this.orVwap=orVwap;
        this.liquidityMap=liquidityMap;
    }

    public boolean isCrypto(String t) { return t.startsWith("X:"); }

    public void scanTicker(String ticker) {
        try {
            // Check per-ticker profile: skip if ALL three modes are disabled
            com.smcscanner.model.TickerProfile profile = config.getTickerProfile(ticker);
            com.smcscanner.model.TickerProfile.ModeProfile scalpMode    = profile.resolveMode("scalp");
            com.smcscanner.model.TickerProfile.ModeProfile intradayMode = profile.resolveMode("intraday");
            com.smcscanner.model.TickerProfile.ModeProfile swingMode    = profile.resolveMode("swing");
            boolean rootSkip    = profile.isSkip();
            boolean intradayActive = !intradayMode.isEffectiveSkip(rootSkip);
            boolean anyActive   = !scalpMode.isEffectiveSkip(rootSkip)
                               || intradayActive
                               || !swingMode.isEffectiveSkip(rootSkip);
            if (!anyActive) {
                setTs(ticker,"idle",null,0,"⊘ "+profile.getSkipReason());
                return;
            }
            // Use intraday mode params as the baseline for confidence thresholds
            int parentMinConf   = profile.resolveMinConfidence(config.getMinConfidence());
            int parentMaxConf   = profile.resolveMaxConfidence();
            int effectiveMinConf = intradayMode.resolveMinConfidence(parentMinConf, config.getMinConfidence());
            int effectiveMaxConf = intradayMode.resolveMaxConfidence(parentMaxConf);

            // ── Intraday-only skip gate ───────────────────────────────────────
            // If the root profile is skip=true and ONLY the swing sub-profile is
            // active, this ticker should never send an intraday alert.
            boolean onlySwingActive = !scalpMode.isEffectiveSkip(rootSkip)  == false
                                   && !intradayMode.isEffectiveSkip(rootSkip) == false
                                   && !swingMode.isEffectiveSkip(rootSkip);
            if (rootSkip && onlySwingActive) {
                // Swing alerts are handled by the dedicated swing scanner, not here.
                setTs(ticker, "idle", null, 0, "⊘ Swing-only ticker — no intraday alert");
                return;
            }

            setTs(ticker,"scanning",null,0,"Fetching data...");
            boolean isC=isCrypto(ticker);
            List<OHLCV> bars=client.getBars(ticker,"5m",100);
            if (bars==null||bars.size()<20) { setTs(ticker,"idle",null,0,"No data"); return; }

            // ── Strip incomplete (open) bar ───────────────────────────────────
            // Polygon's /v2/aggs returns the CURRENT incomplete bar as the last
            // result. Acting on a partial bar (e.g. 2 min into the 9:30 candle)
            // generates false "setups" on noise — the bar has no close, no
            // confirmed structure. Drop it if the bar hasn't closed yet.
            if (!isC && !bars.isEmpty()) {
                long lastBarOpenMs = bars.get(bars.size()-1).getTimestamp();
                long barCloseMs    = lastBarOpenMs + 5L * 60 * 1000;
                if (System.currentTimeMillis() < barCloseMs) {
                    bars = new java.util.ArrayList<>(bars.subList(0, bars.size()-1));
                    log.debug("{} INCOMPLETE_BAR_STRIP: dropped open bar t={}",
                            ticker, java.time.Instant.ofEpochMilli(lastBarOpenMs));
                }
            }
            if (bars.size() < 20) { setTs(ticker,"idle",null,0,"Waiting for bars..."); return; }

            // ── Market regime detection (live: 15-min cache per ticker) ───────
            // LOW_LIQUIDITY (RVOL < 0.8) gates out the scan entirely — signals on
            // thin volume are noise. Other regimes feed regimeStratAdj later.
            MarketRegimeDetector.Regime regime = MarketRegimeDetector.Regime.RANGING;
            if (!isC) {
                regime = regimeDetector.detect(bars, ticker);
                if (regime == MarketRegimeDetector.Regime.LOW_LIQUIDITY) {
                    setTs(ticker, "idle", null, 0, "⊘ Low liquidity — RVOL < 0.8");
                    return;
                }
            }

            // ── Session bars + gap state (computed once, reused by pressure checks) ──
            // Session bars = today's 9:30–4:00 bars only (no pre-market noise).
            // prevSessionClose = last close before today's open (gap calculation).
            // gapLongBlocked = LONG disabled until gap 50% filled or 30 min elapsed.
            List<OHLCV> sessionBars5m       = isC ? bars : pressureService.getSessionBars(bars);
            double      prevSessionClose    = isC ? 0.0  : pressureService.getPrevSessionClose(bars);
            boolean     gapLongBlocked      = !isC && pressureService.isLongBlockedByGap(sessionBars5m, prevSessionClose);

            String htfBias="neutral";
            double dailyAtr=0.0;
            List<OHLCV> dailyBars=null;
            List<OHLCV> hourlyBars=null;

            try { hourlyBars=client.getBars(ticker,"60m",50); if (hourlyBars!=null&&hourlyBars.size()>=10) htfBias=mtf.getHtfBias(hourlyBars); }
            catch (Exception e) { log.debug("{} HTF error: {}",ticker,e.getMessage()); }

            // ── 15m bias (pre-fetched here, used for alignment check after setup detection) ──
            String bias15m = "neutral";
            List<OHLCV> bars15Ref = List.of(); // kept for fractal-anchor squeeze check below
            if (!isC) {
                try {
                    List<OHLCV> bars15 = client.getBars(ticker, "15m", 60);
                    if (bars15 != null && bars15.size() >= 10) {
                        bias15m   = mtf.getHtfBias(bars15);
                        bars15Ref = bars15;
                    }
                } catch (Exception e) { log.debug("{} 15m bias error: {}", ticker, e.getMessage()); }
            }

            // ── Cross-asset correlation bias (soft penalty, not hard block) ───
            // COIN/MARA track BTC (~90% intraday correlation).
            // AMD/SMCI track NVDA (AI/semi cluster moves together).
            //
            // When the correlated asset DISAGREES with the setup direction:
            //   -20 for crypto proxies  (BTC is the driver — strong signal)
            //   -15 for semi cluster    (NVDA leads AMD/SMCI — strong signal)
            // When correlated asset AGREES: +5 bonus (additional confirmation).
            // "neutral" correlated asset → 0 adjustment (no clear trend to use).
            //
            // Stored in corrAdj; applied in totalAdj below after setup detection.
            // Uses soft penalty so a 95-conf COIN setup isn't killed by neutral BTC —
            // only genuinely conflicting setups get penalised.
            String corrAsset = null;
            int    corrConflictPenalty = 0;
            int    corrAgreementBonus  = 0;
            String corrBias = "neutral";
            if (!isC) {
                if      (ticker.equals("COIN") || ticker.equals("MARA")) { corrAsset = "X:BTCUSD"; corrConflictPenalty = -20; corrAgreementBonus = +5; }
                else if (ticker.equals("AMD")  || ticker.equals("SMCI")) { corrAsset = "NVDA";     corrConflictPenalty = -15; corrAgreementBonus = +5; }
            }
            if (corrAsset != null) {
                try {
                    List<OHLCV> corrBars = client.getBars(corrAsset, "15m", 60);
                    if (corrBars != null && corrBars.size() >= 10) {
                        corrBias = mtf.getHtfBias(corrBars);
                        log.debug("{} corr asset={} bias={}", ticker, corrAsset, corrBias);
                    }
                } catch (Exception e) { log.debug("{} correlation fetch error: {}", ticker, e.getMessage()); }
            }
            try {
                dailyBars=client.getBars(ticker,"1d",250); // 250 bars: enough for SMA 200 + swing detection + ATR
                if (dailyBars!=null&&dailyBars.size()>=5) {
                    double[] da=atrCalc.computeAtr(dailyBars,Math.min(14,dailyBars.size()-1));
                    for (int i=da.length-1;i>=0;i--) { if (da[i]>0) { dailyAtr=da[i]; break; } }
                }
            } catch (Exception e) { log.debug("{} daily bars error: {}",ticker,e.getMessage()); }

            // ── HTF staleness gate ────────────────────────────────────────────
            // If hourly price moved > 1.5× dailyATR against the HTF bias in the
            // last 10 bars, that bias is stale (rapid regime shift). Degrade to
            // "neutral" so HTF_CONFLICT doesn't block setups aligned with the
            // NEW trend direction. Catches crashes/squeezes like April 2026 tariff.
            if (!"neutral".equals(htfBias) && dailyAtr > 0 && hourlyBars != null && hourlyBars.size() >= 5) {
                int staleWindow = Math.min(10, hourlyBars.size());
                List<OHLCV> recentHtf = hourlyBars.subList(hourlyBars.size() - staleWindow, hourlyBars.size());
                double priceMove = recentHtf.get(recentHtf.size() - 1).getClose() - recentHtf.get(0).getOpen();
                boolean staleUp   = "bearish".equals(htfBias) && priceMove >  dailyAtr * 1.5;
                boolean staleDown = "bullish".equals(htfBias) && priceMove < -dailyAtr * 1.5;
                if (staleUp || staleDown) {
                    log.debug("{} HTF_STALE: bias={} priceMove={} dailyAtr={} → neutral",
                        ticker, htfBias, String.format("%.2f", priceMove), String.format("%.2f", dailyAtr));
                    htfBias = "neutral";
                }
            }

            // ── Intraday alert (5m bars → original Discord channel) ─────────
            long lastBarTs = bars.isEmpty() ? System.currentTimeMillis()
                    : bars.get(bars.size() - 1).getTimestamp();
            String rootStratType = profile.hasTimeRouting()
                    ? profile.resolveStrategyForTime(lastBarTs)
                    : profile.getStrategyType();
            String intradayStratType = intradayMode.getStrategyType() != null
                    ? intradayMode.getStrategyType()
                    : rootStratType;

            // ── Universal intraday time gate ──────────────────────────────────
            // Pre-compute so capReversal/fallback overlays share the same gate.
            java.time.LocalTime etNowIntraday = isC ? java.time.LocalTime.NOON
                    : java.time.Instant.ofEpochMilli(lastBarTs)
                            .atZone(java.time.ZoneId.of("America/New_York")).toLocalTime();
            boolean intradayTooEarly = !isC && etNowIntraday.isBefore(java.time.LocalTime.of(9, 45));
            boolean intradayTooLate  = !isC && !etNowIntraday.isBefore(java.time.LocalTime.of(15, 30));

            List<TradeSetup> setups; String phaseMsg;
            if (isC) { setups=crypto.detectCryptoSetup(bars,ticker); phaseMsg=setups.isEmpty()?"Waiting for breakout + volume spike...":""; }
            else if (intradayTooEarly) {
                // OR-VWAP is exempt from the 9:45 gate — it fires specifically in the opening flush window
                List<TradeSetup> orEarlySetups = orVwap.detect(bars, ticker, dailyAtr);
                if (!orEarlySetups.isEmpty()) {
                    setups   = List.of(orEarlySetups.get(0));
                    phaseMsg = "";
                } else {
                    setups   = List.of();
                    phaseMsg = "⏳ Opening range — watching for VWAP flush recovery...";
                }
                setTs(ticker, "idle", null, 0, phaseMsg);
            } else if (intradayTooLate) {
                // Last 30 min: approaching close, spreads widen, new intraday entries unreliable
                setups = List.of();
                phaseMsg = "🔒 Late session — no new entries after 15:30 ET";
                setTs(ticker, "idle", null, 0, phaseMsg);
            } else {
                // ── Multi-strategy dispatch ─────────────────────────────────────
                // Run ALL strategy types from active sub-profiles simultaneously.
                // Previously a single strategyType was time-routed (scalp OR keylevel),
                // meaning tickers like COIN never ran their intraday strategy alongside
                // their scalp strategy. Now each active sub-profile contributes its own
                // strategy type; the highest-confidence setup across all wins.
                java.util.Set<String> stratTypes = new java.util.LinkedHashSet<>();
                // Add scalp sub-profile strategy if that mode is active
                if (!scalpMode.isEffectiveSkip(rootSkip)) {
                    String st = scalpMode.getStrategyType();
                    if (st != null) stratTypes.add(st);
                }
                // Add intraday sub-profile strategy if that mode is active
                if (intradayActive) {
                    stratTypes.add(intradayStratType);
                }

                if (stratTypes.size() > 1) {
                    log.debug("{} MULTI_STRAT: running {} strategies: {}", ticker, stratTypes.size(), stratTypes);
                } else if (profile.hasTimeRouting()) {
                    log.debug("{} TIME_ROUTE: ts={} → strategy={}", ticker, lastBarTs, rootStratType);
                }

                // Pre-fetch SPY 5m bars once if scalp or idiv is in the strategy set
                List<OHLCV> spyBars5m = List.of();
                if (stratTypes.contains("scalp") || stratTypes.contains("idiv")) {
                    try {
                        List<OHLCV> sp = client.getBars("SPY", "5m", 100);
                        if (sp != null) spyBars5m = sp;
                    } catch (Exception e) { log.debug("{} SPY 5m fetch error: {}", ticker, e.getMessage()); }
                }

                // Evaluate scalp gates — only suppress scalp, never abort other strategies
                boolean scalpSuppressed = false;
                String  scalpSuppressMsg = null;
                if (stratTypes.contains("scalp")) {
                    if (regime == MarketRegimeDetector.Regime.VOLATILE) {
                        scalpSuppressed = true;
                        scalpSuppressMsg = "VOLATILE regime — scalp edge suppressed";
                    } else if (spyBars5m.size() >= 10) {
                        double spyOpen    = spyBars5m.get(0).getOpen();
                        double spyCurrent = spyBars5m.get(spyBars5m.size() - 1).getClose();
                        double spyMove    = spyOpen > 0 ? Math.abs(spyCurrent - spyOpen) / spyOpen : 0;
                        if (spyMove > 0.018) {
                            scalpSuppressed = true;
                            scalpSuppressMsg = String.format("SPY ±%.1f%% intraday — news tape, scalp suppressed", spyMove * 100);
                        }
                    }
                    if (scalpSuppressed)
                        log.debug("{} ⚠️ {} — scalp skipped, continuing with other strategies", ticker, scalpSuppressMsg);
                }

                // Run every configured strategy; collect all setups
                List<TradeSetup> allSetups = new ArrayList<>();
                phaseMsg = "";
                for (String strat : stratTypes) {
                    if ("scalp".equals(strat) && scalpSuppressed) continue;
                    List<TradeSetup> stratSetups;
                    if ("scalp".equals(strat)) {
                        stratSetups = scalpMomentum.detect(bars, spyBars5m, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Waiting for Bollinger reclaim or squeeze break...";
                    } else if ("vwap".equals(strat)) {
                        // 1-per-day cap: once a VWAP trade fires on this ticker today, skip all
                        // subsequent VWAP scans for the rest of the session (prevents "death by
                        // a thousand cuts" where TSLA fires 3× on the same choppy day).
                        if (liveLog.hasStrategyFiredToday(ticker, "vwap")) {
                            stratSetups = List.of();
                            if (phaseMsg.isEmpty()) phaseMsg = "⊘ VWAP trade already placed today";
                        } else {
                            boolean vwapLongOnly = profile.isVwapLongOnly() || "MOMENTUM".equals(profile.getExplicitCharacter());
                            stratSetups = vwap.detect(bars, ticker, dailyAtr, false, vwapLongOnly);
                            if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Waiting for VWAP reversion...";
                        }
                    } else if ("breakout".equals(strat)) {
                        stratSetups = breakout.detect(bars, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Waiting for ORB breakout...";
                    } else if ("keylevel".equals(strat)) {
                        stratSetups = keyLevel.detect(bars, dailyBars, ticker, dailyAtr, profile);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Waiting for key level rejection...";
                    } else if ("vsqueeze".equals(strat)) {
                        stratSetups = vSqueeze.detect(bars, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Watching for volatility squeeze release...";
                    } else if ("vwap3d".equals(strat)) {
                        List<OHLCV> multiDayBars = bars;
                        try {
                            List<OHLCV> wider = client.getBars(ticker, "5m", 300); // ~3 trading days
                            if (wider != null && wider.size() >= 30) multiDayBars = wider;
                        } catch (Exception e) { log.debug("{} 3dVWAP fetch error: {}", ticker, e.getMessage()); }
                        stratSetups = vwap3d.detect(multiDayBars, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Watching for 3-day VWAP reversion...";
                    } else if ("idiv".equals(strat)) {
                        stratSetups = indexDiv.detect(bars, spyBars5m, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Watching for SPY/AAPL divergence...";
                    } else if ("gammapin".equals(strat)) {
                        stratSetups = gammaPin.detect(bars, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Watching for gamma pin convergence...";
                    } else if ("cap_reversal".equals(strat)) {
                        stratSetups = capReversal.detect(bars, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Watching for capitulation waterfall + reversal...";
                    } else if ("or-vwap".equals(strat)) {
                        stratSetups = orVwap.detect(bars, ticker, dailyAtr);
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = "Waiting for opening VWAP flush recovery...";
                    } else {
                        // smc (default)
                        SetupDetector.DetectResult r = setupDetector.detectSetups(bars, htfBias, ticker, false, dailyAtr);
                        stratSetups = r.setups();
                        if (stratSetups.isEmpty() && phaseMsg.isEmpty()) phaseMsg = r.state().phaseMsg();
                    }
                    allSetups.addAll(stratSetups);
                }
                // Pick highest-confidence setup across all strategies
                allSetups.sort(java.util.Comparator.comparingInt(TradeSetup::getConfidence).reversed());
                setups = allSetups.isEmpty() ? List.of() : List.of(allSetups.get(0));

                // If scalp was the only active strategy and it was suppressed, report it
                if (setups.isEmpty() && scalpSuppressed
                        && stratTypes.stream().allMatch(s -> "scalp".equals(s))) {
                    setTs(ticker, "idle", null, 0, "⚠️ " + scalpSuppressMsg);
                    return;
                }
            }

            // ── Capitulation reversal overlay — runs on ALL equity tickers ────
            // When the primary strategy finds nothing AND price has dropped ≥2.5%
            // in recent bars, check for a capitulation reversal bounce.
            // This catches the COIN/SOFI waterfall pattern regardless of configured strategy.
            // Blocked in VOLATILE regime only (too much false-positive noise).
            if (intradayActive && setups.isEmpty() && !isC && !intradayTooEarly && !intradayTooLate && regime != MarketRegimeDetector.Regime.VOLATILE) {
                List<TradeSetup> capSetups = capReversal.detect(bars, ticker, dailyAtr);
                if (!capSetups.isEmpty()) {
                    log.info("{} CAP_REVERSAL_OVERLAY: waterfall + reversal detected — primary strategy={}", ticker,
                            intradayStratType);
                    setups = capSetups;
                    phaseMsg = "";
                }
            }

            // ── Pattern overlays: sweep-flip, PDH/PDL, CHOCH primary ──────────
            // Run for all non-crypto NYSE tickers after primary strategy + cap overlay.
            // These detect setups that don't require a full SMC chain.
            if (intradayActive && setups.isEmpty() && !isC && !intradayTooEarly && !intradayTooLate) {
                List<TradeSetup> overlaySetups = new java.util.ArrayList<>();
                overlaySetups.addAll(sweepFlip.detect(bars, ticker, dailyAtr));
                overlaySetups.addAll(pdhPdl.detect(bars, ticker, dailyAtr));
                overlaySetups.addAll(setupDetector.detectChochPrimary(bars, ticker, dailyAtr, false));
                if (!overlaySetups.isEmpty()) {
                    overlaySetups.sort(java.util.Comparator.comparingInt(TradeSetup::getConfidence).reversed());
                    setups = List.of(overlaySetups.get(0));
                    phaseMsg = "";
                    log.info("{} OVERLAY_SIGNAL: {} (conf={})", ticker,
                            setups.get(0).getFactorBreakdown(), setups.get(0).getConfidence());
                }
            }

            // ── Regime-based fallback when primary strategy finds nothing ─────
            // e.g. SMC ticker in a RANGING day → try keylevel instead.
            // Only the 3 generic regimes have clear fallbacks; VOLATILE/LOW_LIQUIDITY
            // don't (LOW_LIQUIDITY is already gated above, VOLATILE trusts the profile).
            if (intradayActive && setups.isEmpty() && !isC && !intradayTooEarly && !intradayTooLate) {
                String strategyType = intradayStratType;
                String fallbackStrat = regimeDetector.suggestStrategy(regime, strategyType);
                if (fallbackStrat != null && !fallbackStrat.equals(strategyType)) {
                    List<TradeSetup> fb = switch (fallbackStrat) {
                        case "smc" -> {
                            SetupDetector.DetectResult fr = setupDetector.detectSetups(bars, htfBias, ticker, false, dailyAtr);
                            yield fr.setups();
                        }
                        case "scalp" -> {
                            // Same gates as primary scalp path
                            if (regime == MarketRegimeDetector.Regime.VOLATILE) { yield List.of(); }
                            List<OHLCV> spyBars5m = List.of();
                            try {
                                List<OHLCV> sp = client.getBars("SPY", "5m", 100);
                                if (sp != null) spyBars5m = sp;
                            } catch (Exception e) { log.debug("{} SPY 5m fetch error: {}", ticker, e.getMessage()); }
                            if (spyBars5m.size() >= 10) {
                                double spyOpen = spyBars5m.get(0).getOpen();
                                double spyCur  = spyBars5m.get(spyBars5m.size() - 1).getClose();
                                if (spyOpen > 0 && Math.abs(spyCur - spyOpen) / spyOpen > 0.018) { yield List.of(); }
                            }
                            yield scalpMomentum.detect(bars, spyBars5m, ticker, dailyAtr);
                        }
                        case "keylevel" -> keyLevel.detect(bars, dailyBars, ticker, dailyAtr, profile);
                        case "vsqueeze" -> vSqueeze.detect(bars, ticker, dailyAtr);
                        default -> List.of();
                    };
                    if (!fb.isEmpty()) {
                        log.info("{} REGIME_FALLBACK: primary={} regime={} → fallback={} fired",
                                ticker, strategyType, regime, fallbackStrat);
                        setups = fb;
                        phaseMsg = "";
                    }
                }
            }

            if (!setups.isEmpty()) {
                TradeSetup s=setups.get(0);

                // ── Gap long block (hard gate for LONG on unresolved gap-up) ─
                // Smart money sells into retail longs on gap-up opens. Hold off
                // until the gap is 50% filled or 30 minutes have elapsed.
                if ("long".equals(s.getDirection()) && gapLongBlocked) {
                    log.info("{} GAP_LONG_BLOCK: gap-up unfilled — LONG disabled this tick", ticker);
                    setTs(ticker, "idle", null, 0, "⊘ Gap-fill wait — LONG blocked");
                    removeSetup(ticker);
                    return;
                }

                // ── SPY intraday directional gate ─────────────────────────────
                // When SPY moves >1.5% intraday, the market has strong directional
                // conviction. Counter-trend setups fail at >2× normal rate.
                // Hard-veto: skip the setup entirely (swing/overnight still runs).
                if (!isC && !"SPY".equals(ticker) && !"QQQ".equals(ticker)) {
                    List<OHLCV> spyGateBars = List.of();
                    try {
                        List<OHLCV> sp = client.getBars("SPY", "5m", 80);
                        if (sp != null) spyGateBars = pressureService.getSessionBars(sp);
                    } catch (Exception e) { log.debug("{} SPY gate fetch: {}", ticker, e.getMessage()); }
                    if (spyGateBars.size() >= 3) {
                        double spyOpen = spyGateBars.get(0).getOpen();
                        double spyCur  = spyGateBars.get(spyGateBars.size() - 1).getClose();
                        double spyMove = spyOpen > 0 ? (spyCur - spyOpen) / spyOpen : 0;
                        if (spyMove > 0.015 && "short".equals(s.getDirection())) {
                            log.info("{} SPY_BIAS_BLOCK: SPY +{:.1f}%% intraday — SHORT vetoed (counter-trend)",
                                    ticker, spyMove * 100);
                            setTs(ticker, "idle", null, 0,
                                    String.format("↑ SPY +%.1f%% intraday — no shorts today", spyMove * 100));
                            removeSetup(ticker);
                            return;
                        }
                        if (spyMove < -0.015 && "long".equals(s.getDirection())) {
                            log.info("{} SPY_BIAS_BLOCK: SPY {:.1f}%% intraday — LONG vetoed (counter-trend)",
                                    ticker, spyMove * 100);
                            setTs(ticker, "idle", null, 0,
                                    String.format("↓ SPY %.1f%% intraday — no longs today", spyMove * 100));
                            removeSetup(ticker);
                            return;
                        }
                    }
                }

                // ── Ticker DNA (character) gates ─────────────────────────────
                // Structural incompatibilities identified from 30d loss audit:
                //   EXTERNAL_CORRELATED (MARA): SMC patterns = noise vs BTC moves. Block.
                //   STABLE_LARGE_CAP (AAPL/MSFT): options decay before slow stock hits TP.
                //                                  Only trade with confirmed news catalyst.
                //   FINANCIAL (GS/V/JPM): option premium too large vs typical % move.
                //                         -20 conf penalty makes these nearly un-fireable.
                //   SPECULATIVE_LOW_PRICE (SOFI): SL must be widened to survive 3-5% wicks.
                if (!isC) {
                    // EXTERNAL_CORRELATED: SMC blocked, swing-only allowed
                    if (profile.isSmcBlocked()) {
                        String strat = s.getVolatility(); // strategy label lives in volatility field
                        if (!"scalp".equals(strat) && !"keylevel".equals(strat)) {
                            log.info("{} DNA_BLOCK EXTERNAL_CORRELATED: SMC blocked (BTC-correlated, not order-flow driven)", ticker);
                            setTs(ticker, "idle", null, 0, "⊘ BTC-correlated — SMC not applicable");
                            removeSetup(ticker);
                            return;
                        }
                    }
                    // STABLE_LARGE_CAP: require news catalyst (no catalyst = time-decay death)
                    if (profile.isCatalystRequired()) {
                        boolean hasCatalyst = news.getSentiment(ticker).isAligned(s.getDirection());
                        if (!hasCatalyst) {
                            log.info("{} DNA_BLOCK STABLE_LARGE_CAP: no catalyst — slow mover, options would decay before TP", ticker);
                            setTs(ticker, "idle", null, 0, "⊘ No catalyst — " + ticker + " only trades on news days");
                            removeSetup(ticker);
                            return;
                        }
                    }
                    // Universal SL floor: 1.5% of price for any stock under $30.
                    // Ticker-character override (SPECULATIVE_LOW_PRICE) adds extra floor.
                    // Prevents wick-out on low-price volatile stocks (SOFI: $0.05 ATR → $0.08 SL = dead).
                    double minSlPct = Math.max(profile.minSlPricePct(),
                            s.getEntry() < 30.0 ? 0.015 : 0.0);
                    if (minSlPct > 0) {
                        double entry = s.getEntry();
                        double slDist = Math.abs(s.getStopLoss() - entry);
                        double minSlDist = entry * minSlPct;
                        if (slDist < minSlDist) {
                            double newSl = "long".equals(s.getDirection())
                                    ? Math.round((entry - minSlDist) * 10000.0) / 10000.0
                                    : Math.round((entry + minSlDist) * 10000.0) / 10000.0;
                            log.info("{} DNA_SL_FLOOR: SL widened {:.4f}→{:.4f} (minSlPct={}%)", ticker,
                                    s.getStopLoss(), newSl, minSlPct * 100);
                            s = TradeSetup.builder()
                                    .ticker(s.getTicker()).direction(s.getDirection())
                                    .entry(s.getEntry()).stopLoss(newSl).takeProfit(s.getTakeProfit())
                                    .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                                    .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                                    .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                                    .build();
                        }
                    }
                }

                // ── Volume pressure trap detection ────────────────────────────
                // BOS with declining bar pressure = institutional trap. Bypassed
                // for VWAP (mean-reversion) and SQUEEZE regime (volume naturally low).
                String activeStrat = intradayStratType;
                int trapAdj = !isC ? pressureService.computeTrapAdj(
                        bars, s.getDirection(), activeStrat, regime) : 0;
                if (trapAdj != 0) {
                    log.info("{} TRAP_ADJ: dir={} strat={} regime={} → {}",
                            ticker, s.getDirection(), activeStrat, regime, trapAdj);
                }

                // ── ATR exhaustion gate ───────────────────────────────────────
                // Session range ≥ 90% of 20d avg daily range → ticker is tired.
                // Cap TP to 1:1 R:R; apply -10 confidence penalty.
                int exhaustionAdj = 0;
                if (!isC && !sessionBars5m.isEmpty()) {
                    PressureService.ExhaustionResult exh =
                            pressureService.checkExhaustion(sessionBars5m, dailyBars);
                    if (exh.exhausted()) {
                        exhaustionAdj = -10;
                        double entry = s.getEntry();
                        double risk  = Math.abs(s.getStopLoss() - entry);
                        double capTp = "long".equals(s.getDirection()) ? entry + risk : entry - risk;
                        capTp = Math.round(capTp * 10000.0) / 10000.0;
                        s = TradeSetup.builder()
                                .ticker(s.getTicker()).direction(s.getDirection())
                                .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(capTp)
                                .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                                .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                                .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                                .build();
                        log.info("{} EXHAUSTION: rangeRatio={} → 1:1 TP={} adj={}",
                                ticker, String.format("%.2f", exh.rangeRatio()), capTp, exhaustionAdj);
                    }
                }

                // ── Intraday RS (mega-caps: AAPL, MSFT, NVDA, AMZN) ────────
                // Soft confidence adjustment (not hard block) to avoid signal starvation.
                // Also checks absolute trend anchor to prevent "falling knife" longs
                // (buying a stock just because it's bleeding slower than SPY).
                int intradayRsAdj = 0;
                double intradayRsVal = 0.0;
                if (profile.isIntradayRsGate() && !isC) {
                    intradayRsVal = marketCtx.computeIntradayRs(bars);
                    intradayRsAdj = marketCtx.computeIntradayRsDelta(intradayRsVal, bars, s.getDirection());
                    if (intradayRsAdj != 0) {
                        log.info("{} intraday RS adj={} rs={} dir={}", ticker, intradayRsAdj,
                                String.format("%.4f", intradayRsVal), s.getDirection());
                    }
                }

                // ── RS continuous TP multiplier [0.7 → 1.5] ─────────────────
                // Strong outperformance vs SPY → extend TP (stock has momentum).
                // Lagging SPY → tighten TP (less room to run). Float, not boolean.
                if (profile.isIntradayRsGate() && !isC && intradayRsVal > 0) {
                    double rsMultiplier = 1.0;
                    if      (intradayRsVal > 1.3) rsMultiplier = Math.min(1.5, intradayRsVal);
                    else if (intradayRsVal < 0.8) rsMultiplier = Math.max(0.7, intradayRsVal);
                    if (rsMultiplier != 1.0) {
                        double entry  = s.getEntry();
                        double tpDist = Math.abs(s.getTakeProfit() - entry) * rsMultiplier;
                        double newTp  = "long".equals(s.getDirection()) ? entry + tpDist : entry - tpDist;
                        newTp = Math.round(newTp * 10000.0) / 10000.0;
                        s = TradeSetup.builder()
                                .ticker(s.getTicker()).direction(s.getDirection())
                                .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(newTp)
                                .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                                .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                                .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                                .build();
                        log.info("{} RS_TP_MULT: rs={} mult={} newTp={}", ticker,
                                String.format("%.3f", intradayRsVal), String.format("%.2f", rsMultiplier), newTp);
                    }
                }

                // ── VOLATILE regime SL+TP widening (R:R preserved) ───────────
                // In chaotic conditions ATR expands; a normal SL gets clipped quickly.
                // Widen SL by slExpansionFactor (1.5×) for breathing room.
                // CRITICAL: also scale TP by same factor to preserve R:R.
                // Old code only widened SL → converted 1.5:1 trades to sub-1:1 in
                // volatile regimes (e.g. April 2026 tariff market = 66% sub-1:1 R:R).
                if (regime == MarketRegimeDetector.Regime.VOLATILE && !isC) {
                    double slFactor = regimeDetector.slExpansionFactor(regime);
                    double entry   = s.getEntry();
                    double slDist  = Math.abs(s.getStopLoss() - entry) * slFactor;
                    double tpDist  = Math.abs(s.getTakeProfit() - entry) * slFactor; // preserve R:R
                    double newSl   = "long".equals(s.getDirection()) ? entry - slDist : entry + slDist;
                    double newTp   = "long".equals(s.getDirection()) ? entry + tpDist : entry - tpDist;
                    newSl = Math.round(newSl * 10000.0) / 10000.0;
                    newTp = Math.round(newTp * 10000.0) / 10000.0;
                    s = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(newSl).takeProfit(newTp)
                            .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                            .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                            .build();
                    log.info("{} VOLATILE_SL_TP: widened {}× newSl={} newTp={}", ticker, slFactor, newSl, newTp);
                }

                // ── 15m alignment check ───────────────────────────────────────
                // Changed from hard block → soft penalty (-15 confidence).
                // Hard block was wiping valid counter-trend entries that had strong
                // conviction from other signals (volume, key level, etc).
                // BYPASS for VWAP: mean-reversion trades intentionally fight the trend.
                String stratTypeForFilter = intradayStratType;
                boolean is15mApplicable = !"vwap".equals(stratTypeForFilter) && !"vwap3d".equals(stratTypeForFilter);
                int bias15mAdj = 0;
                boolean is15mConflict = !isC && is15mApplicable && (
                        ("bullish".equals(bias15m) && "short".equals(s.getDirection())) ||
                        ("bearish".equals(bias15m) && "long".equals(s.getDirection())));
                if (is15mConflict) {
                    bias15mAdj = -15;
                    log.info("{} 15M_PENALTY: {} setup vs {} 15m trend ({})", ticker, s.getDirection(), bias15m, bias15mAdj);
                }

                // ── News sentiment check ──────────────────────────────────────
                NewsSentiment sentiment = isC ? NewsSentiment.NONE : news.getSentiment(ticker);
                String stratType = intradayStratType;
                int newsAdj = sentiment.confidenceDelta(s.getDirection(), stratType);
                if (newsAdj != 0) {
                    log.info("{} news adj={} score={} dir={}", ticker, newsAdj, sentiment.netScore(), s.getDirection());
                }

                // ── News-aligned TP extension: widen TP to 3:1 ──────────────────
                // Skip extension if ticker has explicit tpRrRatio override (e.g. JPM=1.0)
                boolean hasTpOverride = profile.getTpRrRatio() != null;
                if (!isC && sentiment.isAligned(s.getDirection()) && !hasTpOverride) {
                    double risk = Math.abs(s.getEntry() - s.getStopLoss());
                    double tp3x = "long".equals(s.getDirection())
                            ? Math.round((s.getEntry() + risk * 3.0) * 10000.0) / 10000.0
                            : Math.round((s.getEntry() - risk * 3.0) * 10000.0) / 10000.0;
                    s = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(tp3x)
                            .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                            .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                            .build();
                    log.info("{} news-aligned: TP extended to 3:1 tp={}", ticker, tp3x);
                }

                // ── Fractal Anchor: 15m SQUEEZE → scalp-only (1:1 TP hard cap) ──
                // When the 15m timeframe is coiling (BB inside Keltner), there is no
                // established direction. Any RS extension or news-aligned TP widening
                // above 1:1 is premature — the squeeze hasn't resolved yet.
                // Hard geometric cap; no confidence penalty (trade still fires, tighter).
                boolean m15Squeeze = !isC && !bars15Ref.isEmpty() && regimeDetector.detectSqueeze(bars15Ref);
                if (m15Squeeze) {
                    double fa_entry = s.getEntry();
                    double fa_risk  = Math.abs(s.getStopLoss() - fa_entry);
                    double fa_oneR  = "long".equals(s.getDirection()) ? fa_entry + fa_risk : fa_entry - fa_risk;
                    fa_oneR = Math.round(fa_oneR * 10000.0) / 10000.0;
                    if (Math.abs(s.getTakeProfit() - fa_entry) > fa_risk + 0.0001) {
                        s = TradeSetup.builder()
                                .ticker(s.getTicker()).direction(s.getDirection())
                                .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(fa_oneR)
                                .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                                .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                                .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                                .build();
                        log.info("{} FRACTAL_ANCHOR: 15m squeeze → 1:1 TP cap tp={}", ticker, fa_oneR);
                    }
                }

                // ── Market context (SPY RS + VIX regime) ─────────────────────
                // SPY relative strength: if the stock is strongly outperforming
                // SPY while we try to SHORT (or underperforming while going LONG),
                // reduce confidence. VIX regime check flags when strategy type
                // (VWAP/ORB) is a poor fit for current market volatility.
                MarketContext context = isC ? MarketContext.NONE : marketCtx.getContext(ticker);
                int ctxAdj = context.confidenceDelta(s.getDirection(), stratType);
                if (ctxAdj != 0) {
                    log.info("{} market ctx adj={} rs={} vix={} regime={}", ticker, ctxAdj,
                            String.format("%.2f%%", context.rsScore()*100), context.vixLevel(), context.vixRegime());
                }

                // Hard gate: choch-primary SHORT against bullish news OR positive RS.
                // Both conditions were present in every META CHOCH short loss (04/08, 04/16).
                // Alignment bonus was overriding news/RS penalties; hard gate is required.
                if (!isC && "short".equals(s.getDirection())
                        && s.getFactorBreakdown() != null
                        && s.getFactorBreakdown().startsWith("choch-primary-short")
                        && (sentiment.isBullish() || context.isRsConflicting(s.getDirection()))) {
                    log.info("{} CHOCH_SHORT_BLOCKED: bullishNews={} rsConflict={} — suppressed",
                            ticker, sentiment.isBullish(), context.isRsConflicting(s.getDirection()));
                    setTs(ticker, "idle", null, 0, "⊘ Short CHOCH blocked — bullish news or positive RS");
                    removeSetup(ticker);
                    return;
                }

                // ── Signal quality (R:R + time-of-day + loss streak) ─────────
                // The entry bar's timestamp drives both R:R and time-of-day checks.
                // Streak comes from the live adaptive suppressor's file-backed state.
                long barEpochMs   = s.getTimestamp() != null
                        ? java.time.ZoneOffset.UTC.normalized()
                                .equals(s.getTimestamp().atZone(java.time.ZoneOffset.UTC).getZone())
                                ? s.getTimestamp().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                                : s.getTimestamp().atZone(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
                        : System.currentTimeMillis();
                int streak     = isC ? 0 : adaptive.getConsecutiveLosses(ticker);
                int qualityAdj = isC ? 0 : qualityFilter.computeDelta(s, barEpochMs, streak);
                if (qualityAdj != 0) {
                    log.info("{} quality adj={} rr={} streak={}", ticker, qualityAdj,
                            String.format("%.1f", s.rrRatio()), streak);
                }

                // ── Regime failure penalty ────────────────────────────────────
                // Beyond consecutive losses: track rolling win rate over last 6
                // outcomes. Slow degradation (3 wins then 3 losses) is invisible
                // to a streak counter but caught here.
                int regimeAdj = isC ? 0 : adaptive.getRegimeDelta(ticker);
                if (regimeAdj != 0) {
                    log.info("{} regime adj={} (rolling WR below threshold)", ticker, regimeAdj);
                }

                // ── Cross-asset correlation penalty (computed now we know dir) ─
                int corrAdj = 0;
                if (corrAsset != null && !corrBias.equals("neutral")) {
                    String dir = s.getDirection();
                    boolean conflicts = ("bullish".equals(corrBias) && "short".equals(dir))
                                     || ("bearish".equals(corrBias) && "long".equals(dir));
                    boolean agrees    = ("bullish".equals(corrBias) && "long".equals(dir))
                                     || ("bearish".equals(corrBias) && "short".equals(dir));
                    if      (conflicts) { corrAdj = corrConflictPenalty; log.info("{} CORR_CONFLICT: {} bias={} vs {} setup → adj={}", ticker, corrAsset, corrBias, dir, corrAdj); }
                    else if (agrees)    { corrAdj = corrAgreementBonus;  log.info("{} CORR_AGREE:    {} bias={} with {} setup → adj={}", ticker, corrAsset, corrBias, dir, corrAdj); }
                }

                // ── Technical indicator adjustments (SMA 200 + RSI + candle patterns + volume) ──
                // These are the core improvements to filter bad entries across all tickers.
                // Applied as soft confidence deltas, NOT hard gates.
                int sma200Adj = 0, rsiAdj = 0, candleAdj = 0, volAdj = 0;
                if (!isC) {
                    double currentPrice = bars.get(bars.size() - 1).getClose();

                    // SMA 200: directional filter from daily bars
                    sma200Adj = techIndicators.sma200Delta(dailyBars, currentPrice, s.getDirection());
                    if (sma200Adj != 0) {
                        double sma200 = techIndicators.sma(dailyBars, 200);
                        log.info("{} SMA200 adj={} price=${} sma200=${} dir={}",
                                ticker, sma200Adj, String.format("%.2f", currentPrice),
                                String.format("%.2f", sma200), s.getDirection());
                    }

                    // RSI 14: momentum filter from 5m bars
                    rsiAdj = techIndicators.rsiDelta(bars, s.getDirection());
                    if (rsiAdj != 0) {
                        double rsiVal = techIndicators.rsi(bars, 14);
                        log.info("{} RSI adj={} rsi={} dir={}", ticker, rsiAdj,
                                String.format("%.1f", rsiVal), s.getDirection());
                    }

                    // Candle patterns: hammer/engulfing/pin bar at entry
                    candleAdj = techIndicators.candlePatternDelta(bars, s.getDirection());
                    if (candleAdj != 0) {
                        log.info("{} CANDLE_PATTERN adj={} dir={}", ticker, candleAdj, s.getDirection());
                    }

                    // Volume confirmation: setup bar vs 20-bar avg
                    volAdj = techIndicators.volumeDelta(bars);
                    if (volAdj != 0) {
                        log.info("{} VOLUME adj={}", ticker, volAdj);
                    }
                }

                // ── Per-ticker dead zone hard block ───────────────────────────────
                // skipHours set per-ticker in ticker-profiles.json from backtest loss data.
                // Hard skip — signal silently suppressed for this scan cycle.
                if (!isC && profile.isSkipHour(etNowIntraday.getHour())) {
                    log.info("{} SKIP_HOUR_BLOCK: ticker has this hour blocked in profile", ticker);
                    setTs(ticker, "idle", null, 0, "⊘ Blocked hour — no entry this window");
                    removeSetup(ticker);
                    return;
                }

                // ── Time-of-day soft penalty (was hard block) ─────────────────────
                // 11:xx AM and 1:xx PM ET have lower WR. Now -15 instead of hard kill.
                // Strong setups (VWAP rubber-band, high-conf SMC) can still fire at lunch.
                // Also detect late-day (after 3:30 PM ET) — route to swing channel instead.
                int deadZoneAdj = 0;
                boolean lateDay = false;
                if (!isC) {
                    java.time.LocalTime etNow = etNowIntraday;
                    int etHour = etNow.getHour();
                    if (etHour == 11 || etHour == 13) {
                        deadZoneAdj = -15;
                        log.info("{} DEAD_ZONE_PENALTY: hour={} ET adj={}", ticker, etHour, deadZoneAdj);
                    }
                    // 3:00-3:30 PM ET: hard block. Losses peak in this window — MMs
                    // position for close, spreads widen, false breakouts spike.
                    // 3:30-4:00 PM ET: keep for overnight routing (lateDay=true path below).
                    if (etHour == 15 && etNow.getMinute() < 30) {
                        log.info("{} MID_AFTERNOON_BLOCK: {}:{} ET — 3:00-3:30 intraday window blocked",
                                ticker, etHour, String.format("%02d", etNow.getMinute()));
                        setTs(ticker, "idle", null, 0, "⊘ 3-3:30pm window blocked");
                        removeSetup(ticker);
                        return;
                    }
                    // After 3:30 PM ET — route to overnight/swing channel instead of intraday
                    if (etHour >= 16 || (etHour == 15 && etNow.getMinute() >= 30)) {
                        lateDay = true;
                    }
                }

                // ── Options flow check (call/put volume + contract recommendation) ──
                OptionsFlowResult flow = OptionsFlowResult.NONE;
                OptionsRecommendation rec = OptionsRecommendation.NONE;
                int flowAdj = 0;
                if (!isC) {
                    try {
                        double currentPrice = bars.get(bars.size() - 1).getClose();
                        flow = optionsFlow.analyzeFlow(ticker, currentPrice);
                        flowAdj = flow.confidenceDelta(s.getDirection());
                        if (flowAdj != 0) {
                            log.info("{} options flow adj={} dir={} pcRatio={} unusual={}",
                                    ticker, flowAdj, flow.flowDirection(),
                                    String.format("%.2f", flow.pcRatioVol()), flow.unusualActivity());
                        }
                        // Get contract recommendation
                        rec = optionsFlow.recommendContract(ticker, s.getDirection(),
                                s.getEntry(), s.getStopLoss(), s.getTakeProfit());
                    } catch (Exception e) {
                        log.debug("{} options flow error: {}", ticker, e.getMessage());
                    }
                }

                // ── Cross-timeframe alignment bonus ──────────────────────────
                // When daily trend (htfBias), 15m trend (bias15m), and setup
                // direction all agree, this is a high-probability aligned trade.
                // Award bonus. When all three disagree, extra penalty.
                int alignmentAdj = 0;
                if (!isC) {
                    boolean dailyAligned = ("bullish".equals(htfBias) && "long".equals(s.getDirection()))
                                        || ("bearish".equals(htfBias) && "short".equals(s.getDirection()));
                    boolean m15Aligned   = ("bullish".equals(bias15m) && "long".equals(s.getDirection()))
                                        || ("bearish".equals(bias15m) && "short".equals(s.getDirection()));
                    if (dailyAligned && m15Aligned) {
                        alignmentAdj = +10; // triple alignment: daily + 15m + setup direction
                        log.info("{} TRIPLE_ALIGN: daily={} 15m={} dir={} → +10", ticker, htfBias, bias15m, s.getDirection());
                    } else if (!dailyAligned && !m15Aligned && !"neutral".equals(htfBias) && !"neutral".equals(bias15m)) {
                        alignmentAdj = -10; // triple conflict: both timeframes oppose setup
                        log.info("{} TRIPLE_CONFLICT: daily={} 15m={} dir={} → -10", ticker, htfBias, bias15m, s.getDirection());
                    }
                }

                // ── Regime-strategy alignment adjustment ─────────────────────
                // Running SMC (trend-following) in a RANGING day → -5.
                // Running KeyLevel in a RANGING day → +5. Drives strategy selection.
                int regimeStratAdj = !isC ? regimeDetector.computeStrategyAlignment(regime, stratType) : 0;
                if (regimeStratAdj != 0) {
                    log.info("{} REGIME_STRAT_ADJ: regime={} strat={} → {}", ticker, regime, stratType, regimeStratAdj);
                }

                // ── Multi-day pivot level adjustment ─────────────────────────
                // Entry near recent pivot high = resistance (LONG → -12, SHORT → +8).
                // Entry near recent pivot low  = support  (SHORT → -12, LONG → +8).
                int pivotAdj = !isC ? pivotService.computePivotAdj(dailyBars, s.getEntry(), s.getDirection()) : 0;
                if (pivotAdj != 0) {
                    log.info("{} PIVOT_ADJ: entry={} dir={} → {}", ticker,
                            String.format("%.2f", s.getEntry()), s.getDirection(), pivotAdj);
                }

                // ── Confluence veto: 3+ independent primary filters all negative ─
                // Each filter measures a different risk dimension (structure, momentum,
                // regime, correlation, pressure). When 3+ say "no" simultaneously,
                // that is a compound warning no single high base-confidence score should
                // override. Apply an additional -20 penalty on top of all other adjustments.
                int negPrimaryCount = 0;
                if (trapAdj        < 0) negPrimaryCount++;
                if (bias15mAdj     < 0) negPrimaryCount++;
                if (regimeStratAdj < 0) negPrimaryCount++;
                if (pivotAdj       < 0) negPrimaryCount++;
                if (corrAdj        < 0) negPrimaryCount++;
                if (sma200Adj      < 0) negPrimaryCount++;
                int confluenceVetoAdj = negPrimaryCount >= 4 ? -20 : negPrimaryCount == 3 ? -10 : 0;
                if (confluenceVetoAdj != 0) {
                    log.info("{} CONFLUENCE_VETO: {}/6 primary filters negative → {}",
                            ticker, negPrimaryCount, confluenceVetoAdj);
                }

                // Apply combined confidence adjustment (news + context + quality + flow + regime + correlation + RS)
                // Penalty floor: secondary filters can reduce base confidence by at most 25%.
                // e.g. base=80 → floor=60, base=70 → floor=52. Prevents "death by a thousand filters."
                // Ticker DNA confidence penalty (FINANCIAL: -20, SPECULATIVE_LOW_PRICE: -10)
                int dnaAdj = !isC ? profile.characterConfPenalty() : 0;
                if (dnaAdj != 0) log.info("{} DNA_CONF_ADJ: character={} adj={}", ticker, profile.getCharacter(), dnaAdj);

                int rawAdj   = newsAdj + ctxAdj + qualityAdj + flowAdj + regimeAdj + corrAdj + intradayRsAdj + deadZoneAdj + bias15mAdj + alignmentAdj + sma200Adj + rsiAdj + candleAdj + volAdj + regimeStratAdj + pivotAdj + trapAdj + exhaustionAdj + confluenceVetoAdj + dnaAdj;
                int penaltyFloor = (int)(s.getConfidence() * 0.75);
                int totalAdj = rawAdj; // no longer clamping at -40; floor handles it
                if (rawAdj != totalAdj) {
                    log.info("{} ADJ_CLAMPED: raw={} → clamped={} (breakdown: news{} ctx{} qual{} flow{} regime{} corr{} iRS{} 15m{} align{} sma200{} rsi{} candle{} vol{})",
                            ticker, rawAdj, totalAdj, newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj, intradayRsAdj, bias15mAdj, alignmentAdj, sma200Adj, rsiAdj, candleAdj, volAdj);
                }
                if (totalAdj != 0 || flow.hasData() || rec.hasData()) {
                    TradeSetup.Builder sb = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(s.getTakeProfit())
                            .confidence(Math.max(penaltyFloor, Math.min(100, s.getConfidence() + totalAdj)))
                            .session(s.getSession()).volatility(s.getVolatility()).atr(s.getAtr())
                            .hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom())
                            .timestamp(s.getTimestamp());
                    // Attach options flow data
                    if (flow.hasData()) {
                        sb.optionsFlowLabel(flow.label()).optionsFlowDir(flow.flowDirection())
                          .optionsMaxPain(flow.maxPainStrike());
                    }
                    // Attach contract recommendation
                    if (rec.hasData()) {
                        sb.optionsContract(rec.contractTicker()).optionsType(rec.contractType())
                          .optionsStrike(rec.strike()).optionsExpiry(rec.expirationDate())
                          .optionsPremium(rec.estimatedPremium()).optionsDelta(rec.delta())
                          .optionsIV(rec.iv()).optionsIVPct(rec.ivPercentile())
                          .optionsBreakEven(rec.breakEvenPrice())
                          .optionsProfitPer(rec.profitPerContract()).optionsLossPer(rec.lossPerContract())
                          .optionsRR(rec.optionsRR()).optionsSuggested(rec.suggestedContracts());
                        if (rec.greeksWarning() != null) {
                            sb.optionsGreeksWarning(rec.greeksWarning());
                        }
                    }
                    s = sb.build();
                }

                // ── Options flow HARD GATE ─────────────────────────────────────
                // Only block when UNUSUAL institutional activity conflicts (vol > 3× OI).
                // Normal directional disagreement already penalised by -10 conf above.
                if (flow.hasData() && flow.unusualActivity() && flow.isConflicting(s.getDirection())) {
                    log.info("{} FLOW_WARNING: unusual {} flow vs {} setup (P/C ratio {}) — informational only for parity",
                            ticker, flow.flowDirection(), s.getDirection().toUpperCase(),
                            String.format("%.2f", flow.pcRatioVol()));
                }
                {
                    setTs(ticker,"long".equals(s.getDirection())?"setup-long":"setup-short",s.getDirection(),s.getConfidence(),
                        String.format("ENTRY %s | Score %d | $%.2f",s.getDirection().toUpperCase(),s.getConfidence(),s.getEntry()));
                    // ── Dynamic quality gate (VIX-aware) ──────────────────────────
                // When VIX is elevated, the market is in fear/volatility regime.
                // Only take highest-conviction setups: raise bar by 5 pts above 25,
                // another 5 pts above 35 (crisis). Prevents marginal calls in chaos.
                int vixBoost = 0;
                boolean vixEligible = !isC && !"vwap".equals(stratType) && !"scalp".equals(stratType);
                if (vixEligible && context.vixLevel() > 25) vixBoost  = 5;
                if (vixEligible && context.vixLevel() > 35) vixBoost += 2;
                int dynamicMinConf = effectiveMinConf + vixBoost;

                // ── Attribution string (factor breakdown) ──────────────────────
                // Tells trader EXACTLY why confidence is what it is.
                // Every factor that moved conf > ±2 is shown.
                // Prepend raw SMC signals (set by SetupDetector) so they survive this rebuild.
                String adjBreakdown = buildFactorBreakdown(
                        newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj,
                        bias15mAdj, vixBoost, s.getConfidence(),
                        sma200Adj, rsiAdj, candleAdj, volAdj, regimeStratAdj, pivotAdj,
                        trapAdj, exhaustionAdj, confluenceVetoAdj);
                String smcSignals = s.getFactorBreakdown(); // raw SMC signals from SetupDetector
                String factorBreakdown = (smcSignals != null && smcSignals.startsWith("smc-"))
                        ? smcSignals + "\nadj: " + adjBreakdown
                        : adjBreakdown;

                // ── Conviction tier (suggested contract size) ─────────────────
                // Based on final adjusted confidence — scales exposure to signal quality.
                // 90+ = rare A+ setup → 3 contracts max
                // 82–89 = strong setup → 2 contracts
                // 75–81 = standard → 1 contract
                // <75   = borderline → 1 contract (minimum size, treat as paper trade)
                String convictionTier;
                int suggestedOverride;
                if      (s.getConfidence() >= 90) { convictionTier = "🔥 HIGH CONVICTION (3 contracts)";  suggestedOverride = 3; }
                else if (s.getConfidence() >= 82) { convictionTier = "✅ STRONG (2 contracts)";            suggestedOverride = 2; }
                else if (s.getConfidence() >= 75) { convictionTier = "🟡 STANDARD (1 contract)";           suggestedOverride = 1; }
                else                              { convictionTier = "⚪ BORDERLINE (1 contract — lite)";  suggestedOverride = 1; }

                // ── Risk tier classification ─────────────────────────────────
                // Guides position sizing and stop width based on final confidence.
                String riskTier;
                if (s.getConfidence() >= 86) {
                    riskTier = "🔴 AGGRESSIVE — 2-3% risk, ATR-based stop, max 3d hold";
                } else if (s.getConfidence() >= 70) {
                    riskTier = "🟡 STANDARD — 1-2% risk, 1x ATR stop, max 5d hold";
                } else {
                    riskTier = "🟢 CONSERVATIVE — 0.5% risk, 1.5x ATR stop, spreads preferred";
                }

                // Attach attribution + conviction to setup
                if (factorBreakdown != null || convictionTier != null) {
                    TradeSetup.Builder sb2 = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(s.getTakeProfit())
                            .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                            .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                            .factorBreakdown(factorBreakdown).convictionTier(convictionTier)
                            .riskTier(riskTier);
                    if (s.hasOptionsData()) {
                        sb2.optionsFlowLabel(s.getOptionsFlowLabel()).optionsFlowDir(s.getOptionsFlowDir())
                           .optionsMaxPain(s.getOptionsMaxPain())
                           .optionsContract(s.getOptionsContract()).optionsType(s.getOptionsType())
                           .optionsStrike(s.getOptionsStrike()).optionsExpiry(s.getOptionsExpiry())
                           .optionsPremium(s.getOptionsPremium()).optionsDelta(s.getOptionsDelta())
                           .optionsIV(s.getOptionsIV()).optionsIVPct(s.getOptionsIVPct())
                           .optionsBreakEven(s.getOptionsBreakEven())
                           .optionsProfitPer(s.getOptionsProfitPer()).optionsLossPer(s.getOptionsLossPer())
                           .optionsRR(s.getOptionsRR())
                           .optionsSuggested(suggestedOverride); // override with conviction-scaled count
                    }
                    s = sb2.build();
                }

                // ── Max-confidence gate (blocks over-extended signals) ────────────
                // For tickers where 85+ bucket historically underperforms 75-84 bucket
                // (reversed confidence pattern), cap signals above maxConf.
                double intradayCurrentPrice = bars.isEmpty() ? 0 : bars.get(bars.size() - 1).getClose();
                double maxIntradayEntryDrift = s.getAtr() > 0 ? s.getAtr() * 1.5 : dailyAtr * 0.5;
                boolean intradayEntryReachable = intradayCurrentPrice > 0
                        && Math.abs(intradayCurrentPrice - s.getEntry()) <= maxIntradayEntryDrift;
                boolean scalpOptionSetup = "scalp".equals(s.getVolatility());
                double setupRisk = Math.abs(s.getStopLoss() - s.getEntry());
                boolean strongFlowConflict = flow.hasData() && flow.isConflicting(s.getDirection())
                        && (("short".equals(s.getDirection()) && flow.pcRatioVol() >= 3.0)
                         || ("long".equals(s.getDirection()) && flow.pcRatioVol() <= 0.33));
                double maxPain = s.getOptionsMaxPain();
                boolean adverseMaxPainMagnet = maxPain > 0 && setupRisk > 0
                        && (("short".equals(s.getDirection())
                                && maxPain >= s.getEntry()
                                && maxPain <= s.getStopLoss())
                         || ("long".equals(s.getDirection())
                                && maxPain <= s.getEntry()
                                && maxPain >= s.getStopLoss()));

                if (s.getConfidence() > effectiveMaxConf) {
                    log.debug("{} OVEREXTENDED conf={} maxConf={} — skipping over-extended signal",
                            ticker, s.getConfidence(), effectiveMaxConf);
                } else if (s.hasOptionsData() && s.getOptionsRR() > 0 && s.getOptionsRR() < 0.8) {
                    log.info("{} OPTIONS_RR_BLOCK: optionsRR={} too weak for directional buy; stockRR={} entry={} tp={} sl={}",
                            ticker, String.format("%.2f", s.getOptionsRR()),
                            String.format("%.2f", s.rrRatio()), s.getEntry(), s.getTakeProfit(), s.getStopLoss());
                    removeSetup(ticker);
                    setTs(ticker, "idle", null, 0, "⊘ Options R:R too weak");
                    return;
                } else if (scalpOptionSetup && strongFlowConflict) {
                    log.info("{} FLOW_CONFLICT_BLOCK: {} flow ratio={} conflicts with {} scalp",
                            ticker, flow.flowDirection(), String.format("%.2f", flow.pcRatioVol()),
                            s.getDirection().toUpperCase());
                    removeSetup(ticker);
                    setTs(ticker, "idle", null, 0, "⊘ Options flow conflicts");
                    return;
                } else if (scalpOptionSetup && adverseMaxPainMagnet) {
                    log.info("{} MAX_PAIN_BLOCK: maxPain={} sits between entry={} and stop={} for {} scalp",
                            ticker, String.format("%.2f", maxPain), s.getEntry(), s.getStopLoss(),
                            s.getDirection().toUpperCase());
                    removeSetup(ticker);
                    setTs(ticker, "idle", null, 0, "⊘ Max pain magnet against setup");
                    return;
                } else if (!intradayEntryReachable) {
                    log.info("INTRADAY STALE {} {} entry={} currentPrice={} drift={} > 1.5×ATR({}), skipping",
                            ticker, s.getDirection(), s.getEntry(), intradayCurrentPrice,
                            String.format("%.2f", Math.abs(intradayCurrentPrice - s.getEntry())),
                            String.format("%.2f", s.getAtr()));
                    removeSetup(ticker); setTs(ticker, "idle", null, 0, "stale entry — price moved away");
                } else if (s.getConfidence() >= dynamicMinConf && !dedup.isDuplicate(ticker,s.getDirection(),s.getEntry())) {
                        if (dedup.isStartupQuiet()) {
                            // Startup quiet window — seed entry key only, not ticker cooldown.
                            // Prevents exact replay of the same entry/direction,
                            // but allows fresh signals to fire once the quiet window expires.
                            log.info("{} STARTUP_SEED: {} conf={} entry={} — seeded (no alert, startup quiet active)",
                                    ticker, s.getDirection().toUpperCase(), s.getConfidence(), s.getEntry());
                            dedup.markSeedOnly(ticker, s.getDirection(), s.getEntry());
                        } else {
                            // Earnings proximity check — warn but don't block
                            EarningsCalendar.EarningsCheck earningsCheck = earnings.check(ticker);
                            if (earningsCheck.isNearEarnings()) {
                                log.warn("EARNINGS WARNING {} — {} near alert {} {}",
                                        ticker, earningsCheck.label(), s.getDirection().toUpperCase(), s.getConfidence());
                            }

                            // ── Intraday TP cap (2x daily ATR) ─────────────────
                            // Prevents unrealistic TPs like TSLA $359→$386 (+5.76%)
                            // on intraday trades. Daily ATR is the max realistic
                            // single-day move; cap TP at 2x that from entry.
                            if (!lateDay && dailyAtr > 0) {
                                double maxTpDist = dailyAtr * 2.0;
                                double tpDist = Math.abs(s.getTakeProfit() - s.getEntry());
                                if (tpDist > maxTpDist) {
                                    double cappedTp = "long".equals(s.getDirection())
                                            ? s.getEntry() + maxTpDist
                                            : s.getEntry() - maxTpDist;
                                    log.info("{} TP_CAPPED: ${} → ${} (dist ${} > 2x dailyATR ${})",
                                            ticker,
                                            String.format("%.2f", s.getTakeProfit()),
                                            String.format("%.2f", cappedTp),
                                            String.format("%.2f", tpDist),
                                            String.format("%.2f", maxTpDist));
                                    s = TradeSetup.builder()
                                            .ticker(s.getTicker()).direction(s.getDirection())
                                            .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(cappedTp)
                                            .confidence(s.getConfidence()).session(s.getSession())
                                            .volatility(s.getVolatility()).atr(s.getAtr())
                                            .hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom())
                                            .timestamp(s.getTimestamp())
                                            .factorBreakdown(s.getFactorBreakdown())
                                            .convictionTier(s.getConvictionTier()).riskTier(s.getRiskTier())
                                            .optionsFlowLabel(s.getOptionsFlowLabel()).optionsFlowDir(s.getOptionsFlowDir())
                                            .optionsMaxPain(s.getOptionsMaxPain())
                                            .optionsContract(s.getOptionsContract()).optionsType(s.getOptionsType())
                                            .optionsStrike(s.getOptionsStrike()).optionsExpiry(s.getOptionsExpiry())
                                            .optionsPremium(s.getOptionsPremium()).optionsDelta(s.getOptionsDelta())
                                            .optionsIV(s.getOptionsIV()).optionsIVPct(s.getOptionsIVPct())
                                            .optionsBreakEven(s.getOptionsBreakEven())
                                            .optionsProfitPer(s.getOptionsProfitPer()).optionsLossPer(s.getOptionsLossPer())
                                            .optionsRR(s.getOptionsRR()).optionsSuggested(s.getOptionsSuggested())
                                            .optionsGreeksWarning(s.getOptionsGreeksWarning())
                                            .build();
                                }
                            }

                            if (lateDay) {
                                // After 3:30 PM ET — check overnight coiling conditions before routing.
                                // Only hold overnight when institutions are visibly loading into the close.
                                // Without those signals, routing as a standard swing is safer.
                                List<OHLCV> spyBarsForOvernight = List.of();
                                try {
                                    List<OHLCV> sp = client.getBars("SPY", "5m", 50);
                                    if (sp != null) spyBarsForOvernight = pressureService.getSessionBars(sp);
                                } catch (Exception _e) {
                                    log.debug("{} SPY overnight RS fetch error: {}", ticker, _e.getMessage());
                                }
                                boolean hasCat = sentiment.isAligned(s.getDirection());
                                OvernightMomentumService.HoldSignal overnightSignal =
                                        overnightService.evaluate(ticker, sessionBars5m, s.getDirection(),
                                                hasCat, spyBarsForOvernight);

                                if (overnightSignal.shouldHold()) {
                                    // Tighten SL to day's midpoint (only tighten, never widen)
                                    double dayMid = overnightSignal.suggestedSl();
                                    if (dayMid > 0) {
                                        boolean tighten = ("long".equals(s.getDirection()) && dayMid > s.getStopLoss())
                                                       || ("short".equals(s.getDirection()) && dayMid < s.getStopLoss());
                                        if (tighten) {
                                            s = TradeSetup.builder()
                                                    .ticker(s.getTicker()).direction(s.getDirection())
                                                    .entry(s.getEntry()).stopLoss(dayMid).takeProfit(s.getTakeProfit())
                                                    .confidence(s.getConfidence()).session(s.getSession())
                                                    .volatility(s.getVolatility()).atr(s.getAtr())
                                                    .hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                                                    .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom())
                                                    .timestamp(s.getTimestamp()).build();
                                        }
                                    }
                                    if (!swingMode.isEffectiveSkip(rootSkip)) {
                                        log.info("OVERNIGHT_HOLD {} {} conf={} gapScore={} reason=[{}] sl={}",
                                                ticker, s.getDirection().toUpperCase(), s.getConfidence(),
                                                overnightSignal.gapScore(), overnightSignal.reason(), s.getStopLoss());
                                        discord.sendOvernightHoldAlert(s, overnightSignal.gapScore(), overnightSignal.reason());
                                        tracker.recordStrategySignal("overnight_hold", s.getConfidence());
                                    }
                                } else {
                                    // No coiling signals → plain late-day swing reroute
                                    if (!swingMode.isEffectiveSkip(rootSkip)) {
                                        log.info("LATE_DAY SWING REROUTE {} {} conf={} entry={} (overnight score={} — no hold)",
                                                ticker, s.getDirection().toUpperCase(), s.getConfidence(),
                                                s.getEntry(), overnightSignal.gapScore());
                                        discord.sendSwingAlert(s);
                                        tracker.recordStrategySignal("swing_lateday", s.getConfidence());
                                    }
                                }
                            } else {
                                // ── Mode gate: scalp alerts suppressed if scalpMode.skip, intraday if intradayMode.skip ──
                                boolean isScalpSetup = "scalp".equals(s.getVolatility());
                                boolean modeAllowed  = isScalpSetup
                                        ? !scalpMode.isEffectiveSkip(rootSkip)
                                        : !intradayMode.isEffectiveSkip(rootSkip);
                                if (!modeAllowed) {
                                    log.info("ALERT SUPPRESSED {} {} conf={} — {} mode disabled for this ticker",
                                            ticker, s.getDirection().toUpperCase(), s.getConfidence(),
                                            isScalpSetup ? "scalp" : "intraday");
                                } else {
                                    if (!s.hasOptionsData()) {
                                        log.info("ALERT OPTIONS_UNRESOLVED {} {} conf={} — recording parity signal; Alpaca may skip order placement",
                                                ticker, s.getDirection().toUpperCase(), s.getConfidence());
                                    }
                                    log.info("INTRADAY ALERT {} {} conf={} entry={} adj=news{}/ctx{}/qual{}/flow{}/regime{}/corr{}/align{}/sma200{}/rsi{}/candle{}/vol{} vixBoost={} dynamicMin={}",
                                            ticker, s.getDirection().toUpperCase(), s.getConfidence(), s.getEntry(),
                                            newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj, alignmentAdj, sma200Adj, rsiAdj, candleAdj, volAdj, vixBoost, dynamicMinConf);
                                    discord.sendSetupAlert(s, sentiment, context, earningsCheck);
                                    liveLog.recordTrade(s, stratType);
                                    tracker.recordStrategySignal(stratType, s.getConfidence());
                                    // ── Auto-trade via Alpaca (if enabled) ──────────
                                    // Two gates: R:R must be ≥ alpacaMinRR AND confidence must be ≥ dynamicMinConf.
                                    // dynamicMinConf = effectiveMinConf + vixBoost — same threshold backtest uses,
                                    // so live auto-executes exactly the same signals backtest would trade.
                                    if (alpaca.isEnabled()) {
                                        double rr = s.rrRatio();
                                        int    liveConf = s.getConfidence();
                                        if (rr < config.getAlpacaMinRR()) {
                                            log.info("ALPACA SKIPPED {} {} rr={} < minRR={} — alert only",
                                                    ticker, s.getDirection().toUpperCase(),
                                                    String.format("%.2f", rr), config.getAlpacaMinRR());
                                        } else if (liveConf < dynamicMinConf) {
                                            log.info("ALPACA SKIPPED {} {} conf={} < dynamicMinConf={} — alert only",
                                                    ticker, s.getDirection().toUpperCase(),
                                                    liveConf, dynamicMinConf);
                                        } else if (!ticker.startsWith("X:") && !liquidityMap.isNearLevel(ticker, s.getEntry(), dailyAtr)) {
                                            log.info("ALPACA SKIPPED {} {} entry={} — not near a liquidity level (location gate)",
                                                    ticker, s.getDirection().toUpperCase(),
                                                    String.format("%.2f", s.getEntry()));
                                        } else if (!ticker.startsWith("X:") && !liquidityMap.isLevelFresh(ticker, s.getEntry(), dailyAtr)) {
                                            log.info("ALPACA SKIPPED {} {} entry={} — level already tested 2× this session (third-push filter)",
                                                    ticker, s.getDirection().toUpperCase(),
                                                    String.format("%.2f", s.getEntry()));
                                        } else {
                                            String orderId = alpaca.placeOrder(s);
                                            if (orderId != null) {
                                                log.info("ALPACA ORDER {} {} orderId={}", ticker, s.getDirection(), orderId);
                                            }
                                        }
                                    }
                                }
                            }
                            dedup.markSent(ticker, s.getDirection(), s.getEntry());
                        }
                    } else if (s.getConfidence() < effectiveMinConf) {
                        // Warn loudly when a strong base setup gets filtered — helps catch over-filtering
                        int baseConf = setups.get(0).getConfidence(); // raw score before any adjustments
                        if (baseConf >= 85) {
                            log.warn("SUPPRESSED_STRONG {} {} baseConf={} finalConf={} min={} | breakdown: news{} ctx{} qual{} flow{} regime{} corr{} align{} (rawAdj={} clampedAdj={})",
                                    ticker, s.getDirection().toUpperCase(), baseConf, s.getConfidence(), effectiveMinConf,
                                    newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj, alignmentAdj, rawAdj, totalAdj);
                        } else {
                            log.debug("{} LOW_CONF conf={} min={} adj=news{}/ctx{}/qual{}/flow{}/regime{}/corr{}/align{}",
                                    ticker, s.getConfidence(), effectiveMinConf, newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj, alignmentAdj);
                        }
                    }
                }
                tracker.recordSetup(s); updateSetup(s);
            } else {
                log.debug("{} intraday phase={}", ticker, phaseMsg);
                removeSetup(ticker); setTs(ticker,"idle",null,0,phaseMsg);
            }

            // ── Hourly consolidation swing alert (hourly + daily bars → swing channel) ──
            if (!isC && !swingMode.isEffectiveSkip(rootSkip)
                    && hourlyBars != null && hourlyBars.size() >= 50
                    && dailyBars != null && dailyBars.size() >= 20) {
                try {
                    List<TradeSetup> swingSetups = swingDetector.detect(hourlyBars, dailyBars, ticker, dailyAtr);
                    if (!swingSetups.isEmpty()) {
                        TradeSetup sw = swingSetups.get(0);
                        String swKey = "hswing_" + ticker;
                        if (sw.getConfidence() >= effectiveMinConf
                                && !dedup.isDuplicate(swKey, sw.getDirection(), sw.getEntry(), 72 * 60)) {
                            log.info("HOURLY_SWING ALERT {} {} conf={} entry={}", ticker,
                                    sw.getDirection().toUpperCase(), sw.getConfidence(), sw.getEntry());
                            discord.sendSwingAlert(sw);
                            tracker.recordStrategySignal("swing_hourly", sw.getConfidence());
                            dedup.markSent(swKey, sw.getDirection(), sw.getEntry());
                        }
                    }
                } catch (Exception e) { log.debug("{} hourly swing scan error: {}", ticker, e.getMessage()); }
            }

            // ── Swing alert (daily bars → swing Discord channel) ────────────
            if (!isC && !swingMode.isEffectiveSkip(rootSkip) && dailyBars!=null && dailyBars.size()>=30) {
                try {
                    SetupDetector.DetectResult sr=setupDetector.detectSetups(dailyBars,htfBias,ticker,false,dailyAtr,true);
                    if (!sr.setups().isEmpty()) {
                        TradeSetup sw=sr.setups().get(0);
                        String swKey="swing_"+ticker;
                        // Staleness guard: current price must be within 1.5×daily ATR of the entry zone.
                        // Daily setups detected from historical bars may have entry zones that are no longer
                        // reachable (e.g. NVDA SHORT entry $190 when price is already at $178). Skip those.
                        double swingCurrentPrice = bars.isEmpty() ? 0 : bars.get(bars.size()-1).getClose();
                        double maxEntryDrift = dailyAtr * 1.5;
                        boolean entryReachable = swingCurrentPrice > 0
                                && Math.abs(swingCurrentPrice - sw.getEntry()) <= maxEntryDrift;
                        if (!entryReachable) {
                            log.info("SWING STALE {} {} entry={} currentPrice={} drift={} > 1.5×ATR({}), skipping",
                                    ticker, sw.getDirection(), sw.getEntry(), swingCurrentPrice,
                                    String.format("%.2f", Math.abs(swingCurrentPrice - sw.getEntry())),
                                    String.format("%.2f", dailyAtr));
                        } else if (sw.getConfidence()>=effectiveMinConf
                                && !dedup.isDuplicate(swKey,sw.getDirection(),sw.getEntry(),72*60)) {
                            log.info("SWING ALERT {} {} conf={} entry={}", ticker, sw.getDirection().toUpperCase(), sw.getConfidence(), sw.getEntry());
                            discord.sendSwingAlert(sw);
                            tracker.recordStrategySignal("swing_daily", sw.getConfidence());
                            dedup.markSent(swKey,sw.getDirection(),sw.getEntry());
                        }
                    }
                } catch (Exception e) { log.debug("{} swing scan error: {}",ticker,e.getMessage()); }
            }

            // ── Range detection (neutral/spreads → swing channel) ─────────────
            // Only run when no directional intraday setup was found — range and
            // directional signals are mutually exclusive for the same ticker.
            if (!isC && !swingMode.isEffectiveSkip(rootSkip) && setups.isEmpty() && dailyBars != null && dailyBars.size() >= 20 && bars.size() >= 50) {
                try {
                    List<TradeSetup> rangeSetups = rangeDetector.detect(bars, dailyBars, ticker, dailyAtr);
                    if (!rangeSetups.isEmpty()) {
                        TradeSetup rng = rangeSetups.get(0);
                        String rngKey = "range_" + ticker;
                        if (rng.getConfidence() >= 60
                                && !dedup.isDuplicate(rngKey, "range", rng.getEntry(), 24 * 60)) {
                            log.info("RANGE ALERT {} conf={} band={}-{}", ticker, rng.getConfidence(),
                                    String.format("%.2f", rng.getFvgBottom()), String.format("%.2f", rng.getFvgTop()));
                            discord.sendSwingAlert(rng); // route to swing channel (spread plays)
                            tracker.recordStrategySignal("range", rng.getConfidence());
                            dedup.markSent(rngKey, "range", rng.getEntry());
                        }
                    }
                } catch (Exception e) { log.debug("{} range scan error: {}", ticker, e.getMessage()); }
            }


            // ── Pre-close overnight gap prediction (3:30–3:55 PM ET) ─────────────
            // Scan near close for stocks coiling for a next-morning gap.
            // Enter near today's close; exit tomorrow when price jumps $20-30.
            if (config.isGapOvernightEnabled() && !isC && dailyAtr > 0 && sessionBars5m.size() >= 20) {
                try {
                    java.time.LocalTime etNowGap = etNowIntraday;
                    boolean isPreCloseWindow = !etNowGap.isBefore(java.time.LocalTime.of(15, 30))
                            && etNowGap.isBefore(java.time.LocalTime.of(16, 0));
                    if (isPreCloseWindow && !liveLog.hasOvernightFiredToday(ticker)) {
                        List<OHLCV> spyBarsForGap = java.util.List.of();
                        try {
                            List<OHLCV> sp = client.getBars("SPY", "5m", 80);
                            if (sp != null) spyBarsForGap = pressureService.getSessionBars(sp);
                        } catch (Exception e) { log.debug("{} SPY gap fetch: {}", ticker, e.getMessage()); }
                        final List<OHLCV> spyGapFinal = spyBarsForGap;

                        for (String tryDir : java.util.List.of("long", "short")) {
                            boolean hasCat = overnightService.evaluate(ticker, sessionBars5m, tryDir, false, spyGapFinal).gapScore() > 0
                                    && news.getSentiment(ticker).isAligned(tryDir);
                            OvernightMomentumService.HoldSignal gapSig =
                                    overnightService.evaluate(ticker, sessionBars5m, tryDir, hasCat, spyGapFinal);
                            if (!gapSig.shouldHold()) continue;

                            // Minimum score 70: requires at least 2 real confirmations beyond catalyst alone.
                            // Score 60 = catalyst + close extreme only — not enough for an overnight hold.
                            if (gapSig.gapScore() < 70) {
                                log.debug("GAP_OVERNIGHT {} {} skipped — score {} < 70 minimum", ticker, tryDir, gapSig.gapScore());
                                continue;
                            }

                            double gapEntry = sessionBars5m.get(sessionBars5m.size() - 1).getClose();
                            double gapSl    = gapSig.suggestedSl() > 0 ? gapSig.suggestedSl()
                                    : ("long".equals(tryDir) ? gapEntry - dailyAtr : gapEntry + dailyAtr);
                            double gapTp    = "long".equals(tryDir)
                                    ? gapEntry + 2.0 * dailyAtr
                                    : gapEntry - 2.0 * dailyAtr;
                            double gapRisk  = Math.abs(gapEntry - gapSl);
                            if (gapRisk <= 0) continue;

                            String gapKey = "gap_overnight_" + ticker;
                            if (!dedup.isDuplicate(gapKey, tryDir, gapEntry, 60)) {
                                // Stamp dedup immediately — before options lookup — so a failed options
                                // call doesn't let the same ticker re-fire on the next scan cycle.
                                dedup.markSent(gapKey, tryDir, gapEntry);

                                log.info("GAP_OVERNIGHT {} {} score={} reason=[{}] entry={} sl={} tp={}",
                                        ticker, tryDir.toUpperCase(), gapSig.gapScore(), gapSig.reason(),
                                        String.format("%.2f", gapEntry), String.format("%.2f", gapSl),
                                        String.format("%.2f", gapTp));
                                TradeSetup.Builder gapBuilder = TradeSetup.builder()
                                        .ticker(ticker).direction(tryDir)
                                        .entry(gapEntry).stopLoss(gapSl).takeProfit(gapTp)
                                        .confidence(gapSig.gapScore())
                                        .session("NYSE").volatility("gap").atr(dailyAtr)
                                        .factorBreakdown("Overnight gap predict [" + gapSig.reason() + "] score=" + gapSig.gapScore());
                                try {
                                    OptionsRecommendation gapRec = optionsFlow.recommendContract(
                                            ticker, tryDir, gapEntry, gapSl, gapTp);
                                    if (gapRec.hasData()) {
                                        gapBuilder.optionsContract(gapRec.contractTicker())
                                                .optionsType(gapRec.contractType())
                                                .optionsStrike(gapRec.strike())
                                                .optionsExpiry(gapRec.expirationDate())
                                                .optionsPremium(gapRec.estimatedPremium())
                                                .optionsDelta(gapRec.delta())
                                                .optionsIV(gapRec.iv())
                                                .optionsIVPct(gapRec.ivPercentile())
                                                .optionsBreakEven(gapRec.breakEvenPrice())
                                                .optionsProfitPer(gapRec.profitPerContract())
                                                .optionsLossPer(gapRec.lossPerContract())
                                                .optionsRR(gapRec.optionsRR())
                                                .optionsSuggested(1);
                                    }
                                } catch (Exception e) { log.debug("{} gap overnight options: {}", ticker, e.getMessage()); }

                                TradeSetup gapNightSetup = gapBuilder.build();
                                // Always record + track — ensures hasOvernightFiredToday() blocks re-fires
                                // on service restart even when options data is unavailable.
                                liveLog.recordTrade(gapNightSetup, "gap_overnight_" + tryDir);
                                tracker.recordStrategySignal("gap_overnight", gapSig.gapScore());
                                if (gapNightSetup.hasOptionsData()) {
                                    discord.sendOvernightHoldAlert(gapNightSetup, gapSig.gapScore(), gapSig.reason());
                                    if (alpaca.isEnabled()) alpaca.placeOrder(gapNightSetup);
                                }
                            }
                            break; // only one direction per ticker per scan
                        }
                    }

                } catch (Exception e) { log.debug("{} gap scan error: {}", ticker, e.getMessage()); }
            }

            // ── Power Earnings Gap (PEG) scan (9:30–10:00 AM ET) ──────────────
            // Detect gap ≥3% on 4x volume, near 52-week high, with closing strength.
            // Fires once per day at the open if earnings drove a big gap.
            if (!isC && dailyAtr > 0 && sessionBars5m.size() >= 3
                    && dailyBars != null && dailyBars.size() >= 22) {
                try {
                    java.time.LocalTime etNowPeg = etNowIntraday;
                    boolean isPegWindow = !etNowPeg.isBefore(java.time.LocalTime.of(9, 30))
                            && etNowPeg.isBefore(java.time.LocalTime.of(10, 1));
                    if (isPegWindow) {
                        double pegPrevClose = pressureService.getPrevSessionClose(bars);
                        PowerEarningsGapDetector.PEGSignal peg =
                                pegDetector.detect(sessionBars5m, dailyBars, pegPrevClose, dailyAtr);
                        if (peg.detected()) {
                            String pegKey = "peg_" + ticker;
                            if (!dedup.isDuplicate(pegKey, peg.direction(), peg.entry(), 60)) {
                                log.info("PEG_DETECTED {} {} gap={}% vol={}x score={} entry={} sl={} tp={}",
                                        ticker, peg.direction().toUpperCase(),
                                        String.format("%.1f", peg.gapPct() * 100),
                                        String.format("%.1f", peg.volumeRatio()),
                                        peg.confidence(),
                                        String.format("%.2f", peg.entry()),
                                        String.format("%.2f", peg.stopLoss()),
                                        String.format("%.2f", peg.takeProfit()));
                                TradeSetup.Builder pegBuilder = TradeSetup.builder()
                                        .ticker(ticker).direction(peg.direction())
                                        .entry(peg.entry()).stopLoss(peg.stopLoss()).takeProfit(peg.takeProfit())
                                        .confidence(peg.confidence())
                                        .session("NYSE").volatility("gap").atr(dailyAtr)
                                        .factorBreakdown(peg.note());
                                try {
                                    OptionsRecommendation pegRec = optionsFlow.recommendContract(
                                            ticker, peg.direction(), peg.entry(), peg.stopLoss(), peg.takeProfit());
                                    if (pegRec.hasData()) {
                                        pegBuilder.optionsContract(pegRec.contractTicker())
                                                .optionsType(pegRec.contractType())
                                                .optionsStrike(pegRec.strike())
                                                .optionsExpiry(pegRec.expirationDate())
                                                .optionsPremium(pegRec.estimatedPremium())
                                                .optionsDelta(pegRec.delta())
                                                .optionsIV(pegRec.iv()).optionsIVPct(pegRec.ivPercentile())
                                                .optionsBreakEven(pegRec.breakEvenPrice())
                                                .optionsProfitPer(pegRec.profitPerContract())
                                                .optionsLossPer(pegRec.lossPerContract())
                                                .optionsRR(pegRec.optionsRR()).optionsSuggested(1);
                                    }
                                } catch (Exception e) { log.debug("{} PEG options rec error: {}", ticker, e.getMessage()); }
                                TradeSetup pegSetup = pegBuilder.build();
                                discord.sendOvernightHoldAlert(pegSetup, peg.confidence(), peg.note());
                                liveLog.recordTrade(pegSetup, "peg");
                                tracker.recordStrategySignal("peg", peg.confidence());
                                dedup.markSent(pegKey, peg.direction(), peg.entry());
                                if (alpaca.isEnabled()) alpaca.placeOrder(pegSetup);
                            }
                        }
                    }
                } catch (Exception e) { log.debug("{} PEG scan error: {}", ticker, e.getMessage()); }
            }

        } catch (Exception e) { log.error("Error scanning {}: {}",ticker,e.getMessage()); setTs(ticker,"idle",null,0,"Error: "+e.getMessage()); }
    }

    public void scanAll(List<String> tickers) {
        for (String t:tickers) { scanTicker(t); try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();return;} }
    }

    private void setTs(String ticker,String status,String dir,int conf,String msg) {
        state.updateTicker(TickerStatus.builder().ticker(ticker).status(status).direction(dir).confidence(conf).phaseMsg(msg).build());
    }
    private void updateSetup(TradeSetup s) {
        List<Map<String,Object>> cur=new ArrayList<>(state.getSetups());
        cur.removeIf(x->s.getTicker().equals(x.get("ticker")));
        double rr=Math.abs(s.getStopLoss()-s.getEntry())>0?Math.round(Math.abs(s.getTakeProfit()-s.getEntry())/Math.abs(s.getStopLoss()-s.getEntry())*10)/10.0:0;
        String reasons = isCrypto(s.getTicker())             ? "Momentum+Volume"
                       : "scalp".equals(s.getVolatility())    ? "Bollinger Bounce/Break"
                       : "normal".equals(s.getVolatility())   ? "VWAP Reversion"
                       : "high".equals(s.getVolatility())     ? "ORB Breakout"
                       : "keylevel".equals(s.getVolatility()) ? "Key Level Rejection"
                       : "Sweep+Disp+FVG+Retest";
        Map<String,Object> m=new LinkedHashMap<>(s.toMap()); m.put("rr",rr); m.put("reasons",reasons);
        cur.add(m); if (cur.size()>20) cur=cur.subList(cur.size()-20,cur.size()); state.setSetups(cur);
    }
    private void removeSetup(String t) { List<Map<String,Object>> c=new ArrayList<>(state.getSetups()); c.removeIf(x->t.equals(x.get("ticker"))); state.setSetups(c); }

    /**
     * Build a compact factor attribution string for the Discord alert.
     * Only shows factors that moved confidence by ±2 or more — keeps it readable.
     * Example: "news +8 | RS -5 | regime -15 | vix gate +5"
     */
    private String buildFactorBreakdown(int newsAdj, int ctxAdj, int qualityAdj,
                                         int flowAdj, int regimeAdj, int corrAdj,
                                         int bias15mAdj, int vixBoost, int finalConf,
                                         int sma200Adj, int rsiAdj, int candleAdj, int volAdj,
                                         int regimeStratAdj, int pivotAdj,
                                         int trapAdj, int exhaustionAdj, int confluenceVetoAdj) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (Math.abs(newsAdj)         >= 2) parts.add("news "        + (newsAdj         > 0 ? "+" : "") + newsAdj);
        if (Math.abs(ctxAdj)          >= 2) parts.add("RS/VIX "      + (ctxAdj          > 0 ? "+" : "") + ctxAdj);
        if (Math.abs(qualityAdj)      >= 2) parts.add("quality "     + (qualityAdj      > 0 ? "+" : "") + qualityAdj);
        if (Math.abs(flowAdj)         >= 2) parts.add("flow "        + (flowAdj         > 0 ? "+" : "") + flowAdj);
        if (Math.abs(regimeAdj)       >= 2) parts.add("regime "      + (regimeAdj       > 0 ? "+" : "") + regimeAdj);
        if (Math.abs(corrAdj)         >= 2) parts.add("corr "        + (corrAdj         > 0 ? "+" : "") + corrAdj);
        if (Math.abs(bias15mAdj)      >= 2) parts.add("15m "         + (bias15mAdj      > 0 ? "+" : "") + bias15mAdj);
        if (Math.abs(sma200Adj)       >= 2) parts.add("SMA200 "      + (sma200Adj       > 0 ? "+" : "") + sma200Adj);
        if (Math.abs(rsiAdj)          >= 2) parts.add("RSI "         + (rsiAdj          > 0 ? "+" : "") + rsiAdj);
        if (Math.abs(candleAdj)       >= 2) parts.add("candle "      + (candleAdj       > 0 ? "+" : "") + candleAdj);
        if (Math.abs(volAdj)          >= 2) parts.add("vol "         + (volAdj          > 0 ? "+" : "") + volAdj);
        if (Math.abs(regimeStratAdj)  >= 2) parts.add("regimeStrat " + (regimeStratAdj  > 0 ? "+" : "") + regimeStratAdj);
        if (Math.abs(pivotAdj)        >= 2) parts.add("pivot "       + (pivotAdj        > 0 ? "+" : "") + pivotAdj);
        if (Math.abs(trapAdj)           >= 2) parts.add("trap "        + (trapAdj           > 0 ? "+" : "") + trapAdj);
        if (Math.abs(exhaustionAdj)     >= 2) parts.add("exhaust "     + (exhaustionAdj     > 0 ? "+" : "") + exhaustionAdj);
        if (Math.abs(confluenceVetoAdj) >= 2) parts.add("confluence "  + (confluenceVetoAdj > 0 ? "+" : "") + confluenceVetoAdj);
        if (vixBoost                    >= 5) parts.add("VIX gate +" + vixBoost + " to min");
        if (parts.isEmpty()) return "Base score — no adjustments";
        return String.join(" | ", parts) + " → **" + finalConf + "**";
    }
}
