package com.smcscanner.strategy;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class CryptoStrategyService {
    private static final int    WINDOW   = 10;
    private static final double VOL_MULT = 1.5;

    private final AtrCalculator  atr;
    private final ScoringService scoring;
    private final SessionFilter  session;
    private final ScannerConfig  config;

    public CryptoStrategyService(AtrCalculator atr, ScoringService scoring, SessionFilter session, ScannerConfig config) {
        this.atr=atr; this.scoring=scoring; this.session=session; this.config=config;
    }

    public List<TradeSetup> detectCryptoSetup(List<OHLCV> bars, String ticker) {
        List<TradeSetup> setups=new ArrayList<>();
        if (bars==null||bars.size()<WINDOW+5) return setups;
        double[] atrArr=atr.computeAtr(bars,14);
        double curAtr=lastNz(atrArr), lastClose=bars.get(bars.size()-1).getClose();
        curAtr=Math.max(curAtr,lastClose*0.002);
        int n=bars.size();
        double wHigh=Double.MIN_VALUE, wLow=Double.MAX_VALUE;
        for (int i=n-WINDOW-1;i<n-1;i++) { wHigh=Math.max(wHigh,bars.get(i).getHigh()); wLow=Math.min(wLow,bars.get(i).getLow()); }
        OHLCV last=bars.get(n-1);
        double avgVol=bars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        if (last.getVolume()<avgVol*VOL_MULT) return setups;
        String dir=null;
        if (last.getClose()>wHigh&&last.getClose()>last.getOpen()) dir="long";
        else if (last.getClose()<wLow&&last.getClose()<last.getOpen()) dir="short";
        if (dir==null) return setups;
        double entry=r4(lastClose), sl,tp;
        if ("long".equals(dir)) { sl=r4(entry-curAtr); tp=r4(entry+curAtr*3); }
        else                    { sl=r4(entry+curAtr); tp=r4(entry-curAtr*3); }
        double atrPct=lastClose>0?curAtr/lastClose*100:0;
        setups.add(TradeSetup.builder().ticker(ticker).direction(dir).entry(entry).stopLoss(sl).takeProfit(tp)
            .confidence(scoring.scoreSetup(false,true,false,false,false,true))
            .session(session.cryptoSessionName()).volatility(atrPct<0.5?"low":(atrPct<1.5?"medium":"high"))
            .atr(r4(curAtr)).hasBos(false).hasChoch(false).fvgTop(0).fvgBottom(0).timestamp(LocalDateTime.now()).build());
        return setups;
    }
    private double lastNz(double[] a) { for(int i=a.length-1;i>=0;i--) if(a[i]>0) return a[i]; return 0; }
    private double r4(double v) { return Math.round(v*10000.0)/10000.0; }
}
