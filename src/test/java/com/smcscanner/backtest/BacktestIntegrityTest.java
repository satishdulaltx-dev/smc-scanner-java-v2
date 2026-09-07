package com.smcscanner.backtest;

import com.smcscanner.data.HistoricalDataException;
import com.smcscanner.data.DataCache;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.*;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestIntegrityTest {
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
        return new BacktestService.TradeResult("TEST", "long", "scalp", 100, 99, 102,
                outcome, pnl, "", "", 0, 0, "sweep-flip-long", 80, 1,
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
