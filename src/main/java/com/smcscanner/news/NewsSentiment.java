package com.smcscanner.news;

/**
 * Per-ticker news sentiment result from Polygon's insights API.
 *
 * netScore range: -1.0 (all negative) → 0.0 (neutral) → +1.0 (all positive)
 *
 * Interpretation helpers:
 *   isConflicting("long")  → bearish news when going long  → warn / reduce confidence
 *   isConflicting("short") → bullish news when going short → warn / reduce confidence
 *   isAligned("long")      → bullish news when going long  → confidence boost
 */
public record NewsSentiment(
        String ticker,
        int    positiveCount,
        int    negativeCount,
        int    neutralCount,
        double netScore,       // -1.0 .. +1.0
        String headline        // most relevant recent headline, may be null
) {

    /** Returned when no recent news is found or API is unavailable. */
    public static final NewsSentiment NONE = new NewsSentiment("", 0, 0, 0, 0.0, null);

    public boolean hasNews()  { return positiveCount + negativeCount + neutralCount > 0; }

    /** Strong bearish signal (score ≤ −0.4): at least 40% of articles are negative-majority. */
    public boolean isBearish() { return netScore <= -0.4; }

    /** Strong bullish signal (score ≥ +0.4). */
    public boolean isBullish() { return netScore >= 0.4; }

    /**
     * Returns true when news direction conflicts with the intended trade direction.
     * e.g. strongly bullish news → don't short; strongly bearish news → don't long.
     */
    public boolean isConflicting(String tradeDirection) {
        if ("long".equals(tradeDirection)  && isBearish()) return true;
        if ("short".equals(tradeDirection) && isBullish()) return true;
        return false;
    }

    /** Returns true when news direction supports the trade direction. */
    public boolean isAligned(String tradeDirection) {
        if ("long".equals(tradeDirection)  && isBullish()) return true;
        if ("short".equals(tradeDirection) && isBearish()) return true;
        return false;
    }

    /** Short label for the Discord embed. */
    public String label() {
        if (!hasNews()) return null;
        if (netScore <= -0.6) return "🔴 Strong bearish news";
        if (netScore <= -0.4) return "🟠 Bearish news";
        if (netScore >= 0.6)  return "🟢 Strong bullish news";
        if (netScore >= 0.4)  return "🟡 Bullish news";
        return "⚪ Mixed/neutral news";
    }

    /** Confidence delta to apply based on news alignment with trade direction. */
    public int confidenceDelta(String tradeDirection) {
        return confidenceDelta(tradeDirection, "smc");
    }

    /**
     * Confidence delta with strategy-awareness.
     *
     * "Buy the rumor, sell the news" principle:
     * Published/confirmed bullish news is already priced in — institutions sell into
     * retail enthusiasm on news days. Aligned news is therefore neutral (0), not a boost.
     *
     * For "breakout" (ORB) strategy, divergence logic applies:
     * - ORB breaking AGAINST news = smart money distributing/accumulating = high-conviction signal.
     * - Small penalty only (not -15) for breakout-vs-news conflict.
     *
     * For all other strategies (SMC, VWAP, KeyLevel):
     * - Conflicting news: full penalty (bearish news + long = bad setup).
     * - Aligned news: 0 — published news is already in the price, don't chase it.
     */
    public int confidenceDelta(String tradeDirection, String strategyType) {
        if (!hasNews()) return 0;

        boolean isBreakout = "breakout".equals(strategyType);

        if (isConflicting(tradeDirection)) {
            if (isBreakout) {
                // ORB breaking AGAINST news = divergence = smart money signal.
                // Minimal penalty — the divergence itself is informative.
                return netScore <= -0.6 || netScore >= 0.6 ? -3 : -5;
            }
            // Non-breakout: full conflict penalty
            return netScore <= -0.6 || netScore >= 0.6 ? -15 : -8;
        }
        // Aligned news: neutral (0) — "sell the news" effect means published positive
        // news is already priced in. No confidence boost for chasing confirmed news.
        return 0;
    }
}
