package com.smcscanner.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Point-in-time cross-sectional ranking for the research pipeline.
 *
 * <p>Every input row must describe the same completed market-data timestamp. The
 * ranker deliberately returns percentile components instead of thresholding raw
 * values so a later experiment can test the ranking without tuning cutoffs on the
 * same sample. Catalyst is reported and used only as a deterministic tie-breaker.</p>
 */
public final class StockInPlayRanker {
    public static final int MIN_UNIVERSE_SIZE = 5;

    public List<RankedStock> rank(List<Snapshot> snapshots, int maxSelected) {
        if (snapshots == null || snapshots.isEmpty()) return List.of();
        if (maxSelected < 1) throw new IllegalArgumentException("maxSelected must be positive");

        long asOf = snapshots.get(0).asOfEpochMs();
        Set<String> tickers = new HashSet<>();
        List<Snapshot> eligible = new ArrayList<>();
        for (Snapshot row : snapshots) {
            if (row == null) continue;
            if (row.asOfEpochMs() != asOf)
                throw new IllegalArgumentException("All stock-in-play rows must use one completed timestamp");
            String ticker = normalizeTicker(row.ticker());
            if (!tickers.add(ticker)) throw new IllegalArgumentException("Duplicate ticker: " + ticker);
            if (row.historyComplete() && valid(row)) {
                eligible.add(new Snapshot(ticker, asOf, row.gapAtr(), row.openingRvol(),
                        row.openingRangeAtr(), row.cumulativeDollarVolume(),
                        row.catalystKnownAtTime(), true));
            }
        }
        if (eligible.isEmpty()) return List.of();

        List<RankedStock> rows = new ArrayList<>();
        for (Snapshot row : eligible) {
            double gap = percentile(eligible, row.gapAtr(), Snapshot::gapAtr);
            double rvol = percentile(eligible, row.openingRvol(), Snapshot::openingRvol);
            double range = percentile(eligible, row.openingRangeAtr(), Snapshot::openingRangeAtr);
            double liquidity = percentile(eligible, row.cumulativeDollarVolume(), Snapshot::cumulativeDollarVolume);
            double score = (gap + rvol + range + liquidity) / 4.0;
            rows.add(new RankedStock(row.ticker(), row.asOfEpochMs(), 0, false, score,
                    gap, rvol, range, liquidity, row.catalystKnownAtTime(), eligible.size()));
        }
        rows.sort(Comparator.comparingDouble(RankedStock::score).reversed()
                .thenComparing(RankedStock::catalystKnownAtTime, Comparator.reverseOrder())
                .thenComparing(RankedStock::ticker));

        boolean usableUniverse = rows.size() >= MIN_UNIVERSE_SIZE;
        List<RankedStock> ranked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            RankedStock row = rows.get(i);
            ranked.add(new RankedStock(row.ticker(), row.asOfEpochMs(), i + 1,
                    usableUniverse && i < maxSelected, row.score(), row.gapPercentile(),
                    row.openingRvolPercentile(), row.openingRangePercentile(),
                    row.dollarVolumePercentile(), row.catalystKnownAtTime(), row.universeSize()));
        }
        return List.copyOf(ranked);
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("Ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean valid(Snapshot row) {
        return finiteNonNegative(row.gapAtr())
                && finiteNonNegative(row.openingRvol())
                && finiteNonNegative(row.openingRangeAtr())
                && finiteNonNegative(row.cumulativeDollarVolume());
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static double percentile(List<Snapshot> rows, double value,
                                     ToDoubleFunction<Snapshot> metric) {
        long lower = rows.stream().filter(row -> metric.applyAsDouble(row) < value).count();
        long equal = rows.stream().filter(row -> Double.compare(metric.applyAsDouble(row), value) == 0).count();
        return (lower + equal * 0.5) / rows.size();
    }

    public record Snapshot(
            String ticker,
            long asOfEpochMs,
            double gapAtr,
            double openingRvol,
            double openingRangeAtr,
            double cumulativeDollarVolume,
            boolean catalystKnownAtTime,
            boolean historyComplete
    ) {}

    public record RankedStock(
            String ticker,
            long asOfEpochMs,
            int rank,
            boolean selected,
            double score,
            double gapPercentile,
            double openingRvolPercentile,
            double openingRangePercentile,
            double dollarVolumePercentile,
            boolean catalystKnownAtTime,
            int universeSize
    ) {}
}
