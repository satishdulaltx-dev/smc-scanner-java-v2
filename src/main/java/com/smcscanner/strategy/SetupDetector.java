package com.smcscanner.strategy;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.*;
import com.smcscanner.smc.LiquidityAnalyzer;
import com.smcscanner.smc.StructureAnalyzer;
import com.smcscanner.model.TickerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class SetupDetector {
    private static final Logger log = LoggerFactory.getLogger(SetupDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int    SWEEP_LOOKBACK = 80, FVG_WINDOW = 12, RETEST_WINDOW = 25;
    private static final double DISP_ATR_MULT  = 1.3, MIN_FVG_PCT = 0.0002, MIN_VOL_MULT = 1.5; // global defaults — per-ticker overrides in ticker-profiles.json
    private static final double MAX_ENTRY_DISTANCE_ATR = 2.5;
    // Price-based distance floor: never reject if price is within 3.5% of FVG midpoint.
    // Fixes COIN ($209, ATR $1.75 → 2.5×ATR=$4.38 < $6.45 gap → filtered unfairly)
    // and SOFI ($17, ATR $0.05 → 2.5×ATR=$0.125 < $0.19 gap → filtered unfairly).
    private static final double MAX_ENTRY_DISTANCE_PRICE_PCT = 0.035;
    // FVG older than this many bars (100 min on 5m) is considered price-discovered intraday
    private final ScannerConfig config;
    private final AtrCalculator atrCalc;
    private final StructureAnalyzer sa;
    private final ScoringService scoring;
    private final SessionFilter sessionFilter;
    private final LiquidityAnalyzer liquidityAnalyzer;

    public SetupDetector(ScannerConfig config, AtrCalculator atrCalc, StructureAnalyzer sa,
                         ScoringService scoring, SessionFilter sessionFilter,
                         LiquidityAnalyzer liquidityAnalyzer) {
        this.config=config; this.atrCalc=atrCalc; this.sa=sa; this.scoring=scoring;
        this.sessionFilter=sessionFilter; this.liquidityAnalyzer=liquidityAnalyzer;
    }

    public record DetectResult(List<TradeSetup> setups, SMCState state) {}

    /** Overload without backtestMode — used by live scanner (session filter active). */
    public DetectResult detectSetups(List<OHLCV> bars, String htfBias, String ticker, boolean isCrypto, double dailyAtr) {
        return detectSetups(bars, htfBias, ticker, isCrypto, dailyAtr, false);
    }

    public DetectResult detectSetups(List<OHLCV> bars, String htfBias, String ticker, boolean isCrypto, double dailyAtr, boolean backtestMode) {
        SMCState state = new SMCState();
        if (bars==null||bars.size()<20) return new DetectResult(List.of(),state);
        long lastBarTs = bars.get(bars.size()-1).getTimestamp();
        LocalDate lastBarDate = Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalDate();
        LocalTime lastBarTime = Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalTime();
        if (!backtestMode) {
            if (isCrypto&&!sessionFilter.isInCryptoSession(lastBarTime)) { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
            if (!isCrypto&&!sessionFilter.isInNySession(lastBarTime))    { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
            // Staleness guard: reject if the last bar is from a prior calendar day
            if (!lastBarDate.equals(LocalDate.now(ET))) { state.setPhase(SetupPhase.OUTSIDE_SESSION); return new DetectResult(List.of(),state); }
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
        String session=isCrypto?sessionFilter.cryptoSessionName(lastBarTime):sessionFilter.sessionName(lastBarTime);

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

        // FVG age: no hard cutoff — ScoringService penalizes freshness via fvgAgeBars.
        // A morning FVG retested in the afternoon is 24+ bars old but still valid S/R.
        int fvgAgeBars = (bars.size() - 1) - state.getFvgBar();

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

        if (!hasCleanRetest(bars, state)) {
            log.debug("{} filtered: WEAK_RETEST close quality did not confirm {}", ticker, state.getDirection());
            return new DetectResult(List.of(), state);
        }

        // ── Equal-level sweep detection (via LiquidityAnalyzer) ─────────────────
        // Equal highs/lows = where stop orders actually cluster. Sweeping those levels
        // is far more meaningful than sweeping a single swing.
        boolean isEqualSweep = false;
        int sweepBarIdx = state.getSweepBar();
        if (sweepBarIdx >= 0) {
            try {
                List<SwingPoint> swings = sa.detectSwings(bars, 5);
                List<LiquiditySweep> liqSweeps = liquidityAnalyzer.detectLiquiditySweeps(bars, swings);
                isEqualSweep = liqSweeps.stream()
                        .filter(ls -> ls.getIndex() == sweepBarIdx)
                        .anyMatch(ls -> "equal".equals(ls.getLevelType()));
            } catch (Exception ignored) {}
        }

        // ── Displacement ATR ratio ───────────────────────────────────────────────
        double dispAtrRatio = 0;
        if (state.getDisplacementBar() >= 0 && state.getDisplacementBar() < bars.size()) {
            OHLCV db2 = bars.get(state.getDisplacementBar());
            dispAtrRatio = curAtr > 0 ? (db2.getHigh() - db2.getLow()) / curAtr : 0;
        }

        // ── Retest wick quality ──────────────────────────────────────────────────
        double retestWickQuality = 0.5;
        if (state.getRetestBar() >= 0 && state.getRetestBar() < bars.size()) {
            OHLCV rb2  = bars.get(state.getRetestBar());
            double rng = Math.max(0.0001, rb2.getHigh() - rb2.getLow());
            double pos = (rb2.getClose() - rb2.getLow()) / rng;
            retestWickQuality = "long".equals(state.getDirection()) ? pos : (1.0 - pos);
        }

        int conf=scoring.scoreSetup(isEqualSweep, dispAtrRatio, fvgAgeBars, retestWickQuality,
                                    hasStructure, peakVol > avgVol * MIN_VOL_MULT);
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
        // Minimum SL floor: risk < 0.35% of entry means options spread eats the entire move
        if (Math.abs(entry - sl) < entry * 0.0035) return new DetectResult(List.of(), state);

        // ── SMC signal breakdown (shown in "WHY THIS TRADE?" panel) ────────────
        // Format: "smc-{dir} | sweep=BEAR(N bars back) | disp=2.4×ATR | FVG=[top/bot] | retest=X% | BOS=✓/✗ CHOCH=✓/✗ | vol=X×avg"
        int n = bars.size();
        String sweepDir  = "long".equals(state.getDirection()) ? "BULL" : "BEAR";
        int    sweepBarsBack = state.getSweepBar() >= 0 ? (n - 1 - state.getSweepBar()) : -1;
        // dispAtrRatio already computed above for scoring — reuse here
        double retestClosePos = retestWickQuality * 100.0; // already computed for scoring
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
            .timestamp(Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalDateTime()).build();
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
        if ("long".equals(state.getDirection())) {
            if (!(retest.getClose() >= mid && closePos >= 0.35)) return false;
        } else {
            if (!(retest.getClose() <= mid && closePos <= 0.65)) return false;
        }
        // Consolidation guard: between displacement and retest, at least 2 bars must not extend
        // beyond the displacement candle's extreme. Sideways consolidation counts — we don't
        // require a visible lower-high staircase, just that price isn't still running.
        int dispBar   = state.getDisplacementBar();
        int retestBar = state.getRetestBar();
        String dir    = state.getDirection();
        if (dispBar >= 0 && dispBar < bars.size() && retestBar - dispBar >= 3) {
            double dispExtreme = "long".equals(dir) ? bars.get(dispBar).getHigh() : bars.get(dispBar).getLow();
            int nonExtendingBars = 0;
            for (int i = dispBar + 1; i < retestBar && i < bars.size(); i++) {
                OHLCV curr = bars.get(i);
                if ("long".equals(dir)  && curr.getHigh() <= dispExtreme) nonExtendingBars++;
                if ("short".equals(dir) && curr.getLow()  >= dispExtreme) nonExtendingBars++;
            }
            if (nonExtendingBars < 2) return false;
        }
        return true;
    }
    /**
     * CHOCH primary: fires when CHOCH is detected in the last MAX_AGE bars but the full SMC
     * chain (sweep→disp→FVG→retest) is not complete. Entry = CHOCH bar close, SL = structural
     * swing on the prior side, TP = 2:1 R. Base confidence 70 — lower than full SMC chain.
     */
    public List<TradeSetup> detectChochPrimary(List<OHLCV> bars, String ticker, double dailyAtr,
                                                boolean backtestMode) {
        if (bars == null || bars.size() < 20) return List.of();
        int n = bars.size();
        long lastBarTs = bars.get(n - 1).getTimestamp();
        if (!backtestMode) {
            java.time.LocalDate today = Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalDate();
            if (!today.equals(java.time.LocalDate.now(ET))) return List.of();
            java.time.LocalTime t = Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalTime();
            if (t.isBefore(java.time.LocalTime.of(9, 45)) || !t.isBefore(java.time.LocalTime.of(15, 30)))
                return List.of();
        }

        double[] atrArr = atrCalc.computeAtr(bars, 14);
        double curAtr = lastNz(atrArr);
        double lastClose = bars.get(n - 1).getClose();
        curAtr = Math.max(curAtr, lastClose * 0.002);

        try {
            List<SwingPoint> swings = sa.detectSwings(bars, 5);
            List<StructureBreak> breaks = sa.detectStructureBreaks(bars, swings);
            if (breaks.isEmpty()) return List.of();

            // Find the most recent CHOCH within last 5 bars
            StructureBreak choch = null;
            for (int i = breaks.size() - 1; i >= 0; i--) {
                StructureBreak b = breaks.get(i);
                if (b.getBreakType() == StructureType.CHOCH && (n - 1 - b.getIndex()) <= 5) {
                    choch = b;
                    break;
                }
            }
            if (choch == null) return List.of();

            int    chochIdx  = choch.getIndex();
            OHLCV  chochBar  = bars.get(chochIdx);
            double entry     = r4(chochBar.getClose());

            // Determine direction: CHOCH on a swing HIGH break = bullish flip = long
            //                      CHOCH on a swing LOW break  = bearish flip = short
            // The swing at priorSwingIdx tells us: if it's a HIGH that was broken → long
            int    priorIdx  = choch.getPriorSwingIdx();
            if (priorIdx < 0 || priorIdx >= bars.size()) return List.of();
            boolean priorIsHigh = swings.stream()
                    .filter(s -> s.getIndex() == priorIdx)
                    .anyMatch(s -> s.getSwingType() == SwingType.HIGH);
            String dir = priorIsHigh ? "long" : "short";

            // SL = most recent swing on the opposite side within 30 bars before the CHOCH
            double sl;
            if ("long".equals(dir)) {
                // SL below: find nearest swing LOW before the CHOCH bar
                double swingLow = swings.stream()
                        .filter(s -> s.getSwingType() == SwingType.LOW && s.getIndex() < chochIdx
                                  && s.getIndex() >= Math.max(0, chochIdx - 30))
                        .mapToDouble(SwingPoint::getPrice)
                        .min().orElse(entry - curAtr);
                sl = r4(swingLow - curAtr * 0.15);
            } else {
                // SL above: find nearest swing HIGH before the CHOCH bar
                double swingHigh = swings.stream()
                        .filter(s -> s.getSwingType() == SwingType.HIGH && s.getIndex() < chochIdx
                                  && s.getIndex() >= Math.max(0, chochIdx - 30))
                        .mapToDouble(SwingPoint::getPrice)
                        .max().orElse(entry + curAtr);
                sl = r4(swingHigh + curAtr * 0.15);
            }

            double risk = "long".equals(dir) ? entry - sl : sl - entry;
            // Minimum SL floor: risk < 0.35% of entry means options spread eats the entire move
            if (risk <= 0 || risk > curAtr * 3.0 || risk < entry * 0.0035) return List.of();

            double tp = "long".equals(dir) ? r4(entry + risk * 2.0) : r4(entry - risk * 2.0);
            double avgVol = bars.stream().skip(Math.max(0, n - 30))
                               .mapToDouble(OHLCV::getVolume).average().orElse(1);

            // Wick quality gate: CHOCH bar must show conviction via rejection wick ≥ 1.5× body OR volume > 1.5× avg
            double chochBody = Math.abs(chochBar.getClose() - chochBar.getOpen());
            double rejectionWick = "long".equals(dir)
                    ? (Math.min(chochBar.getOpen(), chochBar.getClose()) - chochBar.getLow())
                    : (chochBar.getHigh() - Math.max(chochBar.getOpen(), chochBar.getClose()));
            boolean wickOk = chochBody > 0 && rejectionWick >= chochBody * 1.5;
            boolean volOk  = chochBar.getVolume() > avgVol * 1.5;
            if (!wickOk && !volOk) {
                log.debug("{} filtered: CHOCH_WEAK_WICK dir={} body={} rejWick={} vol={}×avg",
                        ticker, dir,
                        String.format("%.4f", chochBody),
                        String.format("%.4f", Math.max(0, rejectionWick)),
                        String.format("%.2f", chochBar.getVolume() / Math.max(avgVol, 1)));
                return List.of();
            }

            int conf = 70;
            if (chochBar.getVolume() > avgVol * 1.5) conf += 6;
            if (chochBar.getVolume() > avgVol * 2.5) conf += 4;

            String session  = sessionFilter.sessionName(Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalTime());
            double atrPct   = lastClose > 0 ? curAtr / lastClose * 100 : 0;
            String volLabel = atrPct < 0.5 ? "low" : (atrPct < 1.5 ? "medium" : "high");
            String factors  = String.format(
                    "choch-primary-%s | breakLevel=%.2f | age=%d bars | vol=%.1f×avg",
                    dir, choch.getPrice(), n - 1 - chochIdx, chochBar.getVolume() / Math.max(avgVol, 1));

            return List.of(TradeSetup.builder()
                    .ticker(ticker).direction(dir).entry(entry).stopLoss(sl).takeProfit(tp)
                    .confidence(conf).session(session).volatility(volLabel).atr(r4(curAtr))
                    .hasBos(false).hasChoch(true).fvgTop(0).fvgBottom(0)
                    .factorBreakdown(factors)
                    .timestamp(Instant.ofEpochMilli(lastBarTs).atZone(ET).toLocalDateTime())
                    .build());
        } catch (Exception e) {
            log.debug("{} CHOCH_PRIMARY error: {}", ticker, e.getMessage());
            return List.of();
        }
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
