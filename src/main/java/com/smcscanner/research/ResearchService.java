package com.smcscanner.research;

import com.smcscanner.backtest.BacktestMode;
import com.smcscanner.backtest.BacktestService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.TickerProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResearchService {
    private static final int[] WINDOWS = {90, 180, 365};
    private static final String[] STRATEGIES = {"smc", "vwap", "breakout", "keylevel"};

    private final BacktestService backtestService;
    private final ScannerConfig config;

    public ResearchService(BacktestService backtestService, ScannerConfig config) {
        this.backtestService = backtestService;
        this.config = config;
    }

    public ResearchReport runWatchlistResearch() {
        List<String> tickers = config.loadWatchlist();
        List<TickerResearch> rows = new ArrayList<>();

        for (String ticker : tickers) {
            rows.add(analyzeTicker(ticker));
        }

        rows.sort(Comparator
                .comparing((TickerResearch r) -> recommendationRank(r.recommendation()))
                .thenComparing(TickerResearch::score, Comparator.reverseOrder()));

        long keepCount = rows.stream().filter(r -> "keep".equals(r.recommendation())).count();
        long retuneCount = rows.stream().filter(r -> "retune".equals(r.recommendation())).count();
        long retestCount = rows.stream().filter(r -> "retest".equals(r.recommendation())).count();
        long disableCount = rows.stream().filter(r -> "disable".equals(r.recommendation())).count();

        return new ResearchReport(
                tickers.size(),
                rows.size(),
                (int) keepCount,
                (int) retuneCount,
                (int) retestCount,
                (int) disableCount,
                rows
        );
    }

    private TickerResearch analyzeTicker(String ticker) {
        TickerProfile profile = config.getTickerProfile(ticker);
        String currentStrategy = profile.getStrategyType();

        List<WindowStat> windows = new ArrayList<>();
        for (int days : WINDOWS) {
            BacktestService.BacktestResult bt = backtestService.run(ticker, days, BacktestMode.ALL, null);
            windows.add(new WindowStat(
                    days,
                    bt.total,
                    bt.winRate,
                    round2(bt.expectancy),
                    bt.wins,
                    bt.losses,
                    bt.beStops,
                    bt.error
            ));
        }

        BacktestService.BacktestResult currentYear = backtestService.run(ticker, 365, BacktestMode.ALL, null);
        List<FailureStat> failures = summarizeFailures(currentYear);
        List<StrategyScore> strategyScores = compareStrategies(ticker, currentStrategy);
        StrategyScore bestStrategy = strategyScores.stream()
                .findFirst()
                .orElse(new StrategyScore(currentStrategy, 0, 0, 0, 0, false));

        String recommendation = recommend(currentStrategy, bestStrategy, windows, currentYear);
        String summary = buildSummary(currentStrategy, bestStrategy, windows, currentYear, recommendation);
        double score = scoreTicker(windows, currentYear);

        return new TickerResearch(
                ticker,
                currentStrategy,
                bestStrategy.strategy(),
                recommendation,
                round2(score),
                summary,
                currentYear.total,
                round2(currentYear.winRate),
                round2(currentYear.expectancy),
                round2(currentYear.totalOptPnl),
                currentYear.newsFiltered,
                currentYear.ctxFiltered,
                currentYear.qualityFiltered,
                windows,
                strategyScores,
                failures
        );
    }

    private List<StrategyScore> compareStrategies(String ticker, String currentStrategy) {
        List<StrategyScore> results = new ArrayList<>();

        for (String strategy : STRATEGIES) {
            BacktestService.BacktestResult bt = backtestService.run(ticker, 180, BacktestMode.ALL, strategy);
            results.add(new StrategyScore(
                    strategy,
                    bt.total,
                    round2(bt.winRate),
                    round2(bt.expectancy),
                    round2(bt.totalOptPnl),
                    strategy.equalsIgnoreCase(currentStrategy)
            ));
        }

        results.sort(Comparator
                .comparing(StrategyScore::expectancy, Comparator.reverseOrder())
                .thenComparing(StrategyScore::totalTrades, Comparator.reverseOrder()));
        return results;
    }

    private List<FailureStat> summarizeFailures(BacktestService.BacktestResult bt) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        bt.trades.forEach(trade -> counts.merge(normalizeOutcome(trade.outcome()), 1, Integer::sum));

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> new FailureStat(e.getKey(), e.getValue()))
                .toList();
    }

    private String normalizeOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) return "unknown";
        return outcome.toLowerCase(Locale.ROOT)
                .replace("_", " ");
    }

    private String recommend(String currentStrategy,
                             StrategyScore bestStrategy,
                             List<WindowStat> windows,
                             BacktestService.BacktestResult currentYear) {
        boolean allWeak = windows.stream().allMatch(w -> w.expectancy() <= 0);
        boolean lowSample = currentYear.total < 8;
        boolean materiallyBetterAlt = !bestStrategy.strategy().equalsIgnoreCase(currentStrategy)
                && bestStrategy.expectancy() > currentYear.expectancy + 0.20
                && bestStrategy.totalTrades() >= Math.max(3, currentYear.total / 3);
        boolean weakCurrent = currentYear.expectancy <= 0 || currentYear.winRate < 45;
        boolean decentCurrent = currentYear.expectancy > 0.20 && currentYear.winRate >= 50;

        if (allWeak && !lowSample) return "disable";
        if (materiallyBetterAlt && weakCurrent) return "retest";
        if (lowSample) return "retest";
        if (decentCurrent) return "keep";
        return "retune";
    }

    private String buildSummary(String currentStrategy,
                                StrategyScore bestStrategy,
                                List<WindowStat> windows,
                                BacktestService.BacktestResult currentYear,
                                String recommendation) {
        WindowStat recent = windows.get(0);
        WindowStat medium = windows.get(1);

        if ("disable".equals(recommendation)) {
            return "Negative across rolling windows. Current " + currentStrategy + " profile is not carrying its weight.";
        }
        if ("retest".equals(recommendation) && !bestStrategy.strategy().equalsIgnoreCase(currentStrategy)) {
            return "Current " + currentStrategy + " is weak while " + bestStrategy.strategy()
                    + " looks stronger over 180d. Revalidate before changing live.";
        }
        if ("retest".equals(recommendation)) {
            return "Sample is still thin. Keep researching this ticker before trusting the profile.";
        }
        if ("keep".equals(recommendation)) {
            return "Current " + currentStrategy + " is holding up. Recent "
                    + recent.days() + "d exp " + signed(recent.expectancy())
                    + " and 365d exp " + signed(round2(currentYear.expectancy)) + ".";
        }
        return "Edge is positive but not clean. 90d exp " + signed(recent.expectancy())
                + ", 180d exp " + signed(medium.expectancy())
                + ". Tighten filters before scaling.";
    }

    private double scoreTicker(List<WindowStat> windows, BacktestService.BacktestResult currentYear) {
        double rollingExp = windows.stream().mapToDouble(WindowStat::expectancy).sum();
        return rollingExp + (currentYear.winRate / 50.0) + Math.min(currentYear.total, 40) / 20.0;
    }

    private int recommendationRank(String recommendation) {
        return switch (recommendation) {
            case "keep" -> 0;
            case "retune" -> 1;
            case "retest" -> 2;
            case "disable" -> 3;
            default -> 4;
        };
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String signed(double value) {
        return (value > 0 ? "+" : "") + round2(value) + "%";
    }

    public record ResearchReport(
            int watchlistSize,
            int analyzedCount,
            int keepCount,
            int retuneCount,
            int retestCount,
            int disableCount,
            List<TickerResearch> tickers
    ) {}

    public record TickerResearch(
            String ticker,
            String currentStrategy,
            String bestStrategy,
            String recommendation,
            double score,
            String summary,
            int totalTrades365,
            double winRate365,
            double expectancy365,
            double optPnl365,
            int newsFiltered365,
            int ctxFiltered365,
            int qualityFiltered365,
            List<WindowStat> windows,
            List<StrategyScore> strategies,
            List<FailureStat> failures
    ) {}

    public record WindowStat(
            int days,
            int totalTrades,
            double winRate,
            double expectancy,
            int wins,
            int losses,
            int beStops,
            String error
    ) {}

    public record StrategyScore(
            String strategy,
            int totalTrades,
            double winRate,
            double expectancy,
            double optPnl,
            boolean current
    ) {}

    public record FailureStat(String outcome, int count) {}
}
