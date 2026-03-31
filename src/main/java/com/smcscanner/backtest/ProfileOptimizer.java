package com.smcscanner.backtest;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.TickerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Two-phase optimizer: first finds which strategies produce trades,
 * then tunes parameters only for viable strategies.
 *
 * Phase 1: Test each strategy with default params (4 runs)
 * Phase 2: For strategies that produced 2+ trades, test minConf × tpRrRatio (6 combos each)
 * Total: ~4 + ~12 = ~16 backtests instead of 240
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

    public OptimizeResult optimize(String ticker, int days) {
        TickerProfile original = config.getTickerProfile(ticker);
        String currentStrategy = original.getStrategyType();
        List<ParamResult> results = new ArrayList<>();

        // ── Phase 1: Screen strategies with default params ──────────────────
        String[] strategies = {"smc", "vwap", "breakout", "keylevel"};
        List<String> viableStrategies = new ArrayList<>();

        for (String strat : strategies) {
            try {
                TickerProfile test = buildProfile(ticker, original, strat, 65, 0.5, 1.5);
                config.setProfileOverride(ticker, test);
                BacktestService.BacktestResult bt = backtestService.run(ticker, days);
                int executed = bt.wins + bt.losses + bt.beStops;

                // Record this default-params result
                if (executed >= 1) {
                    results.add(toParamResult(strat, 65, 0.5, 1.5, bt));
                }
                // Strategy is viable if it produced any executed trades
                if (executed >= 2) {
                    viableStrategies.add(strat);
                }
                log.info("Optimizer phase1 {}/{}: {} exec, {}% WR, {}% exp",
                        ticker, strat, executed, bt.winRate, bt.expectancy);
            } catch (Exception e) {
                log.debug("Optimizer phase1 skip {}/{}: {}", ticker, strat, e.getMessage());
            }
        }

        // ── Phase 2: Tune viable strategies ─────────────────────────────────
        int[] minConfs = {62, 70, 78};
        double[] tpRatios = {1.0, 1.5, 2.0};
        double[] slMults = {0.4, 1.0};

        for (String strat : viableStrategies) {
            for (int mc : minConfs) {
                for (double tp : tpRatios) {
                    for (double sl : slMults) {
                        // Skip the default combo we already tested in phase 1
                        if (mc == 65 && tp == 1.5 && sl == 0.5) continue;
                        try {
                            TickerProfile test = buildProfile(ticker, original, strat, mc, sl, tp);
                            config.setProfileOverride(ticker, test);
                            BacktestService.BacktestResult bt = backtestService.run(ticker, days);
                            int executed = bt.wins + bt.losses + bt.beStops;
                            if (executed >= 2) {
                                results.add(toParamResult(strat, mc, sl, tp, bt));
                            }
                        } catch (Exception e) {
                            log.debug("Optimizer phase2 skip {}/{}/mc{}/tp{}: {}",
                                    ticker, strat, mc, tp, e.getMessage());
                        }
                    }
                }
            }
        }

        // Restore original profile
        config.restoreProfile(ticker, original);

        // Sort by expectancy descending, then by trade count (prefer more data)
        results.sort((a, b) -> {
            int cmp = Double.compare(b.expectancy, a.expectancy);
            return cmp != 0 ? cmp : Integer.compare(b.totalTrades, a.totalTrades);
        });

        return new OptimizeResult(ticker, days, currentStrategy, results);
    }

    private TickerProfile buildProfile(String ticker, TickerProfile original,
                                        String strat, int mc, double sl, double tp) {
        TickerProfile test = new TickerProfile();
        test.setTicker(ticker);
        test.setStrategyType(strat);
        test.setMinConfidence(mc);
        test.setSlAtrMult(sl);
        test.setTpRrRatio(tp);
        test.setSkip(false);
        test.setMinFvgPct(original.getMinFvgPct());
        test.setDispAtrMult(original.getDispAtrMult());
        test.setMinVolMult(original.getMinVolMult());
        test.setIntradayRsGate(original.isIntradayRsGate() ? Boolean.TRUE : null);
        return test;
    }

    private ParamResult toParamResult(String strat, int mc, double sl, double tp,
                                       BacktestService.BacktestResult bt) {
        return new ParamResult(strat, mc, sl, tp,
                bt.wins + bt.losses + bt.beStops,
                bt.wins, bt.losses, bt.beStops,
                bt.winRate, bt.expectancy,
                bt.avgWinPct, bt.avgLossPct,
                bt.totalOptPnl, bt.optExpectancy);
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
