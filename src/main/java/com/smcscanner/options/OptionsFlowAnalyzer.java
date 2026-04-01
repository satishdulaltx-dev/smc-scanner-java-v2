package com.smcscanner.options;

import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes options flow (call/put volume, OI, unusual activity, max pain)
 * and recommends the best contract for a given trade setup.
 *
 * This is the core intelligence layer that turns raw options chain data
 * into actionable trading signals.
 */
@Service
public class OptionsFlowAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(OptionsFlowAnalyzer.class);

    private final OptionsDataService dataService;
    private final PolygonClient      polygonClient;

    public OptionsFlowAnalyzer(OptionsDataService dataService, PolygonClient polygonClient) {
        this.dataService = dataService;
        this.polygonClient = polygonClient;
    }

    // ── Flow Analysis ───────────────────────────────────────────────────────────

    /**
     * Analyzes options flow for a ticker: call/put volume ratio, OI skew,
     * unusual activity detection, and max pain calculation.
     *
     * @param ticker       underlying symbol (e.g. "AAPL")
     * @param currentPrice latest stock price
     * @return flow analysis result, or NONE if no data
     */
    public OptionsFlowResult analyzeFlow(String ticker, double currentPrice) {
        if (ticker.startsWith("X:")) return OptionsFlowResult.NONE; // no options on crypto

        // Fetch near-the-money contracts expiring in 5-21 days
        // Strike range: ±10% of current price to capture most liquid contracts
        double strikeRange = currentPrice * 0.10;
        List<OptionsDataService.ContractData> chain =
                dataService.getOptionsChain(ticker, currentPrice, strikeRange, 5, 21);

        if (chain.isEmpty()) {
            log.debug("{}: no options chain data available", ticker);
            return OptionsFlowResult.NONE;
        }

        // Separate calls and puts
        List<OptionsDataService.ContractData> calls = chain.stream()
                .filter(c -> "call".equals(c.contractType())).collect(Collectors.toList());
        List<OptionsDataService.ContractData> puts = chain.stream()
                .filter(c -> "put".equals(c.contractType())).collect(Collectors.toList());

        // Aggregate volume and OI
        long callVol = calls.stream().mapToLong(OptionsDataService.ContractData::volume).sum();
        long putVol  = puts.stream().mapToLong(OptionsDataService.ContractData::volume).sum();
        long callOI  = calls.stream().mapToLong(OptionsDataService.ContractData::openInterest).sum();
        long putOI   = puts.stream().mapToLong(OptionsDataService.ContractData::openInterest).sum();
        int  totalContracts = (int)(callVol + putVol);

        // P/C ratios
        double pcVol = putVol > 0 ? (double) callVol / putVol : (callVol > 0 ? 10.0 : 1.0);
        double pcOI  = putOI  > 0 ? (double) callOI  / putOI  : (callOI  > 0 ? 10.0 : 1.0);

        // Flow direction: >2:1 call/put = bullish, <0.5 = bearish
        String direction = pcVol >= 2.0 ? "BULLISH"
                         : pcVol <= 0.5 ? "BEARISH"
                         : "NEUTRAL";

        // Unusual activity: any near-money contract with volume > 3× its OI
        boolean unusual = chain.stream().anyMatch(c -> {
            double distance = Math.abs(c.strike() - currentPrice) / currentPrice;
            return distance < 0.05  // within 5% of current price
                && c.openInterest() > 0
                && c.volume() > c.openInterest() * 3;
        });

        // Max pain: the strike price where the most options expire worthless
        double maxPain = calculateMaxPain(chain, currentPrice);

        // Top strikes by volume
        double topCallStrike = calls.stream()
                .max(Comparator.comparingLong(OptionsDataService.ContractData::volume))
                .map(OptionsDataService.ContractData::strike).orElse(0.0);
        double topPutStrike = puts.stream()
                .max(Comparator.comparingLong(OptionsDataService.ContractData::volume))
                .map(OptionsDataService.ContractData::strike).orElse(0.0);

        // Underlying price from chain data (may be delayed)
        double undPrice = chain.stream()
                .filter(c -> c.underlyingPrice() > 0)
                .findFirst()
                .map(OptionsDataService.ContractData::underlyingPrice)
                .orElse(currentPrice);

        OptionsFlowResult result = new OptionsFlowResult(ticker, undPrice,
                callVol, putVol, callOI, putOI, pcVol, pcOI,
                direction, unusual, maxPain, topCallStrike, topPutStrike, totalContracts);

        log.info("{} options flow: {} callVol={} putVol={} ratio={} unusual={} maxPain={}",
                ticker, direction, callVol, putVol, String.format("%.2f", pcVol), unusual,
                String.format("%.1f", maxPain));

        return result;
    }

    // ── Contract Recommendation ─────────────────────────────────────────────────

    /**
     * Recommends the best options contract for a trade setup.
     * Selects based on:
     *  - Direction → call (long) or put (short)
     *  - Delta sweet spot: 0.35–0.55 (good leverage without excessive theta)
     *  - DTE: 7–21 days (enough time, not too expensive)
     *  - Liquidity: prefer contracts with volume > 50 or OI > 100
     *
     * @param ticker    underlying symbol
     * @param direction "long" or "short"
     * @param entry     entry price
     * @param sl        stop loss price
     * @param tp        take profit price
     * @return recommended contract with P&L estimates
     */
    public OptionsRecommendation recommendContract(String ticker, String direction,
                                                    double entry, double sl, double tp) {
        if (ticker.startsWith("X:")) return OptionsRecommendation.NONE;

        double strikeRange = entry * 0.08; // ±8% from entry
        List<OptionsDataService.ContractData> chain =
                dataService.getOptionsChain(ticker, entry, strikeRange, 7, 21);

        if (chain.isEmpty()) return OptionsRecommendation.NONE;

        // Filter by contract type based on direction
        String targetType = "long".equals(direction) ? "call" : "put";
        List<OptionsDataService.ContractData> candidates = chain.stream()
                .filter(c -> targetType.equals(c.contractType()))
                .filter(c -> c.close() > 0)         // must have a price
                .filter(c -> c.dte() >= 7 && c.dte() <= 21)  // 7-21 DTE
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            // Fallback: accept 5-30 DTE range
            candidates = chain.stream()
                    .filter(c -> targetType.equals(c.contractType()))
                    .filter(c -> c.close() > 0)
                    .filter(c -> c.dte() >= 5 && c.dte() <= 30)
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) return OptionsRecommendation.NONE;
        }

        // ── IV Rank filter: avoid buying when IV is expensive ──────────────
        double avgIV = candidates.stream().mapToDouble(OptionsDataService.ContractData::iv)
                .filter(iv -> iv > 0).average().orElse(0.30);
        int chainIvPct = avgIV <= 0 ? 50
                : avgIV < 0.20 ? 15 : avgIV < 0.30 ? 35 : avgIV < 0.40 ? 55
                : avgIV < 0.50 ? 70 : avgIV < 0.60 ? 82 : 92;
        if (chainIvPct > 70) {
            log.info("{} HIGH_IV_RANK: IV percentile ~{} — options premiums expensive, IV crush risk",
                    ticker, chainIvPct);
        }

        // Score each candidate: prefer 0.35-0.55 delta, good liquidity, reasonable premium
        OptionsDataService.ContractData best = null;
        double bestScore = Double.MIN_VALUE;

        for (OptionsDataService.ContractData c : candidates) {
            double absDelta = Math.abs(c.delta());
            double score = 0;

            // Delta score: peak at 0.45, penalize far from [0.35, 0.55]
            if (absDelta >= 0.35 && absDelta <= 0.55) {
                score += 40 - Math.abs(absDelta - 0.45) * 200; // peak 40 at 0.45
            } else if (absDelta >= 0.25 && absDelta <= 0.65) {
                score += 15; // acceptable range
            } else {
                score -= 20; // too far ITM or OTM
            }

            // Liquidity score: prefer volume > 50 and OI > 100
            if (c.volume() >= 200) score += 20;
            else if (c.volume() >= 50) score += 10;
            else if (c.volume() >= 10) score += 3;

            if (c.openInterest() >= 500) score += 15;
            else if (c.openInterest() >= 100) score += 8;
            else if (c.openInterest() >= 20) score += 3;

            // DTE score: prefer 10-14 days
            if (c.dte() >= 10 && c.dte() <= 14) score += 10;
            else if (c.dte() >= 7 && c.dte() <= 21) score += 5;

            // Bid-ask implied: prefer reasonable premium (not too cheap, not too expensive)
            double premiumPct = c.close() / entry * 100;
            if (premiumPct >= 0.5 && premiumPct <= 3.0) score += 10;
            else if (premiumPct < 0.5) score -= 10; // too cheap = far OTM, likely worthless

            // ── Bid-ask spread validation (estimated from day range) ──────
            if (c.dayHigh() > 0 && c.dayLow() > 0 && c.close() > 0) {
                double spreadPct = (c.dayHigh() - c.dayLow()) / c.close();
                if (spreadPct > 0.15) score -= 15;      // very wide spread
                else if (spreadPct > 0.10) score -= 5;   // moderately wide
                else if (spreadPct < 0.05) score += 5;   // tight spread bonus
            }

            // ── OI tier classification ────────────────────────────────────
            if (c.openInterest() < 100) score -= 25;     // illiquid, avoid
            else if (c.openInterest() >= 1000) score += 10; // very liquid bonus

            // ── IV Rank penalty for expensive premiums ────────────────────
            if (chainIvPct > 70) score -= 15;             // IV crush risk
            else if (chainIvPct < 30) score += 10;        // cheap IV = good for directional buys

            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) return OptionsRecommendation.NONE;

        // Calculate P&L estimates
        double premium = best.close();
        double delta   = best.delta();
        double gamma   = best.gamma();
        double theta   = best.theta();
        double holdDays = 1.0; // assume ~1 day hold for intraday setups

        // Move to TP: how much does premium change?
        double moveToTP = tp - entry; // signed
        double premiumAtTP = OptionsRecommendation.estimatePremium(premium, delta, gamma, theta, moveToTP, holdDays);

        // Move to SL: how much does premium change?
        double moveToSL = sl - entry; // signed
        double premiumAtSL = OptionsRecommendation.estimatePremium(premium, delta, gamma, theta, moveToSL, holdDays);

        double profitPerContract = (premiumAtTP - premium) * best.sharesPerContract();
        double lossPerContract   = (premium - premiumAtSL) * best.sharesPerContract();
        double optionsRR = lossPerContract > 0 ? profitPerContract / lossPerContract : 0;

        // Break-even price
        double breakEven = "call".equals(targetType)
                ? best.strike() + premium
                : best.strike() - premium;

        // IV percentile estimate (simple: IV < 0.25 = low, > 0.50 = high)
        int ivPercentile = best.iv() <= 0 ? 50
                : best.iv() < 0.20 ? 15
                : best.iv() < 0.30 ? 35
                : best.iv() < 0.40 ? 55
                : best.iv() < 0.50 ? 70
                : best.iv() < 0.60 ? 82
                : 92;

        // Suggested contracts: aim for ~$500 total premium
        int suggested = premium > 0 ? Math.max(1, (int) Math.floor(500.0 / (premium * 100))) : 1;

        String greeksWarning = OptionsRecommendation.computeGreeksWarning(
                theta, gamma, best.vega(), best.iv(), ivPercentile);

        OptionsRecommendation rec = new OptionsRecommendation(
                best.contractTicker(), best.contractType(), best.strike(),
                best.expirationDate(), best.dte(),
                Math.round(premium * 100.0) / 100.0,
                Math.round(delta * 1000.0) / 1000.0,
                Math.round(gamma * 10000.0) / 10000.0,
                Math.round(theta * 1000.0) / 1000.0,
                Math.round(best.iv() * 1000.0) / 1000.0,
                ivPercentile, Math.round(breakEven * 100.0) / 100.0,
                Math.round(premiumAtTP * 100.0) / 100.0,
                Math.round(premiumAtSL * 100.0) / 100.0,
                Math.round(profitPerContract * 100.0) / 100.0,
                Math.round(lossPerContract * 100.0) / 100.0,
                Math.round(optionsRR * 100.0) / 100.0,
                suggested, greeksWarning);

        log.info("{} contract rec: {} strike={} exp={} premium={} delta={} optRR={}:1",
                ticker, best.contractTicker(), best.strike(), best.expirationDate(),
                premium, String.format("%.3f", delta), String.format("%.1f", optionsRR));

        return rec;
    }

    // ── Max Pain Calculator ─────────────────────────────────────────────────────

    /**
     * Max Pain: the price at which the maximum total dollar value of options
     * expires worthless. Market makers profit most when price settles here.
     * Acts as a price magnet near expiration.
     */
    private double calculateMaxPain(List<OptionsDataService.ContractData> chain, double currentPrice) {
        // Get unique strikes
        Set<Double> strikes = chain.stream()
                .map(OptionsDataService.ContractData::strike)
                .collect(Collectors.toCollection(TreeSet::new));

        if (strikes.isEmpty()) return currentPrice;

        // For each potential expiry price, calculate total option value
        double minPain = Double.MAX_VALUE;
        double maxPainStrike = currentPrice;

        for (double testPrice : strikes) {
            double totalPain = 0;

            for (OptionsDataService.ContractData c : chain) {
                double itv; // intrinsic value at test price
                if ("call".equals(c.contractType())) {
                    itv = Math.max(0, testPrice - c.strike());
                } else {
                    itv = Math.max(0, c.strike() - testPrice);
                }
                // Weight by open interest (more OI = more money at stake)
                totalPain += itv * Math.max(c.openInterest(), 0) * c.sharesPerContract();
            }

            if (totalPain < minPain) {
                minPain = totalPain;
                maxPainStrike = testPrice;
            }
        }

        return maxPainStrike;
    }

    // ── Backtest Estimation ─────────────────────────────────────────────────────

    /**
     * Estimates options P&L for a backtest trade using a simplified delta model.
     * Used when historical options data isn't available.
     *
     * Model: ATM option with 0.45 delta, 10 DTE, premium ≈ 1.2-1.8% of underlying.
     *
     * @param entry           underlying entry price
     * @param exitPrice       underlying exit price
     * @param direction       "long" or "short"
     * @param holdDays        number of trading days held
     * @param dailyAtr        daily ATR for premium estimation
     * @return estimated options P&L percentage (on premium invested)
     */
    public BacktestOptionsEstimate estimateBacktestOptionsPnl(
            double entry, double exitPrice, String direction,
            double holdDays, double dailyAtr) {

        // Estimate typical ATM premium: ~0.5× daily ATR for a 10-DTE option
        // This is derived from: premium ≈ ATR × sqrt(DTE/252) × some vol factor
        // Simplified: 10-DTE ATM option ≈ 0.4-0.6 × daily ATR
        double estimatedPremium = dailyAtr * 0.5;
        if (estimatedPremium <= 0) estimatedPremium = entry * 0.012; // fallback: 1.2% of price

        // Typical ATM greeks for 10-DTE option
        double delta = 0.45;
        double gamma = 0.03 / entry * 10; // gamma scales inversely with price
        double thetaDaily = estimatedPremium * 0.05; // ~5% daily decay

        // Actual underlying move
        double underlyingMove = "long".equals(direction)
                ? (exitPrice - entry)   // positive = profitable for call
                : (entry - exitPrice);  // positive = profitable for put

        // Estimate exit premium
        double exitPremium = OptionsRecommendation.estimatePremium(
                estimatedPremium, delta, gamma, -thetaDaily, underlyingMove, holdDays);

        double pnlPerContract = (exitPremium - estimatedPremium) * 100;
        double pnlPct = estimatedPremium > 0
                ? (exitPremium - estimatedPremium) / estimatedPremium * 100
                : 0;

        return new BacktestOptionsEstimate(
                Math.round(estimatedPremium * 100.0) / 100.0,
                Math.round(exitPremium * 100.0) / 100.0,
                Math.round(pnlPerContract * 100.0) / 100.0,
                Math.round(pnlPct * 10.0) / 10.0
        );
    }

    public record BacktestOptionsEstimate(
            double entryPremium,      // estimated entry premium
            double exitPremium,       // estimated exit premium
            double pnlPerContract,    // profit/loss per 1 contract (×100 shares)
            double optionsPnlPct      // percentage return on premium invested
    ) {}
}
