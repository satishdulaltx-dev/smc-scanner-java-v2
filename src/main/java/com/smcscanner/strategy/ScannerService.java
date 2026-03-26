package com.smcscanner.strategy;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.filter.AdaptiveSuppressor;
import com.smcscanner.filter.SignalQualityFilter;
import com.smcscanner.market.MarketContext;
import com.smcscanner.market.MarketContextService;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.news.NewsService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.*;
import com.smcscanner.options.OptionsFlowAnalyzer;
import com.smcscanner.options.OptionsFlowResult;
import com.smcscanner.options.OptionsRecommendation;
import com.smcscanner.state.SharedState;
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
    private final SharedState            state;
    private final AtrCalculator          atrCalc;
    private final VwapStrategyDetector     vwap;
    private final BreakoutStrategyDetector breakout;
    private final KeyLevelStrategyDetector keyLevel;
    private final NewsService              news;
    private final MarketContextService     marketCtx;
    private final SignalQualityFilter      qualityFilter;
    private final AdaptiveSuppressor       adaptive;
    private final OptionsFlowAnalyzer      optionsFlow;

    public ScannerService(ScannerConfig config, PolygonClient client, SetupDetector setupDetector,
                          CryptoStrategyService crypto, MultiTimeframeAnalyzer mtf,
                          DiscordAlertService discord, AlertDedup dedup,
                          PerformanceTracker tracker, SharedState state, AtrCalculator atrCalc,
                          VwapStrategyDetector vwap, BreakoutStrategyDetector breakout,
                          KeyLevelStrategyDetector keyLevel, NewsService news,
                          MarketContextService marketCtx, SignalQualityFilter qualityFilter,
                          AdaptiveSuppressor adaptive, OptionsFlowAnalyzer optionsFlow) {
        this.config=config; this.client=client; this.setupDetector=setupDetector; this.crypto=crypto;
        this.mtf=mtf; this.discord=discord; this.dedup=dedup; this.tracker=tracker; this.state=state;
        this.atrCalc=atrCalc; this.vwap=vwap; this.breakout=breakout; this.keyLevel=keyLevel;
        this.news=news; this.marketCtx=marketCtx; this.qualityFilter=qualityFilter; this.adaptive=adaptive;
        this.optionsFlow=optionsFlow;
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

            setTs(ticker,"scanning",null,0,"Fetching data...");
            boolean isC=isCrypto(ticker);
            List<OHLCV> bars=client.getBars(ticker,"5m",100);
            if (bars==null||bars.size()<20) { setTs(ticker,"idle",null,0,"No data"); return; }

            String htfBias="neutral";
            double dailyAtr=0.0;
            List<OHLCV> dailyBars=null;

            try { List<OHLCV> hb=client.getBars(ticker,"60m",50); if (hb!=null&&hb.size()>=10) htfBias=mtf.getHtfBias(hb); }
            catch (Exception e) { log.debug("{} HTF error: {}",ticker,e.getMessage()); }

            // ── 15m bias (pre-fetched here, used for alignment check after setup detection) ──
            String bias15m = "neutral";
            if (!isC) {
                try {
                    List<OHLCV> bars15 = client.getBars(ticker, "15m", 60);
                    if (bars15 != null && bars15.size() >= 10) bias15m = mtf.getHtfBias(bars15);
                } catch (Exception e) { log.debug("{} 15m bias error: {}", ticker, e.getMessage()); }
            }

            // ── Correlation bias override ─────────────────────────────────────
            // For tickers that are driven by a correlated asset, replace the
            // stock's own 15m bias with the correlated asset's 15m bias.
            // The 15m alignment check below then blocks conflicting directions
            // automatically — no extra code path needed.
            //
            // COIN / MARA → BTC 15m  (crypto proxies, ~90% intraday correlation)
            // AMD  / SMCI → NVDA 15m (AI/semi cluster moves together)
            //
            // Only override when the correlated asset has a clear directional bias;
            // "neutral" keeps the stock's own 15m bias so we don't over-block.
            String correlatedAsset = null;
            if (!isC) {
                if (ticker.equals("COIN") || ticker.equals("MARA"))        correlatedAsset = "X:BTCUSD";
                else if (ticker.equals("AMD") || ticker.equals("SMCI"))    correlatedAsset = "NVDA";
            }
            if (correlatedAsset != null) {
                try {
                    List<OHLCV> corrBars = client.getBars(correlatedAsset, "15m", 60);
                    if (corrBars != null && corrBars.size() >= 10) {
                        String corrBias = mtf.getHtfBias(corrBars);
                        if (!"neutral".equals(corrBias)) {
                            // Only override when correlated asset has a clear trend
                            bias15m = corrBias;
                            log.info("{} correlation override: using {} 15m bias={} instead of own bias",
                                    ticker, correlatedAsset, corrBias);
                        }
                    }
                } catch (Exception e) { log.debug("{} correlation bias error: {}", ticker, e.getMessage()); }
            }
            try {
                dailyBars=client.getBars(ticker,"1d",100); // 100 bars: enough for swing detection + ATR
                if (dailyBars!=null&&dailyBars.size()>=5) {
                    double[] da=atrCalc.computeAtr(dailyBars,Math.min(14,dailyBars.size()-1));
                    for (int i=da.length-1;i>=0;i--) { if (da[i]>0) { dailyAtr=da[i]; break; } }
                }
            } catch (Exception e) { log.debug("{} daily bars error: {}",ticker,e.getMessage()); }

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
                    setups = keyLevel.detect(bars, dailyBars, ticker, dailyAtr);
                    phaseMsg = setups.isEmpty() ? "Waiting for key level rejection..." : "";
                } else {
                    SetupDetector.DetectResult r=setupDetector.detectSetups(bars,htfBias,ticker,false,dailyAtr);
                    setups=r.setups(); phaseMsg=r.state().phaseMsg();
                }
            }
            if (!setups.isEmpty()) {
                TradeSetup s=setups.get(0);

                // ── 15m alignment check ───────────────────────────────────────
                // Block if 15m trend directly opposes the setup direction.
                // "bullish" 15m + short setup = fighting the trend → skip.
                // "bearish" 15m + long  setup = fighting the trend → skip.
                // "neutral" 15m → let it through (no strong trend to fight).
                if (!isC && (
                        ("bullish".equals(bias15m) && "short".equals(s.getDirection())) ||
                        ("bearish".equals(bias15m) && "long".equals(s.getDirection())))) {
                    log.info("{} 15M_BLOCKED: {} setup vs {} 15m trend", ticker, s.getDirection(), bias15m);
                    setTs(ticker, "idle", null, 0, "⛔ 15m trend conflict — " + bias15m + " vs " + s.getDirection());
                    return;
                }

                // ── News sentiment check ──────────────────────────────────────
                NewsSentiment sentiment = isC ? NewsSentiment.NONE : news.getSentiment(ticker);
                int newsAdj = sentiment.confidenceDelta(s.getDirection());
                if (newsAdj != 0) {
                    log.info("{} news adj={} score={} dir={}", ticker, newsAdj, sentiment.netScore(), s.getDirection());
                }

                // ── News-aligned TP extension: 1.5:1 → 3:1 ──────────────────
                // When news aligns with the trade direction, widen TP to 3:1 R:R.
                // The default from detectors is 1.5:1. Aligned news signals strong follow-through.
                if (!isC && sentiment.isAligned(s.getDirection())) {
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

                // ── Market context (SPY RS + VIX regime) ─────────────────────
                // SPY relative strength: if the stock is strongly outperforming
                // SPY while we try to SHORT (or underperforming while going LONG),
                // reduce confidence. VIX regime check flags when strategy type
                // (VWAP/ORB) is a poor fit for current market volatility.
                String stratType = profile.getStrategyType();
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

                // ── Time-of-day dead-zone block ───────────────────────────────────
                // 11:00–11:59 AM ET and 1:00–1:59 PM ET have 0% historical win rate.
                // Mid-morning and post-lunch consolidation = choppy, fake breakouts.
                // Hard block prevents wasting options premium on low-probability entries.
                if (!isC) {
                    java.time.LocalTime etNow = java.time.ZonedDateTime.now(
                            java.time.ZoneId.of("America/New_York")).toLocalTime();
                    int etHour = etNow.getHour();
                    if (etHour == 11 || etHour == 13) {
                        log.info("{} TIME_BLOCKED: hour={} ET (dead zone 0% WR)", ticker, etHour);
                        setTs(ticker, "idle", null, 0,
                                "⏸ Dead zone — no trades 11 AM or 1 PM ET (0% WR)");
                        return;
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

                // Apply combined confidence adjustment (news + market context + quality + options flow)
                int totalAdj = newsAdj + ctxAdj + qualityAdj + flowAdj;
                if (totalAdj != 0 || flow.hasData() || rec.hasData()) {
                    TradeSetup.Builder sb = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(s.getTakeProfit())
                            .confidence(Math.max(0, Math.min(100, s.getConfidence() + totalAdj)))
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
                    }
                    s = sb.build();
                }

                // ── Confidence dead-zone block (70-74) ───────────────────────────
                // 90-day backtest shows confidence 70-74 = 13.3% WR (-0.809% avg P&L)
                // across 15 decided trades — worse than random. This range is occupied by:
                //   • ORB setups that almost-but-not-quite passed confirmation checks
                //   • SMC setups where news/quality adjustments dragged a 90 down to ~72
                // Neither sub-group has meaningful edge. Hard-block saves premium waste.
                if (!isC && s.getConfidence() >= 70 && s.getConfidence() <= 74) {
                    log.info("{} CONF_DEAD_ZONE: conf={} (70-74 band = 13% WR — blocking)", ticker, s.getConfidence());
                    setTs(ticker, "idle", null, 0,
                            "⏸ Confidence 70-74 dead zone (13% WR in 90d) — insufficient edge");
                    return;
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
                    if (s.getConfidence() >= effectiveMinConf && !dedup.isDuplicate(ticker,s.getDirection(),s.getEntry())) {
                        log.info("INTRADAY ALERT {} {} conf={} entry={} adj=news{}/ctx{}/qual{}/flow{}",
                                ticker, s.getDirection().toUpperCase(), s.getConfidence(), s.getEntry(), newsAdj, ctxAdj, qualityAdj, flowAdj);
                        discord.sendSetupAlert(s, sentiment, context); dedup.markSent(ticker,s.getDirection(),s.getEntry());
                    } else if (s.getConfidence() < effectiveMinConf) {
                        log.debug("{} LOW_CONF conf={} min={} adj=news{}/ctx{}/qual{}/flow{}", ticker, s.getConfidence(), effectiveMinConf, newsAdj, ctxAdj, qualityAdj, flowAdj);
                    }
                }
                tracker.recordSetup(s); updateSetup(s);
            } else {
                log.debug("{} intraday phase={}", ticker, phaseMsg);
                removeSetup(ticker); setTs(ticker,"idle",null,0,phaseMsg);
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
                            dedup.markSent(swKey,sw.getDirection(),sw.getEntry());
                        }
                    }
                } catch (Exception e) { log.debug("{} swing scan error: {}",ticker,e.getMessage()); }
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
}
