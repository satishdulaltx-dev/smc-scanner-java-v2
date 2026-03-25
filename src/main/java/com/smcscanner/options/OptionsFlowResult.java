package com.smcscanner.options;

/**
 * Result of options flow analysis for a single underlying ticker.
 * Captures call/put volume ratios, open interest, unusual activity,
 * and max pain to determine institutional directional bias.
 */
public record OptionsFlowResult(
        String ticker,
        double underlyingPrice,
        long   callVolume,
        long   putVolume,
        long   callOI,
        long   putOI,
        double pcRatioVol,        // callVol / putVol — >2.0 = bullish, <0.5 = bearish
        double pcRatioOI,         // callOI  / putOI
        String flowDirection,     // "BULLISH" | "BEARISH" | "NEUTRAL"
        boolean unusualActivity,  // volume > 3× OI on any near-money strike
        double maxPainStrike,     // price where most options expire worthless
        double topCallStrike,     // strike with highest call volume
        double topPutStrike,      // strike with highest put volume
        int    totalContracts     // total options contracts traded today
) {
    public static final OptionsFlowResult NONE =
            new OptionsFlowResult("", 0, 0, 0, 0, 0, 1.0, 1.0, "NEUTRAL", false, 0, 0, 0, 0);

    /** Flow aligns with trade direction — bullish flow + long, or bearish flow + short. */
    public boolean isAligned(String direction) {
        return ("long".equals(direction) && "BULLISH".equals(flowDirection))
            || ("short".equals(direction) && "BEARISH".equals(flowDirection));
    }

    /** Flow conflicts — bullish flow + short, or bearish flow + long. */
    public boolean isConflicting(String direction) {
        return ("long".equals(direction) && "BEARISH".equals(flowDirection))
            || ("short".equals(direction) && "BULLISH".equals(flowDirection));
    }

    /** Confidence adjustment based on options flow alignment. */
    public int confidenceDelta(String direction) {
        if (unusualActivity && isAligned(direction))    return +12;
        if (isAligned(direction))                       return +8;
        if (unusualActivity && isConflicting(direction)) return -15;
        if (isConflicting(direction))                    return -10;
        return 0;
    }

    /** Human-readable label for Discord / dashboard. */
    public String label() {
        if (totalContracts == 0) return null;
        String ratio = String.format("%.1f", pcRatioVol);
        if (unusualActivity && "BULLISH".equals(flowDirection))
            return "🟢 UNUSUAL CALL SWEEP " + ratio + ":1 C/P";
        if (unusualActivity && "BEARISH".equals(flowDirection))
            return "🔴 UNUSUAL PUT SWEEP 1:" + String.format("%.1f", 1.0 / Math.max(pcRatioVol, 0.01)) + " C/P";
        if ("BULLISH".equals(flowDirection))
            return "📈 Bullish flow (" + ratio + ":1 C/P)";
        if ("BEARISH".equals(flowDirection))
            return "📉 Bearish flow (1:" + String.format("%.1f", 1.0 / Math.max(pcRatioVol, 0.01)) + " C/P)";
        return "⚪ Neutral flow";
    }

    public boolean hasData() { return totalContracts > 0; }
}
