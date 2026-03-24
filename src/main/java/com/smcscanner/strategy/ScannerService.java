package com.smcscanner.strategy;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
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

    private final ScannerConfig         config;
    private final PolygonClient         client;
    private final SetupDetector         setupDetector;
    private final CryptoStrategyService crypto;
    private final MultiTimeframeAnalyzer mtf;
    private final DiscordAlertService   discord;
    private final AlertDedup            dedup;
    private final PerformanceTracker    tracker;
    private final SharedState           state;
    private final AtrCalculator         atrCalc;

    public ScannerService(ScannerConfig config, PolygonClient client, SetupDetector setupDetector,
                          CryptoStrategyService crypto, MultiTimeframeAnalyzer mtf,
                          DiscordAlertService discord, AlertDedup dedup,
                          PerformanceTracker tracker, SharedState state, AtrCalculator atrCalc) {
        this.config=config; this.client=client; this.setupDetector=setupDetector; this.crypto=crypto;
        this.mtf=mtf; this.discord=discord; this.dedup=dedup; this.tracker=tracker; this.state=state;
        this.atrCalc=atrCalc;
    }

    public boolean isCrypto(String t) { return t.startsWith("X:"); }

    public void scanTicker(String ticker) {
        try {
            setTs(ticker,"scanning",null,0,"Fetching data...");
            boolean isC=isCrypto(ticker);
            List<OHLCV> bars=client.getBars(ticker,"5m",100);
            if (bars==null||bars.size()<20) { setTs(ticker,"idle",null,0,"No data"); return; }
            String htfBias="neutral";
            double dailyAtr=0.0;
            try { List<OHLCV> hb=client.getBars(ticker,"60m",50); if (hb!=null&&hb.size()>=10) htfBias=mtf.getHtfBias(hb); }
            catch (Exception e) { log.debug("{} HTF error: {}",ticker,e.getMessage()); }
            try {
                List<OHLCV> db=client.getBars(ticker,"1d",20);
                if (db!=null&&db.size()>=5) {
                    double[] da=atrCalc.computeAtr(db,Math.min(14,db.size()-1));
                    for (int i=da.length-1;i>=0;i--) { if (da[i]>0) { dailyAtr=da[i]; break; } }
                }
            } catch (Exception e) { log.debug("{} daily ATR error: {}",ticker,e.getMessage()); }
            List<TradeSetup> setups; String phaseMsg;
            if (isC) { setups=crypto.detectCryptoSetup(bars,ticker); phaseMsg=setups.isEmpty()?"Waiting for breakout + volume spike...":""; }
            else {
                SetupDetector.DetectResult r=setupDetector.detectSetups(bars,htfBias,ticker,false,dailyAtr);
                setups=r.setups(); phaseMsg=r.state().phaseMsg();
            }
            if (!setups.isEmpty()) {
                TradeSetup s=setups.get(0);
                setTs(ticker,"long".equals(s.getDirection())?"setup-long":"setup-short",s.getDirection(),s.getConfidence(),
                    String.format("ENTRY %s | Score %d | $%.2f",s.getDirection().toUpperCase(),s.getConfidence(),s.getEntry()));
                if (s.getConfidence() >= config.getMinConfidence() && !dedup.isDuplicate(ticker,s.getDirection(),s.getEntry())) {
                    log.info("SETUP ALERT {} {} conf={} entry={}", ticker, s.getDirection().toUpperCase(), s.getConfidence(), s.getEntry());
                    discord.sendSetupAlert(s); dedup.markSent(ticker,s.getDirection(),s.getEntry());
                } else if (s.getConfidence() < config.getMinConfidence()) {
                    log.debug("{} setup found but LOW_CONF conf={} min={}", ticker, s.getConfidence(), config.getMinConfidence());
                }
                tracker.recordSetup(s); updateSetup(s);
            } else {
                log.debug("{} phase={}", ticker, phaseMsg);
                removeSetup(ticker); setTs(ticker,"idle",null,0,phaseMsg);
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
        Map<String,Object> m=new LinkedHashMap<>(s.toMap()); m.put("rr",rr); m.put("reasons",isCrypto(s.getTicker())?"Momentum+Volume":"Sweep+Disp+FVG+Retest");
        cur.add(m); if (cur.size()>20) cur=cur.subList(cur.size()-20,cur.size()); state.setSetups(cur);
    }
    private void removeSetup(String t) { List<Map<String,Object>> c=new ArrayList<>(state.getSetups()); c.removeIf(x->t.equals(x.get("ticker"))); state.setSetups(c); }
}
