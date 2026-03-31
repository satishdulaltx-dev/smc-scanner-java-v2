package com.smcscanner.backtest;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.TickerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Runs the existing BacktestService with different parameter combinations
 * to find the optimal profile settings for each ticker.
 *
 * Tests: strategy type × minConfidence × slAtrMult × tpRrRatio
 * Returns ranked results sorted by expectancy.
 */
@Service
public class ProfileOptimizer {
    private static final Logger log = LoggerFactory.getLogger(ProfileOptimizer.class);

    private final BacktestService backtestService;
    private final ScannerConfig   config;

    public ProfileOptimizer(BacktestService backtestService, ScannerConfig config) {
        this.backtestService = backtestService;
        this.config = config;
    }

    /**
     * Run optimization for a single ticker across multiple parameter combos.
     * @param ticker    ticker symbol
     * @param days      lookback days for each backtest run
     * @return sorted list of results (best expectancy first)
     */
    public OptimizeResult optimize(String ticker, int days) {
        TickerProfile original = config.getTickerProfile(ticker);
        String currentStrategy = original.getStrategyType();

        // Parameter grid — test strategies that make sense for the ticker
        String[] strategies = {"smc", "vwap", "breakout", "keylevel"};
        int[] minConfs = {62, 68, 72, 75, 80};
        double[] slMults = {0.4, 0.7, 1.0, 1.5};
        double[] tpRatios = {1.0, 1.5, 2.0};

        List<ParamResult> results = new ArrayList<>();

        for (String strat : strategies) {
            for (int mc : minConfs) {
                for (double sl : slMults) {
                    for (double tp : tpRatios) {
                        try {
                            // Build test profile
                            TickerProfile test = new TickerProfile();
                            test.setTicker(ticker);
                            test.setStrategyType(strat);
                            test.setMinConfidence(mc);
                            test.setSlAtrMult(sl);
                            test.setTpRrRatio(tp);
                            test.setSkip(false);
                            // Carry over non-tuned settings from original
                            test.setMinFvgPct(original.getMinFvgPct());
                            test.setDispAtrMult(original.getDispAtrMult());
                            test.setMinVolMult(original.getMinVolMult());
                            test.setIntradayRsGate(original.isIntradayRsGate() ? Boolean.TRUE : null);

                            // Temporarily swap profile
                            config.setProfileOverride(ticker, test);

                            // Run backtest with these params
                            BacktestService.BacktestResult bt = backtestService.run(ticker, days);

                            // Only include combos that produced at least 3 executed trades
                            int executed = bt.wins + bt.losses + bt.beStops;
                            if (executed >= 3) {
                                results.add(new ParamResult(
                                        strat, mc, sl, tp,
                                        executed,
                                        bt.wins, bt.losses, bt.beStops,
                                        bt.winRate, bt.expectancy,
                                        bt.avgWinPct, bt.avgLossPct,
                                        bt.totalOptPnl, bt.optExpectancy
                                ));
                            }
                        } catch (Exception e) {
                            log.debug("Optimizer skip {}/{}/mc{}/sl{}/tp{}: {}",
                                    ticker, strat, mc, sl, tp, e.getMessage());
                        }
                    }
                }
            }
        }

        // Restore original profile
        config.restoreProfile(ticker, original);

        // Sort by expectancy descending, then by trade count descending (prefer more data)
        results.sort((a, b) -> {
            int cmp = Double.compare(b.expectancy, a.expectancy);
            return cmp != 0 ? cmp : Integer.compare(b.totalTrades, a.totalTrades);
        });

        return new OptimizeResult(ticker, days, currentStrategy, results);
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record ParamResult(
            String strategy,
            int minConfidence,
            double slAtrMult,
            double tpRrRatio,
            int totalTrades,
            int wins, int losses, int beStops,
            double winRate,
            double expectancy,
            double avgWinPct, double avgLossPct,
            double optTotalPnl, double optExpectancy
    ) {}

    public record OptimizeResult(
            String ticker,
            int lookbackDays,
            String currentStrategy,
            List<ParamResult> results
    ) {}
}
