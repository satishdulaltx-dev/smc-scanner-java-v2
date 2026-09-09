package com.smcscanner.backtest;

import com.smcscanner.data.HistoricalDataException;
import com.smcscanner.data.DataCache;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.*;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BacktestIntegrityTest {
    @Test
    void historicalSessionReservationIsVisibleAndReleased() {
        PolygonClient client = new PolygonClient(new ScannerConfig(), new DataCache());
        assertFalse(client.isHistoricalSessionActive());
        client.beginHistoricalSession();
        try {
            assertTrue(client.isHistoricalSessionActive());
        } finally {
            client.endHistoricalSession();
        }
        assertFalse(client.isHistoricalSessionActive());
    }

    @Test
    void researchAcceptsScalpCandidateFamiliesAndBoundedHolds() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate end = LocalDate.of(2026, 6, 5);
        for (String pattern : List.of("scalp", "scalp-early", "scalp-core", "scalp-rvol", "scalp-tod-rvol", "scalp-breakout", "scalp-breakout-retest", "scalp-structure",
                "scalp-spy", "scalp-chase", "vwap", "vwap-cont-long", "vwap-cont-short",
                "vwap-reversion-long", "vwap-reversion-short", "breakout", "keylevel", "vsqueeze", "or-vwap", "idiv",
                "sweep-flip", "ict-sweep-fvg-1m", "choch-primary", "pdh-pdl")) {
            BacktestRun run = new BacktestRun(start, end, pattern, Set.of(), 30);
            assertEquals(pattern, run.pattern);
            assertEquals(30, run.maxHoldMinutes);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestRun(start, end, "scalp", Set.of(), 45));
        assertEquals(1.0, new BacktestRun(start, end, "scalp", Set.of(), 30, 1.0).targetR);
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestRun(start, end, "scalp", Set.of(), 30, 0.75));
    }

    @Test
    void researchUsesLiveEntryHoursIncludingOpeningRangeException() {
        assertFalse(BacktestService.researchEntryWindowAllows("scalp", at("2026-06-01T09:40"), false));
        assertTrue(BacktestService.researchEntryWindowAllows("or-vwap", at("2026-06-01T09:40"), false));
        assertTrue(BacktestService.researchEntryWindowAllows("scalp", at("2026-06-01T09:45"), false));
        assertFalse(BacktestService.researchEntryWindowAllows("scalp", at("2026-06-01T15:30"), false));
        assertTrue(BacktestService.researchEntryWindowAllows("scalp", at("2026-06-01T18:00"), true));
    }

    @Test
    void researchChargesTheExitHalfOfRoundTripExecutionFriction() {
        assertEquals(1.95, BacktestService.netResearchPnlPct(2.0));
        assertEquals(-1.05, BacktestService.netResearchPnlPct(-1.0));
        assertEquals(-0.05, BacktestService.netResearchPnlPct(0.0));
        assertFalse(BacktestService.researchRiskSupportsCosts(100,99.7));
        assertTrue(BacktestService.researchRiskSupportsCosts(100,99.5));
    }

    @Test
    void controlledTargetKeepsInitialRiskFixedForLongsAndShorts() {
        assertEquals(100.5, BacktestService.researchTarget(100,99,"long",0.5));
        assertEquals(102.0, BacktestService.researchTarget(100,99,"long",2.0));
        assertEquals(99.5, BacktestService.researchTarget(100,101,"short",0.5));
        assertEquals(98.0, BacktestService.researchTarget(100,101,"short",2.0));
    }
    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Test
    void morningContextCannotSeeTodaysDailyClose() {
        var service = new com.smcscanner.market.MarketContextService(null);
        var yesterday = bar(at("2026-06-01T00:00"), 20, 20, 20, 20);
        var today = bar(at("2026-06-02T00:00"), 20, 40, 20, 40);
        var context = service.getContextAt("AMD", List.of(), List.of(),
                List.of(yesterday, today), at("2026-06-02T10:00"));
        assertEquals(20, context.vixLevel());
    }

    @Test
    void onlyCompletedCandlesAreVisibleAtDecisionTime() {
        OHLCV five = bar(at("2026-06-01T09:30"), 100, 101, 99, 100);
        OHLCV fifteen = bar(at("2026-06-01T09:30"), 100, 102, 98, 101);

        assertEquals(at("2026-06-01T09:35"), BacktestService.completedAt(five, 5));
        assertTrue(BacktestService.completedBars(List.of(fifteen), 15,
                at("2026-06-01T09:44")).isEmpty());
        assertEquals(1, BacktestService.completedBars(List.of(fifteen), 15,
                at("2026-06-01T09:45")).size());
    }

    @Test
    void missingBenchmarkSlotFailsCoverageInsteadOfReturningZeroTrades() {
        LocalDate day = LocalDate.of(2026, 6, 1);
        List<OHLCV> benchmark = List.of(
                bar(at("2026-06-01T09:30"), 1, 1, 1, 1),
                bar(at("2026-06-01T09:35"), 1, 1, 1, 1));
        List<OHLCV> incomplete = List.of(benchmark.get(0));

        HistoricalDataException error = assertThrows(HistoricalDataException.class,
                () -> BacktestRun.requireSlots("TEST 5m", incomplete, benchmark, day, day));
        assertTrue(error.getMessage().contains("1 missing of 2"));
    }

    @Test
    void sameBarStopAndTargetUsesTheStopThatWasActiveAtBarOpen() throws Exception {
        BacktestService service = new BacktestService(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        Method method = BacktestService.class.getDeclaredMethod("simulateClassicExit",
                List.class, double.class, double.class, double.class, String.class);
        method.setAccessible(true);
        Object exit = method.invoke(service,
                List.of(bar(at("2026-06-01T10:00"), 100, 103, 98, 101)),
                100.0, 99.0, 102.0, "long");
        Method outcome = exit.getClass().getDeclaredMethod("outcome");
        outcome.setAccessible(true);
        assertEquals("LOSS", outcome.invoke(exit));
    }

    @Test
    void summaryUsesAllExecutionsAndCountsEveryFilteredReason() {
        List<BacktestService.TradeResult> rows = List.of(
                trade("WIN", 1.0), trade("LOSS", -1.0), trade("BE_STOP", 0.0),
                trade("TRAP_FILTERED", 0.0));
        BacktestService.BacktestResult result = BacktestService.BacktestResult.of(
                "TEST", rows, 10, BacktestMode.SCALP);

        assertEquals(3, result.total);
        assertEquals(33.3, result.winRate);
        assertEquals(0.0, result.expectancy, 0.0001);
        assertEquals(1, result.filteredTotal);
        assertEquals(1L, result.filteredByReason.get("TRAP_FILTERED"));
    }

    @Test
    void researchEvidenceRejectsAConsistentlyLosingSample() {
        List<BacktestService.TradeResult> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(tradeAt("LOSS", -1.0,
                LocalDate.of(2025, 1, 2).plusDays(i).atTime(10, 0)));

        ResearchStatistics.Summary evidence = ResearchStatistics.summarize(rows);

        assertEquals("REJECTED", evidence.verdict());
        assertEquals(-1.0, evidence.meanR());
        assertTrue(evidence.ciHighR() <= 0);
        assertEquals(0.0, evidence.profitFactor());
    }

    @Test
    void researchEvidenceDoesNotPromoteFourLuckyTrades() {
        List<BacktestService.TradeResult> rows = List.of(
                tradeAt("WIN", 2.0, LocalDateTime.of(2025, 1, 2, 10, 0)),
                tradeAt("WIN", 2.0, LocalDateTime.of(2025, 2, 2, 10, 0)),
                tradeAt("WIN", 2.0, LocalDateTime.of(2025, 3, 2, 10, 0)),
                tradeAt("WIN", 2.0, LocalDateTime.of(2025, 4, 2, 10, 0)));

        ResearchStatistics.Summary evidence = ResearchStatistics.summarize(rows);

        assertEquals("INSUFFICIENT_SAMPLE", evidence.verdict());
        assertEquals(4, evidence.trades());
        assertEquals(4, evidence.positiveMonths());
    }

    @Test
    void researchFeatureSnapshotContainsOnlyPointInTimeInputs() {
        List<OHLCV> bars = List.of(
                OHLCV.builder().timestamp(at("2026-06-01T09:30")).open(100).high(101).low(99).close(100).volume(100).build(),
                OHLCV.builder().timestamp(at("2026-06-01T09:35")).open(100).high(102).low(100).close(101.5).volume(200).build());
        TradeSetup setup = TradeSetup.builder().ticker("TEST").direction("long").entry(101.5)
                .stopLoss(100.5).takeProfit(103.5).confidence(80).atr(2).build();

        Map<String,Double> features = BacktestService.researchFeatures(bars, bars, setup,
                com.smcscanner.strategy.MarketRegimeDetector.Regime.TRENDING, at("2026-06-01T09:40"));

        assertEquals(10.0, features.get("minutes_from_open"));
        assertEquals(2.0, features.get("volume_ratio_6"));
        assertEquals(1.0, features.get("regime_trending"));
        assertEquals(0.0, features.get("regime_ranging"));
        assertTrue(features.get("directional_return_5m") > 0);
        assertFalse(features.containsKey("outcome"));
        assertFalse(features.containsKey("mfe"));
    }

    @Test
    void researchSpyFeatureIgnoresPremarketMove() {
        List<OHLCV> bars = List.of(
                OHLCV.builder().timestamp(at("2026-06-01T09:30")).open(100).high(101).low(99).close(100).volume(100).build(),
                OHLCV.builder().timestamp(at("2026-06-01T09:35")).open(100).high(101).low(99).close(100).volume(100).build());
        List<OHLCV> spy = List.of(
                OHLCV.builder().timestamp(at("2026-06-01T08:00")).open(50).high(100).low(50).close(100).volume(100).build(),
                OHLCV.builder().timestamp(at("2026-06-01T09:30")).open(100).high(101).low(99).close(100).volume(100).build(),
                OHLCV.builder().timestamp(at("2026-06-01T09:35")).open(100).high(102).low(99).close(101).volume(100).build());
        TradeSetup setup = TradeSetup.builder().ticker("TEST").direction("long").entry(100)
                .stopLoss(99).takeProfit(102).confidence(80).atr(2).build();

        Map<String,Double> features = BacktestService.researchFeatures(bars, spy, setup,
                com.smcscanner.strategy.MarketRegimeDetector.Regime.RANGING, at("2026-06-01T09:40"));

        assertEquals(0.01, features.get("directional_spy_return"), 0.000001);
    }

    @Test
    void unavailableOptionalContextBecomesAVisibleWarning() {
        PolygonClient deniedClient = new PolygonClient(new ScannerConfig(), new DataCache()) {
            @Override public synchronized List<OHLCV> getHistoricalBars(
                    String ticker, String timeframe, LocalDate from, LocalDate to) {
                throw new HistoricalDataException(ticker + " " + timeframe + ": provider HTTP 403");
            }
        };
        BacktestRun run = new BacktestRun(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));

        assertTrue(run.optionalBars(deniedClient, "I:VIX", "1d", 450,
                "VIX-dependent adjustments are omitted").isEmpty());
        assertEquals(1, run.warnings.size());
        assertTrue(run.warnings.get(0).contains("VIX-dependent adjustments are omitted"));
        assertEquals(false, ((Map<?, ?>) run.coverage.values().iterator().next()).get("available"));
    }

    @Test
    void evenOneUnresolvedMinuteFailsCoverage() {
        LocalDate day = LocalDate.of(2026, 6, 1);
        List<OHLCV> benchmark = new java.util.ArrayList<>();
        List<OHLCV> ticker = new java.util.ArrayList<>();
        for (int dateOffset = 0; dateOffset < 3; dateOffset++) {
            long open = day.plusDays(dateOffset).atTime(9, 30).atZone(ET).toInstant().toEpochMilli();
            for (int minute = 0; minute < 390; minute++) {
                OHLCV bar = bar(open + minute * 60_000L, 1, 1, 1, 1);
                benchmark.add(bar);
                if (dateOffset != 1 || minute != 100) ticker.add(bar);
            }
        }
        BacktestRun run = new BacktestRun(day, day.plusDays(2));

        var error = assertThrows(HistoricalDataException.class,
                () -> run.requireSlotsAllowSparse("TEST 1m", ticker, benchmark));
        assertTrue(error.getMessage().contains("1 missing of 1170"));
    }

    @Test
    void isolatedEmptyMinuteIsNormalizedButCompleteParentGapFails() {
        LocalDate day=LocalDate.of(2026,6,1);
        long open=day.atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        OHLCV parent=bar(open,100,101,99,100.5);
        List<OHLCV> minutes=new java.util.ArrayList<>();
        for (int minute=0;minute<5;minute++) if (minute!=2)
            minutes.add(bar(open+minute*60_000L,100+minute*.1,100.2+minute*.1,99.9+minute*.1,100.1+minute*.1));
        BacktestRun run=new BacktestRun(day,day);
        List<OHLCV> normalized=run.normalizeSparseMinuteBars("TEST 1m",List.of(parent),minutes);
        assertEquals(5,normalized.stream().filter(b->b.getTimestamp()>=open&&b.getTimestamp()<open+300_000L).count());
        OHLCV filled=normalized.stream().filter(b->b.getTimestamp()==open+120_000L).findFirst().orElseThrow();
        assertEquals(0,filled.getVolume());
        assertEquals(filled.getOpen(),filled.getClose());
        assertTrue(run.warnings.get(0).contains("filled 1"));
        assertThrows(HistoricalDataException.class,
                ()->new BacktestRun(day,day).normalizeSparseMinuteBars("TEST 1m",List.of(parent),List.of()));
    }

    @Test
    void threeRTargetAllowsTrailingToActivateBeforeTakeProfit() throws Exception {
        var bars = List.of(bar(at("2026-06-01T10:00"),100,102.7,99.8,102.6),
                bar(at("2026-06-01T10:01"),102.6,102.8,102.4,102.5));
        Object result=exit("simulateHybridExit",bars,99,103,"long",true);
        assertEquals("TRAIL_WIN",value(result,"outcome"));
        assertEquals(2.5,value(result,"pnlPct"));
        assertEquals("WIN",value(exit("simulateHybridExit",bars,99,102,"long",true),"outcome"));
    }

    @Test
    void earlyScalpCanEvaluateMorningSignalWithoutChangingBaselineWarmup() {
        var bars = new java.util.ArrayList<OHLCV>();
        for (int i=0;i<7;i++) bars.add(bar(at("2026-06-01T09:30")+i*300_000L,
                100+i*.1,101.1,99.8,100+i*.1));
        bars.add(OHLCV.builder().timestamp(at("2026-06-01T10:05")).open(100.5).high(101.1)
                .low(100.4).close(101.1).volume(250).build());
        var detector=new com.smcscanner.strategy.ScalpMomentumDetector(null,
                new com.smcscanner.indicator.VolumeProfileCalculator(),null);
        assertTrue(detector.detect(bars,List.of(),"TEST",2,true).isEmpty());
        assertFalse(detector.detectEarlyResearch(bars,List.of(),"TEST",2).isEmpty());
        assertTrue(detector.detectTimeOfDayRvolResearch(bars,List.of(),"TEST",2,0).isEmpty());
        assertTrue(detector.detect(bars,List.of(),"TEST",2,true).isEmpty());
    }

    @Test
    void scalpResearchRejectsUnknownEmbeddedLayer() {
        var detector=new com.smcscanner.strategy.ScalpMomentumDetector(null,
                new com.smcscanner.indicator.VolumeProfileCalculator(),null);
        assertThrows(IllegalArgumentException.class,
                () -> detector.detectResearchLayer(List.of(),List.of(),"TEST",2,"unknown"));
    }

    @Test
    void momentumScalpRequiresAFreshRangeBreakWithVolumeExpansion() {
        var bars=new java.util.ArrayList<OHLCV>();
        for (int i=0;i<7;i++) bars.add(OHLCV.builder().timestamp(at("2026-06-01T09:30")+i*300_000L)
                .open(100).high(100.5).low(99.8).close(100.1).volume(100).build());
        bars.add(OHLCV.builder().timestamp(at("2026-06-01T10:05")).open(100.25).high(100.68)
                .low(100.2).close(100.65).volume(250).build());
        var detector=new com.smcscanner.strategy.ScalpMomentumDetector(null,
                new com.smcscanner.indicator.VolumeProfileCalculator(),null);
        assertFalse(detector.detectBreakoutResearch(bars,"TEST",2).isEmpty());
        var weak=new java.util.ArrayList<>(bars);
        weak.set(7,OHLCV.builder().timestamp(at("2026-06-01T10:05")).open(100.25).high(100.68)
                .low(100.2).close(100.65).volume(120).build());
        assertTrue(detector.detectBreakoutResearch(weak,"TEST",2).isEmpty());
    }

    @Test
    void momentumRetestWaitsForReclaimInsteadOfBuyingTheBreakoutBar() {
        var bars=new java.util.ArrayList<OHLCV>();
        for (int i=0;i<7;i++) bars.add(OHLCV.builder().timestamp(at("2026-06-01T09:30")+i*300_000L)
                .open(100).high(100.5).low(99.8).close(100.1).volume(100).build());
        bars.add(OHLCV.builder().timestamp(at("2026-06-01T10:05")).open(100.25).high(100.82)
                .low(100.2).close(100.78).volume(250).build());
        var detector=new com.smcscanner.strategy.ScalpMomentumDetector(null,
                new com.smcscanner.indicator.VolumeProfileCalculator(),null);
        assertTrue(detector.detectBreakoutRetestResearch(bars,"TEST",2).isEmpty());
        bars.add(OHLCV.builder().timestamp(at("2026-06-01T10:10")).open(100.48).high(100.72)
                .low(100.45).close(100.68).volume(130).build());
        assertFalse(detector.detectBreakoutRetestResearch(bars,"TEST",2).isEmpty());
    }

    @Test
    void timeOfDayVolumeBaselineUsesOnlyPriorSessionsAndResistsOneSpike() {
        var byDate=new java.util.TreeMap<LocalDate,List<OHLCV>>();
        var dates=new java.util.ArrayList<LocalDate>();
        double[] volumes={100,110,10_000,90,120};
        for (int i=0;i<volumes.length;i++) {
            LocalDate day=LocalDate.of(2026,6,1).plusDays(i);
            dates.add(day);
            byDate.put(day,List.of(OHLCV.builder().timestamp(day.atTime(10,0).atZone(ET).toInstant().toEpochMilli())
                    .open(100).high(101).low(99).close(100).volume(volumes[i]).build()));
        }
        LocalDate decision=LocalDate.of(2026,6,8);
        dates.add(decision);
        byDate.put(decision,List.of(OHLCV.builder().timestamp(decision.atTime(10,0).atZone(ET).toInstant().toEpochMilli())
                .open(100).high(101).low(99).close(100).volume(50_000).build()));
        Map<LocalTime,Double> baseline=BacktestService.priorSessionMedianVolume(byDate,dates,5,20);
        assertEquals(110.0,baseline.get(LocalTime.of(10,0)));
    }

    @Test
    void earlyCloseDecisionIsSkippedOnlyAfterTheLastTradableMinute() {
        List<OHLCV> halfDay=List.of(bar(at("2026-07-03T12:58"),100,101,99,100),
                bar(at("2026-07-03T12:59"),100,101,99,100));
        assertFalse(BacktestService.decisionIsAfterLastTradableMinute(halfDay,at("2026-07-03T12:59")));
        assertTrue(BacktestService.decisionIsAfterLastTradableMinute(halfDay,at("2026-07-03T13:00")));
    }

    @Test
    void slippageCannotResurrectASetupWhoseStopWasAlreadyCrossed() {
        assertFalse(BacktestService.validEntryStop(342.235,342.0638825,342.0642,"short"));
        assertFalse(BacktestService.validEntryStop(99,99.0495,99.02,"long"));
        assertTrue(BacktestService.validEntryStop(100,100.05,99,"long"));
        assertTrue(BacktestService.validEntryStop(100,99.95,101,"short"));
    }

    @Test
    void gapThroughStopFillsAtOpenInBothDirectionsAndAllTouchStopModels() throws Exception {
        for (String model : List.of("simulateClassicExit", "simulateHybridExit", "simulateScalpExit")) {
            for (String dir : List.of("long", "short")) {
                boolean buy = dir.equals("long");
                var bars = List.of(bar(at("2026-06-02T09:30"), buy ? 97 : 103, buy ? 98 : 104,
                        buy ? 96 : 102, buy ? 97 : 103));
                Object exit = exit(model, bars, buy ? 99 : 101, buy ? 102 : 98, dir, true);
                assertEquals(-3.0, value(exit, "pnlPct"), model + " " + dir);
                assertEquals("LOSS", value(exit, "outcome"));
            }
        }
    }

    @Test
    void fixedStopRemainsInPlaceAfterOneRWhileClassicMovesToBreakeven() throws Exception {
        var bars = List.of(bar(at("2026-06-01T10:00"), 100, 101.2, 99.5, 101),
                bar(at("2026-06-01T10:01"), 100.5, 102.2, 99.5, 102));
        assertEquals("WIN", value(exit("simulateClassicExit", bars,99,102,"long",false),"outcome"));
        assertEquals("BE_STOP", value(exit("simulateClassicExit", bars,99,102,"long",true),"outcome"));
    }

    @Test
    void gapPastBreakevenIsALossRatherThanABreakeven() throws Exception {
        var bars = List.of(bar(at("2026-06-01T15:55"),100,101.2,99.5,101),
                bar(at("2026-06-02T09:30"),98,99,97,98));
        Object result = exit("simulateClassicExit",bars,99,102,"long",true);
        assertEquals("LOSS",value(result,"outcome"));
        assertEquals(-2.0,value(result,"pnlPct"));
    }

    @Test
    void priorDayRejectionCanTriggerWithHistoryButNotWithoutIt() {
        var bars = new java.util.ArrayList<OHLCV>();
        for (int i=0;i<78;i++) bars.add(bar(at("2026-06-01T09:30")+i*300_000L,100,101,99,100));
        var today = new java.util.ArrayList<OHLCV>();
        for (int i=0;i<4;i++) today.add(bar(at("2026-06-02T09:30")+i*300_000L,100,100.5,99.5,100));
        today.add(bar(at("2026-06-02T09:50"),100.5,101.5,100,100.5));
        bars.addAll(today);
        var detector = new com.smcscanner.strategy.PdhPdlDetector();
        assertTrue(detector.detect(today,"TEST",2,true).isEmpty());
        assertFalse(detector.detect(bars,"TEST",2,true).isEmpty());
    }

    @Test
    void oneMinuteIctResearchRequiresSweepThenGapThenCurrentRetest() {
        var bars = new java.util.ArrayList<OHLCV>();
        long start=at("2026-06-01T09:30");
        for (int i=0;i<21;i++) bars.add(bar(start+i*60_000L,100,100.4,99.6,100));
        bars.add(bar(start+21*60_000L,100,100.1,99.0,99.4));       // sweep; reversal follows
        bars.add(bar(start+22*60_000L,99.4,100.5,99.4,100.4));
        bars.add(bar(start+23*60_000L,100.5,101.0,100.3,100.9));  // bullish FVG above sweep bar
        bars.add(bar(start+24*60_000L,100.3,100.7,100.15,100.4));// midpoint retest and reclaim
        var detector = new com.smcscanner.strategy.LiquiditySweepFlipDetector(null);

        assertTrue(detector.detectOneMinuteFvgResearch(bars.subList(0,24),"TEST").isEmpty());
        var setups=detector.detectOneMinuteFvgResearch(bars,"TEST");
        assertEquals(1,setups.size());
        assertEquals("long",setups.get(0).getDirection());
        assertTrue(setups.get(0).getStopLoss()<99.0);
        assertTrue(setups.get(0).getTakeProfit()>setups.get(0).getEntry());
    }

    private Object exit(String name, List<OHLCV> bars, double stop, double target, String dir, boolean be) throws Exception {
        var service = new BacktestService(null,null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        Method method;
        if (name.equals("simulateClassicExit")) {
            method = BacktestService.class.getDeclaredMethod(name,List.class,double.class,double.class,double.class,String.class,boolean.class);
            method.setAccessible(true);
            return method.invoke(service,bars,100.0,stop,target,dir,be);
        }
        method=BacktestService.class.getDeclaredMethod(name,List.class,List.class,double.class,double.class,double.class,String.class);
        method.setAccessible(true);
        return method.invoke(service,List.of(),bars,100.0,stop,target,dir);
    }

    private Object value(Object result,String field) throws Exception {
        Method method=result.getClass().getDeclaredMethod(field);
        method.setAccessible(true);
        return method.invoke(result);
    }

    private static BacktestService.TradeResult trade(String outcome, double pnl) {
        return tradeAt(outcome, pnl, LocalDateTime.of(2026, 6, 1, 10, 0));
    }

    private static BacktestService.TradeResult tradeAt(String outcome, double pnl, LocalDateTime entry) {
        long entryMs = entry.atZone(ET).toInstant().toEpochMilli();
        return new BacktestService.TradeResult("TEST", "long", "scalp", 100, 99, 102,
                outcome, pnl, "", "", entryMs, entryMs + 60_000, "sweep-flip-long", 80, 1,
                0, null, 0, null, 0, null, 0, 0, 0, 0, 1);
    }

    private static long at(String value) {
        return LocalDateTime.parse(value).atZone(ET).toInstant().toEpochMilli();
    }

    private static OHLCV bar(long time, double open, double high, double low, double close) {
        return OHLCV.builder().timestamp(time).open(open).high(high).low(low)
                .close(close).volume(100).build();
    }
}
