package com.smcscanner.research;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockInPlayRankerTest {
    private static final long AS_OF = 1_800_000_000_000L;

    @Test
    void selectsOnlyTheTopNamesFromOneSynchronizedUniverse() {
        var ranker = new StockInPlayRanker();
        var ranked = ranker.rank(List.of(
                snapshot("AAA", 5, 5, 5, 500),
                snapshot("BBB", 4, 4, 4, 400),
                snapshot("CCC", 3, 3, 3, 300),
                snapshot("DDD", 2, 2, 2, 200),
                snapshot("EEE", 1, 1, 1, 100)
        ), 2);

        assertEquals(List.of("AAA", "BBB", "CCC", "DDD", "EEE"),
                ranked.stream().map(StockInPlayRanker.RankedStock::ticker).toList());
        assertEquals(List.of(true, true, false, false, false),
                ranked.stream().map(StockInPlayRanker.RankedStock::selected).toList());
        assertEquals(0.9, ranked.get(0).score(), 1e-9);
        assertEquals(5, ranked.get(0).universeSize());
    }

    @Test
    void incompleteRowsCannotWinAndSmallUniversesDoNotAuthorizeTrades() {
        var ranker = new StockInPlayRanker();
        var rows = new ArrayList<>(List.of(
                snapshot("AAA", 1, 1, 1, 100),
                snapshot("BBB", 2, 2, 2, 200),
                snapshot("CCC", 3, 3, 3, 300),
                snapshot("DDD", 4, 4, 4, 400)
        ));
        rows.add(new StockInPlayRanker.Snapshot("LEAK", AS_OF, 99, 99, 99, 9999, true, false));

        var ranked = ranker.rank(rows, 2);
        assertEquals(4, ranked.size());
        assertTrue(ranked.stream().noneMatch(StockInPlayRanker.RankedStock::selected));
        assertTrue(ranked.stream().noneMatch(row -> row.ticker().equals("LEAK")));
    }

    @Test
    void rejectsMixedDecisionTimestampsAndDuplicateTickers() {
        var ranker = new StockInPlayRanker();
        var mixed = List.of(snapshot("AAA", 1, 1, 1, 100),
                new StockInPlayRanker.Snapshot("BBB", AS_OF + 60_000, 2, 2, 2, 200, false, true));
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(mixed, 1));
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(List.of(
                snapshot("AAA", 1, 1, 1, 100), snapshot("aaa", 2, 2, 2, 200)), 1));
    }

    @Test
    void catalystDoesNotDistortMeasuredActivity() {
        var ranker = new StockInPlayRanker();
        var rows = List.of(
                new StockInPlayRanker.Snapshot("AAA", AS_OF, 5, 5, 5, 500, false, true),
                new StockInPlayRanker.Snapshot("BBB", AS_OF, 4, 4, 4, 400, true, true),
                snapshot("CCC", 3, 3, 3, 300), snapshot("DDD", 2, 2, 2, 200),
                snapshot("EEE", 1, 1, 1, 100));
        var ranked = ranker.rank(rows, 1);
        assertEquals("AAA", ranked.get(0).ticker());
        assertTrue(ranked.get(1).catalystKnownAtTime());
    }

    private static StockInPlayRanker.Snapshot snapshot(String ticker, double gap, double rvol,
                                                        double range, double dollarVolume) {
        return new StockInPlayRanker.Snapshot(ticker, AS_OF, gap, rvol, range,
                dollarVolume, false, true);
    }
}
