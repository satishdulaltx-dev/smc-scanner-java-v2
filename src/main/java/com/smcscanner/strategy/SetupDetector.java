package com.smcscanner.strategy;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.*;
import com.smcscanner.smc.StructureAnalyzer;
import com.smcscanner.model.TickerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SetupDetector {
    private static final Logger log = LoggerFactory.getLogger(SetupDetector.class);
    private static final int    SWEEP_LOOKBACK = 80, FVG_WINDOW = 12, RETEST_WINDOW = 25;
    private static final double DISP_ATR_MULT  = 1.3, MIN_FVG_PCT = 0.0004, MIN_VOL_MULT = 1.5; // global defaults — per-ticker overrides in ticker-profiles.json

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

    /** Overload without backtestMode — used by live scanner (session filter active). */
    public DetectResult detectSetups(List<OHLCV> bars, String htfBias, String ticker, boolean isCrypto, double dailyAtr) {
        return detectSetups(bars, htfBias, ticker, isCrypto, dailyAtr, false);
    }

    public DetectResult detectSetups(List<OHLCV> bars, String htfBias, String ticker, boolean isCrypto, double dailyAtr, boolean backtestMode) {
        SMCState state = new SMCState();
        if (bars==null||bars.size()<20) return new DetectResult(List.of(),state);
        if (!backtestMode) {
            if (isCrypto&&!sessionFilter.isInCryptoSession()) { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
            if (!isCrypto&&!sessionFilter.isInNySession())    { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
        }

        double[] atrArr=atrCalc.computeAtr(bars,14);
        double curAtr=lastNz(atrArr), lastClose=bars.get(bars.size()-1).getClose();
        curAtr=Math.max(curAtr,lastClose*0.002);

        double atrPct=atrCalc.atrPercentile(atrArr,-1,100);
        if (atrPct<config.getMinAtrPercentile()) {
            log.trace("{} filtered: LOW_VOL atrPct={}", ticker, String.format("%.1f", atrPct));
            state.setPhase(SetupPhase.LOW_VOLATILITY); return new DetectResult(List.of(),state);
        }

        String vol=lastClose>0&&curAtr/lastClose*100<0.5?"low":(curAtr/lastClose*100<1.5?"medium":"high");
        String session=isCrypto?sessionFilter.cryptoSessionName():sessionFilter.sessionName();

        // Load per-ticker overrides early — dispAtrMult needed inside runStateMachine
        TickerProfile profile    = config.getTickerProfile(ticker);
        double effDispAtrMult    = profile.resolveDispAtrMult(DISP_ATR_MULT);
        double effMinFvgPct      = profile.resolveMinFvgPct(MIN_FVG_PCT);
        double effMinVolMult     = profile.resolveMinVolMult(MIN_VOL_MULT);

        state=runStateMachine(bars,atrArr,state,effDispAtrMult);
        if (!state.isComplete()) {
            log.trace("{} filtered: state={}", ticker, state.getPhase());
            return new DetectResult(List.of(),state);
        }

        // Staleness check: retest must be recent (within last 20 bars)
        int barsAgoRetest = bars.size() - 1 - state.getRetestBar();
        if (barsAgoRetest > 20) {
            log.debug("{} filtered: STALE_RETEST retest was {} bars ago", ticker, barsAgoRetest);
            state.setPhase(SetupPhase.IDLE);
            return new DetectResult(List.of(),state);
        }

        // Price proximity: current price must be within 3 ATRs of FVG zone
        double fvgMidCheck = (state.getFvgTop() + state.getFvgBottom()) / 2.0;
        if (Math.abs(lastClose - fvgMidCheck) > curAtr * 3.0) {
            log.debug("{} filtered: PRICE_FAR_FROM_FVG price={} fvgMid={} dist={} atr={}", ticker,
                String.format("%.2f", lastClose), String.format("%.2f", fvgMidCheck),
                String.format("%.2f", Math.abs(lastClose - fvgMidCheck)), String.format("%.2f", curAtr));
            state.setPhase(SetupPhase.IDLE);
            return new DetectResult(List.of(),state);
        }

        double fvgSize=Math.abs(state.getFvgTop()-state.getFvgBottom());
        if (fvgSize/lastClose<effMinFvgPct) {
            log.debug("{} filtered: FVG_TOO_SMALL fvgSize={} minPct={} (profile)", ticker, fvgSize, effMinFvgPct);
            return new DetectResult(List.of(),state);
        }

        double avgVol=bars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);
        double peakVol=bars.subList(Math.max(0,bars.size()-10),bars.size()).stream().mapToDouble(OHLCV::getVolume).max().orElse(0);
        if (peakVol<avgVol*effMinVolMult) {
            log.debug("{} filtered: LOW_VOLUME peak={} avg={} ratio={}", ticker,
                (long)peakVol, (long)avgVol, String.format("%.2f", peakVol/Math.max(avgVol,1)));
            return new DetectResult(List.of(),state);
        }

        boolean[] str=detectStructure(bars);
        boolean hasStructure=str[0]||str[1];
        if (!hasStructure) log.debug("{} note: NO_STRUCTURE — continuing at lower confidence", ticker);
        if ("bullish".equals(htfBias)&&"short".equals(state.getDirection())) {
            log.debug("{} filtered: HTF_CONFLICT htf=bullish setup=short", ticker);
            return new DetectResult(List.of(),state);
        }
        if ("bearish".equals(htfBias)&&"long".equals(state.getDirection())) {
            log.debug("{} filtered: HTF_CONFLICT htf=bearish setup=long", ticker);
            return new DetectResult(List.of(),state);
        }

        int conf=scoring.scoreSetup(true,true,true,true,hasStructure,peakVol>avgVol*MIN_VOL_MULT);
        if (conf<config.getMinConfidence()) {
            log.debug("{} filtered: LOW_CONF conf={} min={}", ticker, conf, config.getMinConfidence());
            return new DetectResult(List.of(),state);
        }

        double fvgMid=(state.getFvgTop()+state.getFvgBottom())/2.0, entry=r4(fvgMid);

        // Invalidate if price has already traded through the FVG (limit zone is gone)
        // Long FVG: price closed below the bottom — already filled & moved, stale
        // Short FVG: price closed above the top — already filled & moved, stale
        if ("long".equals(state.getDirection())  && lastClose < state.getFvgBottom() - curAtr * 0.5) {
            log.debug("{} filtered: FVG_TRADED_THROUGH long fvgBot={} close={}", ticker, state.getFvgBottom(), lastClose);
            return new DetectResult(List.of(),state);
        }
        if ("short".equals(state.getDirection()) && lastClose > state.getFvgTop() + curAtr * 0.5) {
            log.debug("{} filtered: FVG_TRADED_THROUGH short fvgTop={} close={}", ticker, state.getFvgTop(), lastClose);
            return new DetectResult(List.of(),state);
        }

        double targetAtr = curAtr * 4;
        double sl, tp;
        double slMult  = profile.resolveSlAtrMult() > 0 ? profile.resolveSlAtrMult() : 0.4;
        double tpRatio = profile.resolveTpRrRatio();
        if ("long".equals(state.getDirection()))  { sl=r4(entry-targetAtr*slMult); tp=r4(entry+targetAtr*slMult*tpRatio); }
        else                                       { sl=r4(entry+targetAtr*slMult); tp=r4(entry-targetAtr*slMult*tpRatio); }

        TradeSetup setup=TradeSetup.builder().ticker(ticker).direction(state.getDirection())
            .entry(entry).stopLoss(sl).takeProfit(tp).confidence(conf).session(session).volatility(vol)
            .atr(r4(curAtr)).hasBos(str[0]).hasChoch(str[1]).fvgTop(r4(state.getFvgTop())).fvgBottom(r4(state.getFvgBottom()))
            .timestamp(LocalDateTime.now()).build();
        return new DetectResult(List.of(setup),state);
    }

    private SMCState runStateMachine(List<OHLCV> bars, double[] atrArr, SMCState state, double dispAtrMult) {
        int n = bars.size();
        int scanFrom = Math.max(0, n - SWEEP_LOOKBACK);
        // Slide a 20-bar window to find local swing H/L; look for sweep in bars after each window
        int swingWindow = 20;
        for (int winStart = scanFrom; winStart < n - swingWindow - 3; winStart++) {
            int winEnd = winStart + swingWindow;
            double swHigh = -Double.MAX_VALUE, swLow = Double.MAX_VALUE;
            for (int k = winStart; k < winEnd; k++) {
                if (bars.get(k).getHigh() > swHigh) swHigh = bars.get(k).getHigh();
                if (bars.get(k).getLow()  < swLow)  swLow  = bars.get(k).getLow();
            }
            // Look for sweep in next 5 bars after the window
            for (int i = winEnd; i < Math.min(winEnd + 5, n); i++) {
                OHLCV bar = bars.get(i);
                // 1-bar sweep
                boolean bull = bar.getLow() < swLow  && bar.getClose() > swLow;
                boolean bear = bar.getHigh() > swHigh && bar.getClose() < swHigh;
                // 2-bar sweep
                if (!bull && !bear && i > winEnd) {
                    OHLCV prev = bars.get(i - 1);
                    if (prev.getLow()  < swLow  && bar.getClose() > swLow)  bull = true;
                    if (prev.getHigh() > swHigh && bar.getClose() < swHigh) bear = true;
                }
                if (!bull && !bear) continue;
                state.setPhase(SetupPhase.SWEEP_DETECTED);
                state.setDirection(bull ? "long" : "short");
                state.setSweepBar(i);
                int dBar = findDisp(bars, atrArr, i + 1, state.getDirection(), n, dispAtrMult);
                if (dBar < 0) { state.setPhase(SetupPhase.INVALID_NO_DISP); break; }
                state.setPhase(SetupPhase.DISPLACEMENT_DETECTED);
                state.setDisplacementBar(dBar);
                double[] fvg = findFvg(bars, Math.max(dBar - 1, i + 1), state.getDirection(), FVG_WINDOW);
                if (fvg == null) { state.setPhase(SetupPhase.INVALID_NO_FVG); break; }
                state.setPhase(SetupPhase.FVG_DETECTED);
                state.setFvgTop(fvg[0]); state.setFvgBottom(fvg[1]); state.setFvgBar((int) fvg[2]);
                int rb = findRetestBar(bars, state.getFvgTop(), state.getFvgBottom(), state.getFvgBar(), state.getDirection(), RETEST_WINDOW, n);
                if (rb < 0) {
                    state.setPhase(SetupPhase.INVALID_NO_RETEST);
                    break; // keep searching more recent windows
                }
                state.setRetestBar(rb);
                state.setPhase(SetupPhase.RETEST_DETECTED);
                return state;
            }
        }
        return state;
    }

    private int findDisp(List<OHLCV> bars,double[] atr,int from,String dir,int n,double dispAtrMult) {
        for (int i=from;i<Math.min(from+15,n);i++) {
            OHLCV b=bars.get(i); double av=atr[i]>0?atr[i]:(b.getHigh()-b.getLow());
            if ((b.getHigh()-b.getLow())<av*dispAtrMult) continue;
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
    private int findRetestBar(List<OHLCV> bars,double top,double bot,int fvgBar,String dir,int window,int n) {
        for (int i=fvgBar+1;i<Math.min(fvgBar+window,n);i++) {
            OHLCV b=bars.get(i);
            if (b.getLow()<top&&b.getHigh()>bot) {
                if ("long".equals(dir)&&b.getClose()>=bot) return i;
                if ("short".equals(dir)&&b.getClose()<=top) return i;
            }
        }
        return -1;
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
