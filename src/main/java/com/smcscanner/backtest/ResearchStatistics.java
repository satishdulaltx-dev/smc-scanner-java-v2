package com.smcscanner.backtest;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Statistical guardrails for interpreting controlled development-period experiments. */
public final class ResearchStatistics {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int BOOTSTRAP_SAMPLES = 10_000;
    private static final long BOOTSTRAP_SEED = 20_260_909L;

    private ResearchStatistics() {}

    public static Summary summarize(List<BacktestService.TradeResult> trades) {
        List<Double> multiples = trades.stream()
                .map(BacktestService.TradeResult::riskMultiple)
                .toList();
        if (multiples.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0,
                    0, 0, "INSUFFICIENT_SAMPLE",
                    "No qualifying trades. This experiment cannot establish an edge.");
        }

        double total = multiples.stream().mapToDouble(Double::doubleValue).sum();
        double gains = multiples.stream().filter(v -> v > 0).mapToDouble(Double::doubleValue).sum();
        double losses = -multiples.stream().filter(v -> v < 0).mapToDouble(Double::doubleValue).sum();
        double profitFactor = losses > 0 ? gains / losses : (gains > 0 ? 999.0 : 0.0);
        double[] interval = bootstrapMeanInterval(multiples);

        Map<YearMonth, Double> monthly = new LinkedHashMap<>();
        for (BacktestService.TradeResult trade : trades) {
            YearMonth month = YearMonth.from(Instant.ofEpochMilli(trade.entryEpochMs()).atZone(ET));
            monthly.merge(month, trade.riskMultiple(), Double::sum);
        }
        int positiveMonths = (int) monthly.values().stream().filter(v -> v > 0).count();

        String verdict;
        String explanation;
        if (interval[1] <= 0) {
            verdict = "REJECTED";
            explanation = "The 95% range is entirely non-positive. Keep this rule out of live trading.";
        } else if (trades.size() < 30) {
            verdict = "INSUFFICIENT_SAMPLE";
            explanation = "Fewer than 30 trades. Treat the apparent result as unproven.";
        } else if (interval[0] > 0 && profitFactor > 1
                && positiveMonths >= Math.ceil(monthly.size() * 0.60)) {
            verdict = "PROMISING_TRAINING_ONLY";
            explanation = "Positive development evidence. Freeze the rule before opening untouched validation.";
        } else {
            verdict = "INCONCLUSIVE";
            explanation = "The 95% range crosses zero or monthly results are unstable. Do not promote this rule.";
        }

        return new Summary(trades.size(), total, total / trades.size(), profitFactor,
                interval[0], interval[1], positiveMonths, monthly.size(), verdict, explanation);
    }

    private static double[] bootstrapMeanInterval(List<Double> values) {
        SplittableRandom random = new SplittableRandom(BOOTSTRAP_SEED);
        List<Double> means = new ArrayList<>(BOOTSTRAP_SAMPLES);
        for (int sample = 0; sample < BOOTSTRAP_SAMPLES; sample++) {
            double total = 0;
            for (int i = 0; i < values.size(); i++) total += values.get(random.nextInt(values.size()));
            means.add(total / values.size());
        }
        Collections.sort(means);
        return new double[]{means.get(249), means.get(9_749)};
    }

    public record Summary(int trades, double totalR, double meanR, double profitFactor,
                          double ciLowR, double ciHighR, int positiveMonths, int activeMonths,
                          String verdict, String explanation) {}
}
