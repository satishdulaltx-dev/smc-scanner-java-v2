package com.smcscanner.filter;

import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Stateless signal quality scorer — combines three orthogonal dimensions into
 * a single confidence delta that stacks with news and market-context adjustments.
 *
 * ┌──────────────┬─────────────────────────────────────────────────────────────┐
 * │ Dimension    │ Logic                                                       │
 * ├──────────────┼─────────────────────────────────────────────────────────────┤
 * │ R:R Ratio    │ < 1.0 → −20  │ 1.0–1.5 → −10  │ 1.5–2.0 → −5  │ ≥3 → +5  │
 * │ Time of Day  │ 9:30–9:45 open chop → −8                                   │
 * │              │ 11:30–13:30 lunch drift → −5                                │
 * │              │ 15:30–16:00 EOD erratic → −8                                │
 * │              │ Prime windows (9:45–11:30, 13:30–15:30) → 0                 │
 * │ Loss Streak  │ 2 consecutive → −8  │ 3 → −15  │ 5+ → −25 (near-block)    │
 * └──────────────┴─────────────────────────────────────────────────────────────┘
 *
 * Used by both ScannerService (live) and BacktestService (historical).
 * The loss-streak count is provided by the caller so this class stays stateless.
 */
@Service
public class SignalQualityFilter {
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // ── Time window boundaries (ET) ───────────────────────────────────────────
    private static final LocalTime RTH_OPEN     = LocalTime.of(9, 30);
    private static final LocalTime PRIME_1_START= LocalTime.of(9, 45);   // after choppy open
    private static final LocalTime LUNCH_START  = LocalTime.of(11, 30);
    private static final LocalTime LUNCH_END    = LocalTime.of(13, 30);
    private static final LocalTime EOD_RISKY    = LocalTime.of(15, 30);  // last 30 min
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    /**
     * Computes the combined quality delta for a setup.
     *
     * @param setup              the candidate trade setup
     * @param barEpochMs         epoch-ms of the entry bar (for time-of-day check)
     * @param consecutiveLosses  recent consecutive losses on this ticker (0 = none)
     */
    public int computeDelta(TradeSetup setup, long barEpochMs, int consecutiveLosses) {
        return rrDelta(setup) + timeDelta(barEpochMs) + streakDelta(consecutiveLosses);
    }

    /**
     * Human-readable label summarising non-zero quality factors.
     * Returns null when all factors are neutral (nothing to show).
     */
    public String buildLabel(TradeSetup setup, long barEpochMs, int consecutiveLosses) {
        StringBuilder sb = new StringBuilder();

        int rr = rrDelta(setup);
        if (rr != 0)
            append(sb, String.format("R:R %.1f (%+d)", setup.rrRatio(), rr));

        int t = timeDelta(barEpochMs);
        if (t != 0)
            append(sb, "time-of-day (" + timeWindow(barEpochMs) + ") " + sign(t));

        int s = streakDelta(consecutiveLosses);
        if (s != 0)
            append(sb, consecutiveLosses + "-loss streak " + sign(s));

        return sb.length() > 0 ? sb.toString() : null;
    }

    // ── R:R scoring ───────────────────────────────────────────────────────────

    private int rrDelta(TradeSetup setup) {
        double rr = setup.rrRatio();
        if (rr < 1.0) return -20; // losing proposition — never worth taking
        if (rr < 1.5) return -10; // marginal
        if (rr < 2.0) return  -5; // acceptable, slight discount
        if (rr >= 3.0) return  +5; // excellent setup — small boost
        return 0;                  // 2.0–3.0: good, no adjustment
    }

    // ── Time-of-day scoring ───────────────────────────────────────────────────

    private int timeDelta(long epochMs) {
        LocalTime t = Instant.ofEpochMilli(epochMs).atZone(ET).toLocalTime();

        // First 15 min: auction-driven gaps, wide spreads, stop hunts
        if (inWindow(t, RTH_OPEN, PRIME_1_START))   return -8;
        // Lunch: low volume, choppy price action, mean-reversion traps
        if (inWindow(t, LUNCH_START, LUNCH_END))     return -5;
        // Last 30 min: EOD covering, erratic flows, stops run
        if (inWindow(t, EOD_RISKY,  MARKET_CLOSE))   return -8;

        return 0; // prime windows: 9:45–11:30 and 13:30–15:30
    }

    private String timeWindow(long epochMs) {
        LocalTime t = Instant.ofEpochMilli(epochMs).atZone(ET).toLocalTime();
        if (inWindow(t, RTH_OPEN, PRIME_1_START))   return "open";
        if (inWindow(t, LUNCH_START, LUNCH_END))     return "lunch";
        if (inWindow(t, EOD_RISKY,  MARKET_CLOSE))   return "EOD";
        return "prime";
    }

    // ── Loss-streak scoring ───────────────────────────────────────────────────

    private int streakDelta(int consecutive) {
        if (consecutive >= 5) return -25; // regime breakdown — near-block
        if (consecutive >= 3) return -15; // strong suppression
        if (consecutive >= 2) return  -8; // early warning
        return 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean inWindow(LocalTime t, LocalTime from, LocalTime to) {
        return !t.isBefore(from) && t.isBefore(to);
    }
    private void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(" | ");
        sb.append(s);
    }
    private String sign(int v) { return v > 0 ? "(+" + v + ")" : "(" + v + ")"; }
}
