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
 * Scalp-aware behavior:
 * - Momentum/scalp profiles use SCALP backtest mode (max ~15 min hold)
 * - Strategy candidates shift toward fast reaction setups (scalp/keylevel/vwap/vsqueeze/idiv/breakout)
 * - Parameter grids tighten around quick-entry / quick-exit combos
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
        boolean scalpMode = isScalpProfile(original);
        BacktestMode mode = scalpMode ? BacktestMode.SCALP : BacktestMode.INTRADAY;
        List<ParamResult> results = new ArrayList<>();

        // ── Phase 1: Screen strategies with default params ──────────────────
        String[] strategies = scalpMode
                ? new String[]{"scalp", "keylevel", "vwap", "vsqueeze", "idiv", "breakout"}
                : new String[]{"smc", "vwap", "breakout", "keylevel"};
        List<String> viableStrategies = new ArrayList<>();

        for (String strat : strategies) {
            try {
                int defaultMc = scalpMode ? 74 : 65;
                double defaultSl = scalpMode ? 0.4 : 0.5;
                double defaultTp = scalpMode ? 1.0 : 1.5;
                TickerProfile test = buildProfile(ticker, original, strat, defaultMc, defaultSl, defaultTp);
                config.setProfileOverride(ticker, test);
                BacktestService.BacktestResult bt = backtestService.run(ticker, days, mode);
                int executed = bt.wins + bt.losses + bt.beStops;

                // Record this default-params result
                if (executed >= 1) {
                    results.add(toParamResult(strat, defaultMc, defaultSl, defaultTp, bt));
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
        int[] minConfs = scalpMode ? new int[]{68, 74, 80, 86} : new int[]{62, 70, 78};
        double[] tpRatios = scalpMode ? new double[]{0.75, 1.0, 1.25} : new double[]{1.0, 1.5, 2.0};
        double[] slMults = scalpMode ? new double[]{0.3, 0.4, 0.5, 0.6} : new double[]{0.4, 1.0};
        int defaultMc = scalpMode ? 74 : 65;
        double defaultTp = scalpMode ? 1.0 : 1.5;
        double defaultSl = scalpMode ? 0.4 : 0.5;

        for (String strat : viableStrategies) {
            for (int mc : minConfs) {
                for (double tp : tpRatios) {
                    for (double sl : slMults) {
                        // Skip the default combo we already tested in phase 1
                        if (mc == defaultMc && tp == defaultTp && sl == defaultSl) continue;
                        try {
                            TickerProfile test = buildProfile(ticker, original, strat, mc, sl, tp);
                            config.setProfileOverride(ticker, test);
                            BacktestService.BacktestResult bt = backtestService.run(ticker, days, mode);
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
            int minTradesCmp = Integer.compare(b.totalTrades, a.totalTrades);
            if ((a.totalTrades >= 6) != (b.totalTrades >= 6)) {
                return Boolean.compare(b.totalTrades >= 6, a.totalTrades >= 6);
            }
            if (scalpMode) {
                int wrCmp = Double.compare(b.winRate, a.winRate);
                if (wrCmp != 0) return wrCmp;
            }
            int cmp = Double.compare(b.expectancy, a.expectancy);
            return cmp != 0 ? cmp : minTradesCmp;
        });

        return new OptimizeResult(ticker, days, currentStrategy, results);
    }

    private boolean isScalpProfile(TickerProfile profile) {
        if (profile == null) return false;
        String strat = profile.getStrategyType();
        boolean fastStrategy = Set.of("scalp", "keylevel", "vwap", "vsqueeze", "idiv", "breakout").contains(strat);
        boolean fastTarget = profile.getTpRrRatio() != null && profile.getTpRrRatio() <= 1.0;
        String note = profile.getNote();
        boolean explicitScalp = note != null && note.toLowerCase(Locale.ROOT).contains("scalp");
        return explicitScalp || (fastStrategy && fastTarget);
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
