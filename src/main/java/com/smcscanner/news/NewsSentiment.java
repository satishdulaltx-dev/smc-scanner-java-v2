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
     * For "breakout" (ORB) strategy, news sentiment is treated differently:
     * A technical breakout AGAINST news sentiment is a high-conviction divergence —
     * "smart money" is breaking price in one direction despite the news narrative.
     * This is not a reason to penalise — it's a stronger signal.
     *
     * Example: Bullish news + ORB SHORT → price breaking down DESPITE bullish news
     *   = institutions distributing into retail buying = high-conviction short.
     *   Instead of -15, apply a small penalty only, or even boost confidence.
     *
     * For all other strategies (SMC, VWAP, KeyLevel), news conflicts are penalised
     * normally because those strategies rely on order-flow alignment with sentiment.
     */
    public int confidenceDelta(String tradeDirection, String strategyType) {
        if (!hasNews()) return 0;

        boolean isBreakout = "breakout".equals(strategyType);

        if (isConflicting(tradeDirection)) {
            if (isBreakout) {
                // ORB breaking AGAINST news = divergence = smart money signal.
                // Apply a small penalty only (not -15) — the divergence itself is informative.
                // Strong divergence (e.g. strong bullish news + SHORT breakdown): minimal penalty.
                return netScore <= -0.6 || netScore >= 0.6 ? -3 : -5;
            }
            // Non-breakout: full conflict penalty
            return netScore <= -0.6 || netScore >= 0.6 ? -15 : -8;
        }
        if (isAligned(tradeDirection)) {
            return netScore >= 0.6 || netScore <= -0.6 ? +8 : +5;
        }
        return 0;
    }
}
