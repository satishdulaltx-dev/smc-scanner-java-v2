package com.smcscanner.research;

import com.smcscanner.backtest.BacktestExitStyle;
import com.smcscanner.backtest.BacktestMode;
import com.smcscanner.backtest.BacktestService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.TickerProfile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ResearchService {
    private static final int[] WINDOWS = {90, 180, 365};
    private static final String[] STRATEGIES = {"smc", "vwap", "breakout", "keylevel"};
    private static final int MIN_BEST_STRATEGY_TRADES = 8;
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final BacktestService backtestService;
    private final ScannerConfig config;
    private final Map<BacktestExitStyle, ResearchRunState> runStates = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ResearchService(BacktestService backtestService, ScannerConfig config) {
        this.backtestService = backtestService;
        this.config = config;
        for (BacktestExitStyle style : BacktestExitStyle.values()) {
            runStates.put(style, new ResearchRunState());
        }
    }

    public ResearchReport runWatchlistResearch() {
        return runWatchlistResearch(BacktestExitStyle.CLASSIC);
    }

    public ResearchReport runWatchlistResearch(BacktestExitStyle exitStyle) {
        List<String> tickers = config.loadWatchlist().stream()
                .filter(t -> !t.startsWith("X:"))
                .toList();
        List<TickerResearch> rows = new ArrayList<>();

        for (String ticker : tickers) {
            rows.add(analyzeTicker(ticker, exitStyle));
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
                exitStyle.name(),
                exitStyle.label(),
                rows
        );
    }

    public ResearchStatus getStatus(BacktestExitStyle exitStyle) {
        ResearchRunState state = runStates.get(exitStyle);
        if (state == null) state = new ResearchRunState();
        ResearchReport cached = state.lastReport;
        return new ResearchStatus(
                exitStyle.name(),
                exitStyle.label(),
                state.running,
                state.lastStartedAt,
                state.lastCompletedAt,
                state.lastError,
                cached != null,
                cached,
                elapsedSeconds(state)
        );
    }

    public ResearchStatus startRefresh(BacktestExitStyle exitStyle) {
        ResearchRunState state = runStates.computeIfAbsent(exitStyle, ignored -> new ResearchRunState());
        synchronized (state) {
            if (state.running) {
                return getStatus(exitStyle);
            }
            state.running = true;
            state.lastStartedAt = ZonedDateTime.now(ET);
            state.lastError = null;
        }

        executor.submit(() -> {
            try {
                ResearchReport report = runWatchlistResearch(exitStyle);
                synchronized (state) {
                    state.lastReport = report;
                    state.lastCompletedAt = ZonedDateTime.now(ET);
                    state.lastError = null;
                }
            } catch (Exception e) {
                synchronized (state) {
                    state.lastError = e.getMessage();
                    state.lastCompletedAt = ZonedDateTime.now(ET);
                }
            } finally {
                synchronized (state) {
                    state.running = false;
                }
            }
        });

        return getStatus(exitStyle);
    }

    public ResearchComparison getComparison() {
        ResearchReport classic = getStatus(BacktestExitStyle.CLASSIC).report();
        ResearchReport live = getStatus(BacktestExitStyle.LIVE_PARITY).report();
        if (classic == null || live == null) {
            return new ResearchComparison(false, false, false, 0, 0, 0, List.of());
        }

        Map<String, TickerResearch> classicMap = classic.tickers().stream()
                .collect(java.util.stream.Collectors.toMap(TickerResearch::ticker, Function.identity()));
        Map<String, TickerResearch> liveMap = live.tickers().stream()
                .collect(java.util.stream.Collectors.toMap(TickerResearch::ticker, Function.identity()));

        List<TickerComparison> rows = new ArrayList<>();
        for (String ticker : classicMap.keySet()) {
            TickerResearch c = classicMap.get(ticker);
            TickerResearch l = liveMap.get(ticker);
            if (c == null || l == null) continue;
            rows.add(new TickerComparison(
                    ticker,
                    c.recommendation(),
                    l.recommendation(),
                    c.winRate365(),
                    l.winRate365(),
                    round2(l.winRate365() - c.winRate365()),
                    c.expectancy365(),
                    l.expectancy365(),
                    round2(l.expectancy365() - c.expectancy365()),
                    c.timeoutRate365(),
                    l.timeoutRate365(),
                    round2(l.timeoutRate365() - c.timeoutRate365())
            ));
        }

        rows.sort(Comparator
                .comparing(TickerComparison::expectancyDelta, Comparator.reverseOrder())
                .thenComparing(TickerComparison::winRateDelta, Comparator.reverseOrder()));

        int improved = (int) rows.stream().filter(r -> r.expectancyDelta() > 0.05).count();
        int declined = (int) rows.stream().filter(r -> r.expectancyDelta() < -0.05).count();

        return new ResearchComparison(true, true, true, rows.size(), improved, declined, rows);
    }

    private long elapsedSeconds(ResearchRunState state) {
        if (state.lastStartedAt == null) return 0;
        ZonedDateTime end = state.running ? ZonedDateTime.now(ET)
                : (state.lastCompletedAt != null ? state.lastCompletedAt : ZonedDateTime.now(ET));
        return Math.max(0, Duration.between(state.lastStartedAt, end).getSeconds());
    }

    private TickerResearch analyzeTicker(String ticker, BacktestExitStyle exitStyle) {
        TickerProfile profile = config.getTickerProfile(ticker);
        String currentStrategy = profile.getStrategyType();

        List<WindowStat> windows = new ArrayList<>();
        for (int days : WINDOWS) {
            BacktestService.BacktestResult bt = backtestService.run(ticker, days, BacktestMode.ALL, null, exitStyle);
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

        BacktestService.BacktestResult currentYear = backtestService.run(ticker, 365, BacktestMode.ALL, null, exitStyle);
        OutcomeSummary outcomeSummary = summarizeOutcomes(currentYear);
        List<StrategyScore> strategyScores = compareStrategies(ticker, currentStrategy, exitStyle);
        StrategyScore bestStrategy = strategyScores.stream()
                .findFirst()
                .orElse(new StrategyScore(currentStrategy, 0, 0, 0, 0, false, false));

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
                exitStyle.name(),
                currentYear.total,
                round2(currentYear.winRate),
                round2(currentYear.expectancy),
                round2(currentYear.totalOptPnl),
                outcomeSummary.timeoutRate(),
                outcomeSummary.confCapReliance(),
                currentYear.newsFiltered,
                currentYear.ctxFiltered,
                currentYear.qualityFiltered,
                windows,
                strategyScores,
                outcomeSummary.executed(),
                outcomeSummary.filtered()
        );
    }

    private List<StrategyScore> compareStrategies(String ticker, String currentStrategy, BacktestExitStyle exitStyle) {
        List<StrategyScore> results = new ArrayList<>();

        for (String strategy : STRATEGIES) {
            BacktestService.BacktestResult bt = backtestService.run(ticker, 180, BacktestMode.ALL, strategy, exitStyle);
            results.add(new StrategyScore(
                    strategy,
                    bt.total,
                    round2(bt.winRate),
                    round2(bt.expectancy),
                    round2(bt.totalOptPnl),
                    strategy.equalsIgnoreCase(currentStrategy),
                    bt.total >= MIN_BEST_STRATEGY_TRADES
            ));
        }

        results.sort(Comparator
                .comparing(StrategyScore::eligible, Comparator.<Boolean>reverseOrder())
                .thenComparing(StrategyScore::expectancy, Comparator.reverseOrder())
                .thenComparing(StrategyScore::totalTrades, Comparator.reverseOrder()));
        return results;
    }

    private OutcomeSummary summarizeOutcomes(BacktestService.BacktestResult bt) {
        Map<String, Integer> executed = new LinkedHashMap<>();
        Map<String, Integer> filtered = new LinkedHashMap<>();
        int confCapFiltered = 0;
        int timeoutCount = 0;

        for (BacktestService.TradeResult trade : bt.trades) {
            String normalized = normalizeOutcome(trade.outcome());
            if (isFilteredOutcome(trade.outcome())) {
                filtered.merge(normalized, 1, Integer::sum);
            } else {
                executed.merge(normalized, 1, Integer::sum);
            }
            if (trade.outcome() != null && trade.outcome().toUpperCase(Locale.ROOT).contains("TIMEOUT")) {
                timeoutCount++;
            }
            if (trade.outcome() != null && trade.outcome().toUpperCase(Locale.ROOT).contains("CONF_CAP")) {
                confCapFiltered++;
            }
        }

        double timeoutRate = bt.total > 0 ? round2(timeoutCount * 100.0 / bt.total) : 0;
        int allFiltered = bt.newsFiltered + bt.ctxFiltered + bt.qualityFiltered + confCapFiltered;
        double confCapReliance = allFiltered > 0 ? round2(confCapFiltered * 100.0 / allFiltered) : 0;

        return new OutcomeSummary(
                topStats(executed),
                topStats(filtered),
                timeoutRate,
                confCapReliance
        );
    }

    private List<FailureStat> topStats(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(e -> new FailureStat(e.getKey(), e.getValue()))
                .toList();
    }

    private boolean isFilteredOutcome(String outcome) {
        if (outcome == null) return false;
        String normalized = outcome.toUpperCase(Locale.ROOT);
        return normalized.contains("FILTERED") || normalized.contains("CONF_CAP");
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
        if ("retest".equals(recommendation) && bestStrategy.eligible() && !bestStrategy.strategy().equalsIgnoreCase(currentStrategy)) {
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
            String exitStyle,
            String exitStyleLabel,
            List<TickerResearch> tickers
    ) {}

    public record ResearchStatus(
            String exitStyle,
            String exitStyleLabel,
            boolean running,
            ZonedDateTime lastStartedAt,
            ZonedDateTime lastCompletedAt,
            String lastError,
            boolean hasCachedReport,
            ResearchReport report,
            long elapsedSeconds
    ) {}

    public record ResearchComparison(
            boolean classicReady,
            boolean liveParityReady,
            boolean comparisonReady,
            int tickerCount,
            int improvedCount,
            int declinedCount,
            List<TickerComparison> tickers
    ) {}

    public record TickerComparison(
            String ticker,
            String classicRecommendation,
            String liveRecommendation,
            double classicWinRate,
            double liveWinRate,
            double winRateDelta,
            double classicExpectancy,
            double liveExpectancy,
            double expectancyDelta,
            double classicTimeoutRate,
            double liveTimeoutRate,
            double timeoutDelta
    ) {}

    public record TickerResearch(
            String ticker,
            String currentStrategy,
            String bestStrategy,
            String recommendation,
            double score,
            String summary,
            String exitStyle,
            int totalTrades365,
            double winRate365,
            double expectancy365,
            double optPnl365,
            double timeoutRate365,
            double confCapReliance365,
            int newsFiltered365,
            int ctxFiltered365,
            int qualityFiltered365,
            List<WindowStat> windows,
            List<StrategyScore> strategies,
            List<FailureStat> executedOutcomes,
            List<FailureStat> filteredOutcomes
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
            boolean current,
            boolean eligible
    ) {}

    public record FailureStat(String outcome, int count) {}

    public record OutcomeSummary(
            List<FailureStat> executed,
            List<FailureStat> filtered,
            double timeoutRate,
            double confCapReliance
    ) {}

    private static class ResearchRunState {
        private volatile boolean running;
        private volatile ZonedDateTime lastStartedAt;
        private volatile ZonedDateTime lastCompletedAt;
        private volatile String lastError;
        private volatile ResearchReport lastReport;
    }
}
