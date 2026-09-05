package com.smcscanner.backtest;

import com.smcscanner.data.HistoricalDataException;
import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestIntegrityTest {
    private static final ZoneId ET = ZoneId.of("America/New_York");

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
