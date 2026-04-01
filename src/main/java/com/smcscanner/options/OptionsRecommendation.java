package com.smcscanner.options;

/**
 * Recommended options contract for a trade setup.
 * Selected based on delta sweet spot (0.35–0.55), DTE (7–21 days),
 * and liquidity (volume + OI).
 */
public record OptionsRecommendation(
        String contractTicker,    // e.g. "O:AAPL260404C00255000"
        String contractType,      // "call" | "put"
        double strike,
        String expirationDate,
        int    dte,               // days to expiration
        double estimatedPremium,  // last close price of the contract
        double delta,
        double gamma,
        double theta,
        double iv,                // implied volatility (decimal)
        int    ivPercentile,      // 0-100, where 100 = most expensive historically
        double breakEvenPrice,    // strike ± premium
        double premiumAtTP,       // estimated premium if underlying reaches TP
        double premiumAtSL,       // estimated premium if underlying hits SL
        double profitPerContract, // (premiumAtTP - premium) × 100
        double lossPerContract,   // (premium - premiumAtSL) × 100
        double optionsRR,         // profitPerContract / lossPerContract
        int    suggestedContracts, // based on ~$500 budget
        String greeksWarning      // risk warnings from Greeks analysis (null = none)
) {
    public static final OptionsRecommendation NONE =
            new OptionsRecommendation(null, null, 0, null, 0, 0, 0, 0, 0, 0, 50,
                    0, 0, 0, 0, 0, 0, 0, null);

    public boolean hasData() { return contractTicker != null && estimatedPremium > 0; }

    /**
     * Estimates the option premium after the underlying moves by a given amount,
     * using a second-order Taylor expansion (delta + gamma) minus theta decay.
     *
     * premiumNew ≈ premium + delta×move + 0.5×gamma×move² - theta×days
     *
     * @param underlyingMove signed price change in the underlying
     * @param holdDays       how many days until exit
     * @return estimated new premium (floored at 0.01)
     */
    /** Generate risk warnings based on Greeks. */
    public static String computeGreeksWarning(double theta, double gamma, double vega, double iv, int ivPercentile) {
        StringBuilder warn = new StringBuilder();
        if (Math.abs(theta) > 0.10)
            warn.append("⚠️ High theta decay ($").append(String.format("%.2f", Math.abs(theta) * 100)).append("/day per contract) | ");
        if (Math.abs(gamma) > 0.15)
            warn.append("⚠️ High gamma (").append(String.format("%.3f", gamma)).append(") — position delta shifts fast | ");
        if (vega > 0 && ivPercentile > 70)
            warn.append("⚠️ Long vega + high IV — IV crush risk | ");
        if (ivPercentile > 80)
            warn.append("🔴 IV Rank >80% — consider selling premium instead of buying | ");
        return warn.length() > 0 ? warn.toString().replaceAll(" \\| $", "") : null;
    }

    public static double estimatePremium(double currentPremium, double delta, double gamma,
                                          double theta, double underlyingMove, double holdDays) {
        double newPremium = currentPremium
                + delta * underlyingMove
                + 0.5 * gamma * underlyingMove * underlyingMove
                - Math.abs(theta) * holdDays;
        return Math.max(0.01, newPremium);
    }
}
