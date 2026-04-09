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

    public ScannerService(ScannerConfig config, PolygonClient client, SetupDetector setupDetector,
                          CryptoStrategyService crypto, MultiTimeframeAnalyzer mtf,
                          DiscordAlertService discord, AlertDedup dedup,
                          PerformanceTracker tracker, LiveTradeLog liveLog, SharedState state, AtrCalculator atrCalc,
                          VwapStrategyDetector vwap, BreakoutStrategyDetector breakout,
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
                          PressureService pressureService, OvernightMomentumService overnightService) {
        this.config=config; this.client=client; this.setupDetector=setupDetector; this.crypto=crypto;
        this.mtf=mtf; this.discord=discord; this.dedup=dedup; this.tracker=tracker; this.liveLog=liveLog; this.state=state;
        this.atrCalc=atrCalc; this.vwap=vwap; this.breakout=breakout; this.keyLevel=keyLevel;
        this.vSqueeze=vSqueeze; this.vwap3d=vwap3d; this.indexDiv=indexDiv; this.gammaPin=gammaPin;
        this.news=news; this.marketCtx=marketCtx; this.qualityFilter=qualityFilter; this.adaptive=adaptive;
        this.optionsFlow=optionsFlow; this.earnings=earnings; this.swingDetector=swingDetector;
        this.rangeDetector=rangeDetector; this.alpaca=alpaca; this.techIndicators=techIndicators;
        this.gapDetector=gapDetector; this.regimeDetector=regimeDetector; this.pivotService=pivotService;
        this.pressureService=pressureService; this.overnightService=overnightService;
    }

    public boolean isCrypto(String t) { return t.startsWith("X:"); }

    public void scanTicker(String ticker) {
        try {
            // Check per-ticker profile: skip if marked
            com.smcscanner.model.TickerProfile profile = config.getTickerProfile(ticker);
            if (profile.isSkip()) {
                setTs(ticker,"idle",null,0,"⊘ "+profile.getSkipReason());
                return;
            }
            int effectiveMinConf = profile.resolveMinConfidence(config.getMinConfidence());
            int effectiveMaxConf = profile.resolveMaxConfidence(); // per-ticker upper cap (blocks over-extended 85+ signals)

            setTs(ticker,"scanning",null,0,"Fetching data...");
            boolean isC=isCrypto(ticker);
            List<OHLCV> bars=client.getBars(ticker,"5m",100);
            if (bars==null||bars.size()<20) { setTs(ticker,"idle",null,0,"No data"); return; }

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
            List<TradeSetup> setups; String phaseMsg;
            if (isC) { setups=crypto.detectCryptoSetup(bars,ticker); phaseMsg=setups.isEmpty()?"Waiting for breakout + volume spike...":""; }
            else {
                String strategyType = profile.getStrategyType();
                if ("vwap".equals(strategyType)) {
                    setups = vwap.detect(bars, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Waiting for VWAP reversion..." : "";
                } else if ("breakout".equals(strategyType)) {
                    setups = breakout.detect(bars, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Waiting for ORB breakout..." : "";
                } else if ("keylevel".equals(strategyType)) {
                    setups = keyLevel.detect(bars, dailyBars, ticker, dailyAtr, profile);
                    phaseMsg = setups.isEmpty() ? "Waiting for key level rejection..." : "";
                } else if ("vsqueeze".equals(strategyType)) {
                    setups = vSqueeze.detect(bars, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Watching for volatility squeeze release..." : "";
                } else if ("vwap3d".equals(strategyType)) {
                    List<OHLCV> multiDayBars = bars;
                    try {
                        List<OHLCV> wider = client.getBars(ticker, "5m", 300); // ~3 trading days
                        if (wider != null && wider.size() >= 30) multiDayBars = wider;
                    } catch (Exception e) { log.debug("{} 3dVWAP fetch error: {}", ticker, e.getMessage()); }
                    setups = vwap3d.detect(multiDayBars, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Watching for 3-day VWAP reversion..." : "";
                } else if ("idiv".equals(strategyType)) {
                    List<OHLCV> spyBars5m = List.of();
                    try {
                        List<OHLCV> sp = client.getBars("SPY", "5m", 100);
                        if (sp != null) spyBars5m = sp;
                    } catch (Exception e) { log.debug("{} SPY 5m fetch error: {}", ticker, e.getMessage()); }
                    setups = indexDiv.detect(bars, spyBars5m, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Watching for SPY/AAPL divergence..." : "";
                } else if ("gammapin".equals(strategyType)) {
                    setups = gammaPin.detect(bars, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Watching for gamma pin convergence..." : "";
                } else {
                    SetupDetector.DetectResult r=setupDetector.detectSetups(bars,htfBias,ticker,false,dailyAtr);
                    setups=r.setups(); phaseMsg=r.state().phaseMsg();
                }
            }

            // ── Regime-based fallback when primary strategy finds nothing ─────
            // e.g. SMC ticker in a RANGING day → try keylevel instead.
            // Only the 3 generic regimes have clear fallbacks; VOLATILE/LOW_LIQUIDITY
            // don't (LOW_LIQUIDITY is already gated above, VOLATILE trusts the profile).
            if (setups.isEmpty() && !isC) {
                String strategyType = profile.getStrategyType(); // safe re-read
                String fallbackStrat = regimeDetector.suggestStrategy(regime, strategyType);
                if (fallbackStrat != null && !fallbackStrat.equals(strategyType)) {
                    List<TradeSetup> fb = switch (fallbackStrat) {
                        case "smc" -> {
                            SetupDetector.DetectResult fr = setupDetector.detectSetups(bars, htfBias, ticker, false, dailyAtr);
                            yield fr.setups();
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

                // ── Volume pressure trap detection ────────────────────────────
                // BOS with declining bar pressure = institutional trap. Bypassed
                // for VWAP (mean-reversion) and SQUEEZE regime (volume naturally low).
                int trapAdj = !isC ? pressureService.computeTrapAdj(
                        bars, s.getDirection(), profile.getStrategyType(), regime) : 0;
                if (trapAdj != 0) {
                    log.info("{} TRAP_ADJ: dir={} strat={} regime={} → {}",
                            ticker, s.getDirection(), profile.getStrategyType(), regime, trapAdj);
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

                // ── VOLATILE regime SL widening ──────────────────────────────
                // In chaotic conditions ATR expands; a normal SL gets clipped almost
                // immediately. Widen by slExpansionFactor (1.5×) so the trade has
                // room to breathe. Pairs with VOLATILE regimeStratAdj penalty below.
                if (regime == MarketRegimeDetector.Regime.VOLATILE && !isC) {
                    double slFactor = regimeDetector.slExpansionFactor(regime);
                    double entry   = s.getEntry();
                    double slDist  = Math.abs(s.getStopLoss() - entry) * slFactor;
                    double newSl   = "long".equals(s.getDirection()) ? entry - slDist : entry + slDist;
                    newSl = Math.round(newSl * 10000.0) / 10000.0;
                    s = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(newSl).takeProfit(s.getTakeProfit())
                            .confidence(s.getConfidence()).session(s.getSession()).volatility(s.getVolatility())
                            .atr(s.getAtr()).hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom()).timestamp(s.getTimestamp())
                            .build();
                    log.info("{} VOLATILE_SL: widened {}\u00d7 newSl={}", ticker, slFactor, newSl);
                }

                // ── 15m alignment check ───────────────────────────────────────
                // Changed from hard block → soft penalty (-15 confidence).
                // Hard block was wiping valid counter-trend entries that had strong
                // conviction from other signals (volume, key level, etc).
                // BYPASS for VWAP: mean-reversion trades intentionally fight the trend.
                String stratTypeForFilter = profile.getStrategyType();
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
                String stratType = profile.getStrategyType();
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

                // ── Time-of-day soft penalty (was hard block) ─────────────────────
                // 11:xx AM and 1:xx PM ET have lower WR. Now -15 instead of hard kill.
                // Strong setups (VWAP rubber-band, high-conf SMC) can still fire at lunch.
                // Also detect late-day (after 3:30 PM ET) — route to swing channel instead.
                int deadZoneAdj = 0;
                boolean lateDay = false;
                if (!isC) {
                    java.time.LocalTime etNow = java.time.ZonedDateTime.now(
                            java.time.ZoneId.of("America/New_York")).toLocalTime();
                    int etHour = etNow.getHour();
                    if (etHour == 11 || etHour == 13) {
                        deadZoneAdj = -15;
                        log.info("{} DEAD_ZONE_PENALTY: hour={} ET adj={}", ticker, etHour, deadZoneAdj);
                    }
                    // After 3:30 PM ET — not enough time for intraday, route to swing
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
                int rawAdj   = newsAdj + ctxAdj + qualityAdj + flowAdj + regimeAdj + corrAdj + intradayRsAdj + deadZoneAdj + bias15mAdj + alignmentAdj + sma200Adj + rsiAdj + candleAdj + volAdj + regimeStratAdj + pivotAdj + trapAdj + exhaustionAdj + confluenceVetoAdj;
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
                    log.info("{} FLOW_BLOCKED: unusual {} flow vs {} setup (P/C ratio {})",
                            ticker, flow.flowDirection(), s.getDirection().toUpperCase(),
                            String.format("%.2f", flow.pcRatioVol()));
                    setTs(ticker, "idle", null, 0,
                            "⛔ Blocked — unusual institutional flow conflicts (" + flow.flowDirection() + ")");
                    removeSetup(ticker);
                } else {
                    setTs(ticker,"long".equals(s.getDirection())?"setup-long":"setup-short",s.getDirection(),s.getConfidence(),
                        String.format("ENTRY %s | Score %d | $%.2f",s.getDirection().toUpperCase(),s.getConfidence(),s.getEntry()));
                    // ── Dynamic quality gate (VIX-aware) ──────────────────────────
                // When VIX is elevated, the market is in fear/volatility regime.
                // Only take highest-conviction setups: raise bar by 5 pts above 25,
                // another 5 pts above 35 (crisis). Prevents marginal calls in chaos.
                int vixBoost = 0;
                if (!isC && context.vixLevel() > 25) vixBoost  = 5;
                if (!isC && context.vixLevel() > 35) vixBoost += 2; // was +5 — total max 7, not 10; ATR sizing already tightens SL/TP in high VIX
                int dynamicMinConf = effectiveMinConf + vixBoost;

                // ── Attribution string (factor breakdown) ──────────────────────
                // Tells trader EXACTLY why confidence is what it is.
                // Every factor that moved conf > ±2 is shown.
                String factorBreakdown = buildFactorBreakdown(
                        newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj,
                        bias15mAdj, vixBoost, s.getConfidence(),
                        sma200Adj, rsiAdj, candleAdj, volAdj, regimeStratAdj, pivotAdj,
                        trapAdj, exhaustionAdj, confluenceVetoAdj);

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
                if (s.getConfidence() > effectiveMaxConf) {
                    log.debug("{} OVEREXTENDED conf={} maxConf={} — skipping over-extended signal",
                            ticker, s.getConfidence(), effectiveMaxConf);
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
                                        overnightService.evaluate(sessionBars5m, s.getDirection(),
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
                                    log.info("OVERNIGHT_HOLD {} {} conf={} gapScore={} reason=[{}] sl={}",
                                            ticker, s.getDirection().toUpperCase(), s.getConfidence(),
                                            overnightSignal.gapScore(), overnightSignal.reason(), s.getStopLoss());
                                    discord.sendOvernightHoldAlert(s, overnightSignal.gapScore(), overnightSignal.reason());
                                    tracker.recordStrategySignal("overnight_hold", s.getConfidence());
                                } else {
                                    // No coiling signals → plain late-day swing reroute
                                    log.info("LATE_DAY SWING REROUTE {} {} conf={} entry={} (overnight score={} — no hold)",
                                            ticker, s.getDirection().toUpperCase(), s.getConfidence(),
                                            s.getEntry(), overnightSignal.gapScore());
                                    discord.sendSwingAlert(s);
                                    tracker.recordStrategySignal("swing_lateday", s.getConfidence());
                                }
                            } else {
                                // Options-only gate: suppress alert entirely if no contract found
                                if (!s.hasOptionsData()) {
                                    log.info("ALERT SUPPRESSED {} {} conf={} — no options contract available (options-only mode)",
                                            ticker, s.getDirection().toUpperCase(), s.getConfidence());
                                } else {
                                    log.info("INTRADAY ALERT {} {} conf={} entry={} adj=news{}/ctx{}/qual{}/flow{}/regime{}/corr{}/align{}/sma200{}/rsi{}/candle{}/vol{} vixBoost={} dynamicMin={}",
                                            ticker, s.getDirection().toUpperCase(), s.getConfidence(), s.getEntry(),
                                            newsAdj, ctxAdj, qualityAdj, flowAdj, regimeAdj, corrAdj, alignmentAdj, sma200Adj, rsiAdj, candleAdj, volAdj, vixBoost, dynamicMinConf);
                                    discord.sendSetupAlert(s, sentiment, context, earningsCheck);
                                    liveLog.recordTrade(s, stratType);
                                    tracker.recordStrategySignal(stratType, s.getConfidence());
                                    // ── Auto-trade via Alpaca (if enabled) ──────────
                                    if (alpaca.isEnabled()) {
                                        String orderId = alpaca.placeOrder(s);
                                        if (orderId != null) {
                                            log.info("ALPACA ORDER {} {} orderId={}", ticker, s.getDirection(), orderId);
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
            if (!isC && hourlyBars != null && hourlyBars.size() >= 50
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
            if (!isC && dailyBars!=null && dailyBars.size()>=30) {
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
            if (!isC && setups.isEmpty() && dailyBars != null && dailyBars.size() >= 20 && bars.size() >= 50) {
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
            if (!isC && dailyAtr > 0 && sessionBars5m.size() >= 20) {
                try {
                    java.time.LocalTime etNowGap = java.time.ZonedDateTime.now(
                            java.time.ZoneId.of("America/New_York")).toLocalTime();
                    boolean isPreCloseWindow = !etNowGap.isBefore(java.time.LocalTime.of(15, 30))
                            && etNowGap.isBefore(java.time.LocalTime.of(16, 0));
                    if (isPreCloseWindow) {
                        List<OHLCV> spyBarsForGap = java.util.List.of();
                        try {
                            List<OHLCV> sp = client.getBars("SPY", "5m", 80);
                            if (sp != null) spyBarsForGap = pressureService.getSessionBars(sp);
                        } catch (Exception e) { log.debug("{} SPY gap fetch: {}", ticker, e.getMessage()); }
                        final List<OHLCV> spyGapFinal = spyBarsForGap;

                        for (String tryDir : java.util.List.of("long", "short")) {
                            boolean hasCat = overnightService.evaluate(sessionBars5m, tryDir, false, spyGapFinal).gapScore() > 0
                                    && news.getSentimentForTicker(ticker).isAligned(tryDir);
                            OvernightMomentumService.HoldSignal gapSig =
                                    overnightService.evaluate(sessionBars5m, tryDir, hasCat, spyGapFinal);
                            if (!gapSig.shouldHold()) continue;

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
                                if (gapNightSetup.hasOptionsData()) {
                                    discord.sendOvernightHoldAlert(gapNightSetup, gapSig.gapScore(), gapSig.reason());
                                    liveLog.recordTrade(gapNightSetup, "gap_overnight_" + tryDir);
                                    tracker.recordStrategySignal("gap_overnight", gapSig.gapScore());
                                    dedup.markSent(gapKey, tryDir, gapEntry);
                                    if (alpaca.isEnabled()) alpaca.placeOrder(gapNightSetup);
                                }
                            }
                            break; // only one direction per ticker per scan
                        }
                    }

                } catch (Exception e) { log.debug("{} gap scan error: {}", ticker, e.getMessage()); }
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
