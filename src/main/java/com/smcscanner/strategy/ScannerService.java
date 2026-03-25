package com.smcscanner.strategy;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.news.NewsService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.*;
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

    public ScannerService(ScannerConfig config, PolygonClient client, SetupDetector setupDetector,
                          CryptoStrategyService crypto, MultiTimeframeAnalyzer mtf,
                          DiscordAlertService discord, AlertDedup dedup,
                          PerformanceTracker tracker, SharedState state, AtrCalculator atrCalc,
                          VwapStrategyDetector vwap, BreakoutStrategyDetector breakout,
                          KeyLevelStrategyDetector keyLevel, NewsService news) {
        this.config=config; this.client=client; this.setupDetector=setupDetector; this.crypto=crypto;
        this.mtf=mtf; this.discord=discord; this.dedup=dedup; this.tracker=tracker; this.state=state;
        this.atrCalc=atrCalc; this.vwap=vwap; this.breakout=breakout; this.keyLevel=keyLevel;
        this.news=news;
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

                // ── News sentiment check ──────────────────────────────────────
                // Fetch last-48h news for this ticker and adjust confidence.
                // Conflicting news (e.g. bullish news on a SHORT signal) reduces
                // confidence; aligned news boosts it slightly.
                // The adjusted setup is used for both the confidence gate and the
                // Discord embed (news headline shown in the alert).
                NewsSentiment sentiment = isC ? NewsSentiment.NONE : news.getSentiment(ticker);
                int newsAdj = sentiment.confidenceDelta(s.getDirection());
                if (newsAdj != 0) {
                    log.info("{} news sentiment: score={} adj={} dir={} headline={}",
                            ticker, sentiment.netScore(), newsAdj, s.getDirection(),
                            sentiment.headline() != null ? sentiment.headline().substring(0, Math.min(80, sentiment.headline().length())) : "");
                    s = TradeSetup.builder()
                            .ticker(s.getTicker()).direction(s.getDirection())
                            .entry(s.getEntry()).stopLoss(s.getStopLoss()).takeProfit(s.getTakeProfit())
                            .confidence(Math.max(0, Math.min(100, s.getConfidence() + newsAdj)))
                            .session(s.getSession()).volatility(s.getVolatility()).atr(s.getAtr())
                            .hasBos(s.isHasBos()).hasChoch(s.isHasChoch())
                            .fvgTop(s.getFvgTop()).fvgBottom(s.getFvgBottom())
                            .timestamp(s.getTimestamp())
                            .build();
                }

                setTs(ticker,"long".equals(s.getDirection())?"setup-long":"setup-short",s.getDirection(),s.getConfidence(),
                    String.format("ENTRY %s | Score %d | $%.2f",s.getDirection().toUpperCase(),s.getConfidence(),s.getEntry()));
                if (s.getConfidence() >= effectiveMinConf && !dedup.isDuplicate(ticker,s.getDirection(),s.getEntry())) {
                    log.info("INTRADAY ALERT {} {} conf={} entry={}", ticker, s.getDirection().toUpperCase(), s.getConfidence(), s.getEntry());
                    discord.sendSetupAlert(s, sentiment); dedup.markSent(ticker,s.getDirection(),s.getEntry());
                } else if (s.getConfidence() < effectiveMinConf) {
                    log.debug("{} intraday LOW_CONF conf={} min={} newsAdj={}", ticker, s.getConfidence(), effectiveMinConf, newsAdj);
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
                        if (sw.getConfidence()>=effectiveMinConf
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
