package com.smcscanner.strategy;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.*;
import com.smcscanner.smc.StructureAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SetupDetector {
    private static final Logger log = LoggerFactory.getLogger(SetupDetector.class);
    private static final int    SWEEP_LOOKBACK = 50, FVG_WINDOW = 8, RETEST_WINDOW = 15;
    private static final double DISP_ATR_MULT  = 1.8, MIN_FVG_PCT = 0.003, MIN_VOL_MULT = 2.5;

    private final ScannerConfig config;
    private final AtrCalculator atrCalc;
    private final StructureAnalyzer sa;
    private final ScoringService scoring;
    private final SessionFilter sessionFilter;

    public SetupDetector(ScannerConfig config, AtrCalculator atrCalc, StructureAnalyzer sa,
                         ScoringService scoring, SessionFilter sessionFilter) {
        this.config=config; this.atrCalc=atrCalc; this.sa=sa; this.scoring=scoring; this.sessionFilter=sessionFilter;
    }

    public record DetectResult(List<TradeSetup> setups, SMCState state) {}

    public DetectResult detectSetups(List<OHLCV> bars, String htfBias, String ticker, boolean isCrypto) {
        SMCState state = new SMCState();
        if (bars==null||bars.size()<20) return new DetectResult(List.of(),state);
        if (isCrypto&&!sessionFilter.isInCryptoSession()) { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
        if (!isCrypto&&!sessionFilter.isInNySession())    { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }

        double[] atrArr=atrCalc.computeAtr(bars,14);
        double curAtr=lastNz(atrArr), lastClose=bars.get(bars.size()-1).getClose();
        curAtr=Math.max(curAtr,lastClose*0.002);

        if (atrCalc.atrPercentile(atrArr,-1,100)<config.getMinAtrPercentile()) { state.setPhase(SetupPhase.LOW_VOLATILITY); return new DetectResult(List.of(),state); }

        String vol=lastClose>0&&curAtr/lastClose*100<0.5?"low":(curAtr/lastClose*100<1.5?"medium":"high");
        String session=isCrypto?sessionFilter.cryptoSessionName():sessionFilter.sessionName();

        state=runStateMachine(bars,atrArr,state);
        if (!state.isComplete()) return new DetectResult(List.of(),state);

        double fvgSize=Math.abs(state.getFvgTop()-state.getFvgBottom());
        if (fvgSize/lastClose<MIN_FVG_PCT||fvgSize<curAtr*0.5) return new DetectResult(List.of(),state);

        double avgVol=bars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        double peakVol=bars.subList(Math.max(0,bars.size()-10),bars.size()).stream().mapToDouble(OHLCV::getVolume).max().orElse(0);
        if (peakVol<avgVol*MIN_VOL_MULT) return new DetectResult(List.of(),state);

        boolean[] str=detectStructure(bars);
        if (!str[0]&&!str[1]) return new DetectResult(List.of(),state);
        if ("bullish".equals(htfBias)&&"short".equals(state.getDirection())) return new DetectResult(List.of(),state);
        if ("bearish".equals(htfBias)&&"long".equals(state.getDirection()))  return new DetectResult(List.of(),state);

        int conf=scoring.scoreSetup(true,true,true,true,str[0]||str[1],peakVol>avgVol*MIN_VOL_MULT);
        if (conf<config.getMinConfidence()) return new DetectResult(List.of(),state);

        double fvgMid=(state.getFvgTop()+state.getFvgBottom())/2.0, entry=r4(fvgMid);
        double sl,tp;
        if ("long".equals(state.getDirection())) { sl=r4(entry-curAtr); tp=r4(entry+curAtr*3); }
        else                                     { sl=r4(entry+curAtr); tp=r4(entry-curAtr*3); }

        TradeSetup setup=TradeSetup.builder().ticker(ticker).direction(state.getDirection())
            .entry(entry).stopLoss(sl).takeProfit(tp).confidence(conf).session(session).volatility(vol)
            .atr(r4(curAtr)).hasBos(str[0]).hasChoch(str[1]).fvgTop(r4(state.getFvgTop())).fvgBottom(r4(state.getFvgBottom()))
            .timestamp(LocalDateTime.now()).build();
        return new DetectResult(List.of(setup),state);
    }

    private SMCState runStateMachine(List<OHLCV> bars, double[] atrArr, SMCState state) {
        int n=bars.size(), scanFrom=Math.max(0,n-SWEEP_LOOKBACK);
        double swHigh=Double.MIN_VALUE, swLow=Double.MAX_VALUE; int shIdx=-1, slIdx=-1;
        for (int i=scanFrom;i<n-5;i++) {
            if (bars.get(i).getHigh()>swHigh) { swHigh=bars.get(i).getHigh(); shIdx=i; }
            if (bars.get(i).getLow()<swLow)   { swLow=bars.get(i).getLow();   slIdx=i; }
        }
        for (int i=Math.max(scanFrom,1);i<n;i++) {
            OHLCV bar=bars.get(i);
            boolean bull=slIdx>=0&&bar.getLow()<swLow&&bar.getClose()>swLow;
            boolean bear=shIdx>=0&&bar.getHigh()>swHigh&&bar.getClose()<swHigh;
            if (!bull&&!bear) continue;
            state.setPhase(SetupPhase.SWEEP_DETECTED); state.setDirection(bull?"long":"short"); state.setSweepBar(i);
            int dBar=findDisp(bars,atrArr,i+1,state.getDirection(),n);
            if (dBar<0) { state.setPhase(SetupPhase.INVALID_NO_DISP); continue; }
            state.setPhase(SetupPhase.DISPLACEMENT_DETECTED); state.setDisplacementBar(dBar);
            double[] fvg=findFvg(bars,Math.max(dBar-1,i+1),state.getDirection(),FVG_WINDOW);
            if (fvg==null) { state.setPhase(SetupPhase.INVALID_NO_FVG); continue; }
            state.setPhase(SetupPhase.FVG_DETECTED); state.setFvgTop(fvg[0]); state.setFvgBottom(fvg[1]); state.setFvgBar((int)fvg[2]);
            if (!findRetest(bars,state.getFvgTop(),state.getFvgBottom(),state.getFvgBar(),state.getDirection(),RETEST_WINDOW,n)) { state.setPhase(SetupPhase.INVALID_NO_RETEST); return state; }
            state.setPhase(SetupPhase.RETEST_DETECTED); return state;
        }
        return state;
    }

    private int findDisp(List<OHLCV> bars,double[] atr,int from,String dir,int n) {
        for (int i=from;i<Math.min(from+10,n);i++) {
            OHLCV b=bars.get(i); double av=atr[i]>0?atr[i]:(b.getHigh()-b.getLow());
            if ((b.getHigh()-b.getLow())<av*DISP_ATR_MULT) continue;
            if ("long".equals(dir)&&b.getClose()>b.getOpen()) return i;
            if ("short".equals(dir)&&b.getClose()<b.getOpen()) return i;
        }
        return -1;
    }
    private double[] findFvg(List<OHLCV> bars,int from,String dir,int window) {
        int n=bars.size();
        for (int i=from;i<Math.min(from+window,n-2);i++) {
            OHLCV b1=bars.get(i),b3=bars.get(i+2);
            if ("long".equals(dir)&&b3.getLow()>b1.getHigh())  return new double[]{b3.getLow(),b1.getHigh(),i+1};
            if ("short".equals(dir)&&b1.getLow()>b3.getHigh()) return new double[]{b1.getLow(),b3.getHigh(),i+1};
        }
        return null;
    }
    private boolean findRetest(List<OHLCV> bars,double top,double bot,int fvgBar,String dir,int window,int n) {
        for (int i=fvgBar+1;i<Math.min(fvgBar+window,n);i++) {
            OHLCV b=bars.get(i);
            if (b.getLow()<top&&b.getHigh()>bot) {
                if ("long".equals(dir)&&b.getClose()>=bot) return true;
                if ("short".equals(dir)&&b.getClose()<=top) return true;
            }
        }
        return false;
    }
    private boolean[] detectStructure(List<OHLCV> bars) {
        try {
            List<SwingPoint> sw=sa.detectSwings(bars,5);
            List<StructureBreak> br=sa.detectStructureBreaks(bars,sw);
            return new boolean[]{br.stream().anyMatch(b->b.getBreakType()==StructureType.BOS), br.stream().anyMatch(b->b.getBreakType()==StructureType.CHOCH)};
        } catch (Exception e) { return new boolean[]{false,false}; }
    }
    private double lastNz(double[] a) { for(int i=a.length-1;i>=0;i--) if(a[i]>0) return a[i]; return 0; }
    private double r4(double v) { return Math.round(v*10000.0)/10000.0; }
}
