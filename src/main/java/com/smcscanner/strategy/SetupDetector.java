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
    private static final int    SWEEP_LOOKBACK = 40, FVG_WINDOW = 12, RETEST_WINDOW = 20;
    private static final double DISP_ATR_MULT  = 1.3, MIN_FVG_PCT = 0.0002, MIN_VOL_MULT = 1.5; // global defaults — per-ticker overrides in ticker-profiles.json
    private static final double MAX_ENTRY_DISTANCE_ATR = 2.5;
    // Price-based distance floor: never reject if price is within 3.5% of FVG midpoint.
    // Fixes COIN ($209, ATR $1.75 → 2.5×ATR=$4.38 < $6.45 gap → filtered unfairly)
    // and SOFI ($17, ATR $0.05 → 2.5×ATR=$0.125 < $0.19 gap → filtered unfairly).
    private static final double MAX_ENTRY_DISTANCE_PRICE_PCT = 0.035;

    private final ScannerConfig config;
    private final AtrCalculator atrCalc;
    private final StructureAnalyzer sa;
    private final SessionFilter sessionFilter;

    public SetupDetector(ScannerConfig config, AtrCalculator atrCalc, StructureAnalyzer sa,
                         ScoringService scoring, SessionFilter sessionFilter) {
        this.config=config; this.atrCalc=atrCalc; this.sa=sa; this.sessionFilter=sessionFilter;
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
            // Staleness guard: reject if the last bar is from a prior calendar day
            java.time.ZoneId et = java.time.ZoneId.of("America/New_York");
            java.time.LocalDate lastBarDate = java.time.Instant.ofEpochMilli(bars.get(bars.size()-1).getTimestamp()).atZone(et).toLocalDate();
            if (!lastBarDate.equals(java.time.LocalDate.now(et))) { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
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

        // ── Cross-day FVG guard (Fix: blocks yesterday FVG at today's open) ──────
        // The state machine scans 80 bars back, which spans multiple sessions.
        // A sweep+FVG from yesterday afternoon can "retest" on today's opening bar —
        // but yesterday's institutional zone is NOT reliable S/R at tomorrow's 9:30 open.
        // Opening bars are in a completely different session context (gaps, overnight news,
        // different participants). Block these cross-session FVG retests before 10:00 AM.
        if (!isCrypto && state.getFvgBar() >= 0 && state.getFvgBar() < bars.size()) {
            java.time.ZoneId etZone = java.time.ZoneId.of("America/New_York");
            long fvgBarTs  = bars.get(state.getFvgBar()).getTimestamp();
            long currBarTs = bars.get(bars.size() - 1).getTimestamp();
            java.time.LocalDate fvgDate  = java.time.Instant.ofEpochMilli(fvgBarTs).atZone(etZone).toLocalDate();
            java.time.LocalDate currDate = java.time.Instant.ofEpochMilli(currBarTs).atZone(etZone).toLocalDate();
            java.time.LocalTime currTime = java.time.Instant.ofEpochMilli(currBarTs).atZone(etZone).toLocalTime();
            // Block cross-day FVGs before 10:00 — prior-session zone unreliable at open
            if (!fvgDate.equals(currDate) && currTime.isBefore(java.time.LocalTime.of(10, 0))) {
                log.debug("{} filtered: CROSS_DAY_FVG fvgDate={} currDate={} currTime={} — stale prior-session zone at open",
                        ticker, fvgDate, currDate, currTime);
                state.setPhase(SetupPhase.IDLE);
                return new DetectResult(List.of(), state);
            }
            // Block ALL SMC entries in the first 15 min — opening bars are directionally unreliable
            // (gap fills, stop hunts, news reactions). Same-day FVG formed on the 09:30 bar itself
            // also fails this check. User confirmed: "first 5-15 mins volatile, doesn't predict direction."
            if (currTime.isBefore(java.time.LocalTime.of(9, 45))) {
                log.debug("{} filtered: OPEN_VOLATILITY — SMC blocked before 09:45 (currTime={})", ticker, currTime);
                state.setPhase(SetupPhase.IDLE);
                return new DetectResult(List.of(), state);
            }
        }

        // Price proximity: current price must be near the FVG zone.
        // Dual limit: ATR-based (2.5×) OR price-based floor (3.5% of price), whichever is larger.
        // Pure ATR-based check fails for:
        //   High-beta large caps (COIN $209, 5m ATR $1.75 → cap $4.38, but $6 moves are normal)
        //   Low-price volatile stocks (SOFI $17, 5m ATR $0.05 → cap $0.13, any wick = filtered)
        double fvgMidCheck = (state.getFvgTop() + state.getFvgBottom()) / 2.0;
        double distLimit   = Math.max(curAtr * MAX_ENTRY_DISTANCE_ATR, lastClose * MAX_ENTRY_DISTANCE_PRICE_PCT);
        if (Math.abs(lastClose - fvgMidCheck) > distLimit) {
            log.debug("{} filtered: PRICE_FAR_FROM_FVG price={} fvgMid={} dist={} limit={} (atrLimit={} pctLimit={})", ticker,
                String.format("%.2f", lastClose), String.format("%.2f", fvgMidCheck),
                String.format("%.2f", Math.abs(lastClose - fvgMidCheck)),
                String.format("%.2f", distLimit),
                String.format("%.2f", curAtr * MAX_ENTRY_DISTANCE_ATR),
                String.format("%.2f", lastClose * MAX_ENTRY_DISTANCE_PRICE_PCT));
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
        if (!hasStructure) {
            log.debug("{} filtered: NO_STRUCTURE — sweep without BOS/CHOCH is noise, not SMC", ticker);
            return new DetectResult(List.of(), state);
        }
        if ("bullish".equals(htfBias)&&"short".equals(state.getDirection())) {
            log.debug("{} filtered: HTF_CONFLICT htf=bullish setup=short", ticker);
            return new DetectResult(List.of(),state);
        }
        if ("bearish".equals(htfBias)&&"long".equals(state.getDirection())) {
            log.debug("{} filtered: HTF_CONFLICT htf=bearish setup=long", ticker);
            return new DetectResult(List.of(),state);
        }

        if (!hasCleanRetest(bars, state)) {
            log.debug("{} filtered: WEAK_RETEST close quality did not confirm {}", ticker, state.getDirection());
            return new DetectResult(List.of(), state);
        }

        // Quality-based confidence — replaces flat true/true/true/true (was always 100, filter was dead).
        // Displacement conviction: how far close is into the bar (0.70-1.0 range for long/short).
        OHLCV db2 = bars.get(state.getDisplacementBar());
        double dRng = Math.max(0.0001, db2.getHigh() - db2.getLow());
        double dClosePos = (db2.getClose() - db2.getLow()) / dRng;
        double dispConv = "long".equals(state.getDirection()) ? dClosePos : (1.0 - dClosePos);
        int dispBonus = (int) Math.max(0, (dispConv - 0.70) / 0.30 * 15); // 0.70→0, 1.0→15

        // Retest conviction: how cleanly price rejected at the FVG zone (0.50-1.0 range).
        OHLCV rb2 = bars.get(state.getRetestBar());
        double rRng = Math.max(0.0001, rb2.getHigh() - rb2.getLow());
        double rClosePos = (rb2.getClose() - rb2.getLow()) / rRng;
        double retestConv = "long".equals(state.getDirection()) ? rClosePos : (1.0 - rClosePos);
        int retestBonus = (int) Math.max(0, (retestConv - 0.50) / 0.50 * 10); // 0.50→0, 1.0→10

        // Recency: fresher sweeps are more reliable (sweep within 8 bars = best).
        int barsFromSweep = (bars.size() - 1) - state.getSweepBar();
        int recencyBonus = barsFromSweep <= 8 ? 15 : (barsFromSweep <= 15 ? 8 : 0);

        // Base 50 (confirmed 4-stage SMC chain) + structure 10 + vol 10 + disp 0-15 + retest 0-10 + recency 0-15 = 50-110 capped at 100
        int conf = 50 + 10
                + (peakVol > avgVol * MIN_VOL_MULT ? 10 : 0)
                + Math.min(15, dispBonus)
                + Math.min(10, retestBonus)
                + recencyBonus;
        conf = Math.min(100, conf);
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

        // ── SMC signal breakdown (shown in "WHY THIS TRADE?" panel) ────────────
        // Format: "smc-{dir} | sweep=BEAR(N bars back) | disp=2.4×ATR | FVG=[top/bot] | retest=X% | BOS=✓/✗ CHOCH=✓/✗ | vol=X×avg"
        int n = bars.size();
        String sweepDir  = "long".equals(state.getDirection()) ? "BULL" : "BEAR";
        int    sweepBarsBack = state.getSweepBar() >= 0 ? (n - 1 - state.getSweepBar()) : -1;
        double dispRange = 0, dispAtrRatio = 0;
        if (state.getDisplacementBar() >= 0 && state.getDisplacementBar() < n) {
            OHLCV db = bars.get(state.getDisplacementBar());
            dispRange    = db.getHigh() - db.getLow();
            dispAtrRatio = curAtr > 0 ? dispRange / curAtr : 0;
        }
        double retestClosePos = 0;
        if (state.getRetestBar() >= 0 && state.getRetestBar() < n) {
            OHLCV rb    = bars.get(state.getRetestBar());
            double range = Math.max(0.0001, rb.getHigh() - rb.getLow());
            retestClosePos = (rb.getClose() - rb.getLow()) / range * 100.0;
        }
        double volRatio = avgVol > 0 ? peakVol / avgVol : 0;
        String smcBreakdown = String.format(
                "smc-%s | sweep=%s(%d bars back) | disp=%.1f×ATR | FVG=[%.2f/%.2f] | retest=%.0f%% | BOS=%s CHOCH=%s | vol=%.1f×avg",
                state.getDirection(), sweepDir, sweepBarsBack,
                dispAtrRatio, state.getFvgTop(), state.getFvgBottom(),
                retestClosePos,
                str[0] ? "✓" : "✗", str[1] ? "✓" : "✗",
                volRatio);

        TradeSetup setup=TradeSetup.builder().ticker(ticker).direction(state.getDirection())
            .entry(entry).stopLoss(sl).takeProfit(tp).confidence(conf).session(session).volatility(vol)
            .atr(r4(curAtr)).hasBos(str[0]).hasChoch(str[1]).fvgTop(r4(state.getFvgTop())).fvgBottom(r4(state.getFvgBottom()))
            .factorBreakdown(smcBreakdown)
            .timestamp(LocalDateTime.now()).build();
        return new DetectResult(List.of(setup),state);
    }

    private SMCState runStateMachine(List<OHLCV> bars, double[] atrArr, SMCState state, double dispAtrMult) {
        int n = bars.size();
        int scanFrom = Math.max(0, n - SWEEP_LOOKBACK);
        int swingWindow = 20;
        SMCState best = null;
        double bestScore = -1.0;

        // Scan ALL windows, collect every complete sweep→disp→FVG→retest chain, return highest-ranked.
        // Previously returned first match (stale old pattern); now returns the best candidate.
        for (int winStart = scanFrom; winStart < n - swingWindow - 3; winStart++) {
            int winEnd = winStart + swingWindow;
            double swHigh = -Double.MAX_VALUE, swLow = Double.MAX_VALUE;
            for (int k = winStart; k < winEnd; k++) {
                if (bars.get(k).getHigh() > swHigh) swHigh = bars.get(k).getHigh();
                if (bars.get(k).getLow()  < swLow)  swLow  = bars.get(k).getLow();
            }
            for (int i = winEnd; i < Math.min(winEnd + 5, n); i++) {
                OHLCV bar = bars.get(i);
                boolean bull = bar.getLow() < swLow  && bar.getClose() > swLow;
                boolean bear = bar.getHigh() > swHigh && bar.getClose() < swHigh;
                if (!bull && !bear && i > winEnd) {
                    OHLCV prev = bars.get(i - 1);
                    if (prev.getLow()  < swLow  && bar.getClose() > swLow)  bull = true;
                    if (prev.getHigh() > swHigh && bar.getClose() < swHigh) bear = true;
                }
                if (!bull && !bear) continue;
                String dir = bull ? "long" : "short";
                int dBar = findDisp(bars, atrArr, i + 1, dir, n, dispAtrMult);
                if (dBar < 0) break; // no displacement in this window
                double[] fvg = findFvg(bars, Math.max(dBar - 1, i + 1), dir, FVG_WINDOW);
                if (fvg == null) break;
                int rb = findRetestBar(bars, fvg[0], fvg[1], (int) fvg[2], dir, RETEST_WINDOW, n);
                if (rb < 0) break; // incomplete chain — move to next window
                double score = rankCandidate(bars, i, dBar, rb, dir, n);
                if (score > bestScore) {
                    bestScore = score;
                    SMCState cand = new SMCState();
                    cand.setPhase(SetupPhase.RETEST_DETECTED);
                    cand.setDirection(dir);
                    cand.setSweepBar(i);
                    cand.setDisplacementBar(dBar);
                    cand.setFvgTop(fvg[0]); cand.setFvgBottom(fvg[1]); cand.setFvgBar((int) fvg[2]);
                    cand.setRetestBar(rb);
                    best = cand;
                }
                break; // one candidate per swing window
            }
        }
        return best != null ? best : state;
    }

    /** Ranks a complete SMC candidate: recency (50%) + displacement conviction (30%) + retest conviction (20%). */
    private double rankCandidate(List<OHLCV> bars, int sweepBar, int dispBar, int retestBar, String dir, int n) {
        double recency = Math.max(0.0, 1.0 - (n - 1 - retestBar) / (double) RETEST_WINDOW);
        OHLCV db = bars.get(dispBar);
        double dRng = Math.max(0.0001, db.getHigh() - db.getLow());
        double dPos  = (db.getClose() - db.getLow()) / dRng;
        double dispConv   = "long".equals(dir) ? dPos : (1.0 - dPos);
        OHLCV rb = bars.get(retestBar);
        double rRng = Math.max(0.0001, rb.getHigh() - rb.getLow());
        double rPos  = (rb.getClose() - rb.getLow()) / rRng;
        double retestConv = "long".equals(dir) ? rPos : (1.0 - rPos);
        return recency * 0.5 + dispConv * 0.3 + retestConv * 0.2;
    }

    private int findDisp(List<OHLCV> bars,double[] atr,int from,String dir,int n,double dispAtrMult) {
        for (int i=from;i<Math.min(from+15,n);i++) {
            OHLCV b=bars.get(i); double av=atr[i]>0?atr[i]:(b.getHigh()-b.getLow());
            double barRange=b.getHigh()-b.getLow();
            if (barRange<av*dispAtrMult) continue;
            if ("long".equals(dir)&&b.getClose()>b.getOpen()) {
                // Conviction: close in top 30% of the bar (not just barely above open)
                double closePos=barRange>0?(b.getClose()-b.getLow())/barRange:0;
                if (closePos>=0.70) return i;
            }
            if ("short".equals(dir)&&b.getClose()<b.getOpen()) {
                double closePos=barRange>0?(b.getClose()-b.getLow())/barRange:1;
                if (closePos<=0.30) return i;
            }
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
    private boolean hasCleanRetest(List<OHLCV> bars, SMCState state) {
        if (state.getRetestBar() < 0 || state.getRetestBar() >= bars.size()) return false;
        OHLCV retest = bars.get(state.getRetestBar());
        double range = Math.max(0.0001, retest.getHigh() - retest.getLow());
        double closePos = (retest.getClose() - retest.getLow()) / range;
        double mid = (state.getFvgTop() + state.getFvgBottom()) / 2.0;
        // Require close in top/bottom 35% of bar — excludes doji-only wicks but allows normal rejection bodies.
        // 50% was too tight: cut valid setups where retest bar closed at 40-49% (small-body rejections).
        if ("long".equals(state.getDirection())) {
            return retest.getClose() >= mid && closePos >= 0.35;
        }
        return retest.getClose() <= mid && closePos <= 0.65;
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
