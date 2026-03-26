package com.smcscanner.analysis;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.market.MarketContext;
import com.smcscanner.market.MarketContextService;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TickerProfile;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.news.NewsService;
import com.smcscanner.options.OptionsFlowAnalyzer;
import com.smcscanner.options.OptionsFlowResult;
import com.smcscanner.options.OptionsRecommendation;
import com.smcscanner.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * On-demand deep analysis of a single ticker.
 * Used by the /analyze page — runs the same logic as the live scanner
 * but returns a rich JSON map for the UI to render.
 */
@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final PolygonClient            client;
    private final AtrCalculator            atrCalc;
    private final SetupDetector            setupDetector;
    private final VwapStrategyDetector     vwapDetector;
    private final BreakoutStrategyDetector breakoutDetector;
    private final KeyLevelStrategyDetector keyLevelDetector;
    private final MultiTimeframeAnalyzer   mtf;
    private final NewsService              newsService;
    private final MarketContextService     marketCtx;
    private final OptionsFlowAnalyzer      optionsFlow;
    private final ScannerConfig            config;

    public AnalysisService(PolygonClient client, AtrCalculator atrCalc,
                           SetupDetector setupDetector, VwapStrategyDetector vwapDetector,
                           BreakoutStrategyDetector breakoutDetector,
                           KeyLevelStrategyDetector keyLevelDetector,
                           MultiTimeframeAnalyzer mtf, NewsService newsService,
                           MarketContextService marketCtx, OptionsFlowAnalyzer optionsFlow,
                           ScannerConfig config) {
        this.client = client; this.atrCalc = atrCalc;
        this.setupDetector = setupDetector; this.vwapDetector = vwapDetector;
        this.breakoutDetector = breakoutDetector; this.keyLevelDetector = keyLevelDetector;
        this.mtf = mtf; this.newsService = newsService;
        this.marketCtx = marketCtx; this.optionsFlow = optionsFlow;
        this.config = config;
    }

    public Map<String, Object> analyze(String ticker) {
        Map<String, Object> result = new LinkedHashMap<>();
        ticker = ticker.toUpperCase().trim();

        try {
            boolean isCrypto = ticker.startsWith("X:");

            // ── Fetch bars ──────────────────────────────────────────────────
            List<OHLCV> bars5m  = client.getBars(ticker, "5m",  100);
            List<OHLCV> bars15m = isCrypto ? List.of() : client.getBars(ticker, "15m", 80);
            List<OHLCV> bars60m = client.getBars(ticker, "60m", 60);
            List<OHLCV> bars1d  = client.getBars(ticker, "1d",  120);

            if (bars5m == null || bars5m.size() < 10) {
                result.put("success", false);
                result.put("error", "Insufficient 5m data for " + ticker + ". Check ticker symbol.");
                return result;
            }

            // ── Price & change ───────────────────────────────────────────────
            double price     = bars5m.get(bars5m.size() - 1).getClose();
            double open      = bars5m.get(bars5m.size() - 1).getOpen();
            // Use previous daily close for day-change calculation
            double prevClose = (bars1d != null && bars1d.size() >= 2)
                    ? bars1d.get(bars1d.size() - 2).getClose() : price;
            double changePct = round2((price - prevClose) / prevClose * 100);
            double changeAbs = round2(price - prevClose);

            // ── ATR ──────────────────────────────────────────────────────────
            double dailyAtr = 0;
            if (bars1d != null && bars1d.size() >= 15) {
                double[] atrArr = atrCalc.computeAtr(bars1d, 14);
                for (int i = atrArr.length - 1; i >= 0; i--) {
                    if (atrArr[i] > 0) { dailyAtr = atrArr[i]; break; }
                }
            }

            // ── Multi-timeframe bias ─────────────────────────────────────────
            String biasDaily = computeBias(bars1d);
            String bias60m   = computeBias(bars60m);
            String bias15m   = bars15m.size() >= 20 ? computeBias(bars15m) : "n/a";
            String bias5m    = computeBias(bars5m);

            // ── Ticker profile ───────────────────────────────────────────────
            TickerProfile profile = config.getTickerProfile(ticker);
            String stratType      = profile.getStrategyType();
            int    effectiveMinConf = profile.resolveMinConfidence(config.getMinConfidence());

            // ── Run setup detector ───────────────────────────────────────────
            List<TradeSetup> setups = List.of();
            try {
                if ("vwap".equals(stratType)) {
                    setups = vwapDetector.detect(bars5m, ticker, dailyAtr);
                } else if ("breakout".equals(stratType)) {
                    setups = breakoutDetector.detect(bars5m, ticker, dailyAtr);
                } else if ("keylevel".equals(stratType)) {
                    setups = keyLevelDetector.detect(bars5m, bars1d, ticker, dailyAtr);
                } else {
                    SetupDetector.DetectResult dr =
                            setupDetector.detectSetups(bars5m, biasDaily, ticker, isCrypto, dailyAtr);
                    setups = dr.setups();
                }
            } catch (Exception e) {
                log.debug("{} setup detection error: {}", ticker, e.getMessage());
            }
            TradeSetup setup = setups.isEmpty() ? null : setups.get(0);

            // ── News sentiment ───────────────────────────────────────────────
            NewsSentiment sentiment = isCrypto ? NewsSentiment.NONE
                    : newsService.getSentiment(ticker);

            // ── Market context ───────────────────────────────────────────────
            MarketContext context = isCrypto ? MarketContext.NONE
                    : marketCtx.getContext(ticker);

            // ── Options flow ─────────────────────────────────────────────────
            OptionsFlowResult flow  = OptionsFlowResult.NONE;
            OptionsRecommendation rec = OptionsRecommendation.NONE;
            if (!isCrypto) {
                try {
                    flow = optionsFlow.analyzeFlow(ticker, price);
                    if (setup != null) {
                        rec = optionsFlow.recommendContract(ticker, setup.getDirection(),
                                setup.getEntry(), setup.getStopLoss(), setup.getTakeProfit());
                    }
                } catch (Exception e) {
                    log.debug("{} options flow error: {}", ticker, e.getMessage());
                }
            }

            // ── Compute key levels — intraday (15m) + daily ──────────────────
            // 15m levels capture today's sweeps and recent session structure.
            // Daily levels provide the macro context (weekly swing points).
            List<Map<String, Object>> levels = computeKeyLevels(bars15m, bars1d, price, dailyAtr);

            // ── Verdict ──────────────────────────────────────────────────────
            String verdict; String verdictClass;
            if (profile.isSkip()) {
                verdict = "Skipped — " + profile.getSkipReason();
                verdictClass = "skip";
            } else if (setup != null && setup.getConfidence() >= effectiveMinConf) {
                verdict = "🔥 LIVE SETUP — " + setup.getDirection().toUpperCase()
                        + " signal active at $" + String.format("%.2f", setup.getEntry());
                verdictClass = "live";
            } else if (setup != null) {
                verdict = "⚠️ Setup detected but below min confidence ("
                        + setup.getConfidence() + " < " + effectiveMinConf + ")";
                verdictClass = "weak";
            } else {
                verdict = buildNoSetupReason(biasDaily, bias15m, bias5m, stratType);
                verdictClass = "none";
            }

            // ── Assemble result ──────────────────────────────────────────────
            result.put("success",    true);
            result.put("ticker",     ticker);
            result.put("asOf", java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM d h:mm a z")));
            result.put("isCrypto", isCrypto);
            result.put("strategy", stratType.toUpperCase());
            result.put("minConf",  effectiveMinConf);
            result.put("skip",     profile.isSkip());

            // Price
            Map<String,Object> priceMap = new LinkedHashMap<>();
            priceMap.put("current",   round2(price));
            priceMap.put("prevClose", round2(prevClose));
            priceMap.put("changePct", changePct);
            priceMap.put("changeAbs", changeAbs);
            priceMap.put("dailyAtr",  round2(dailyAtr));
            priceMap.put("atrPct",    dailyAtr > 0 ? round2(dailyAtr / price * 100) : 0);
            result.put("price", priceMap);

            // Bias
            Map<String,Object> biasMap = new LinkedHashMap<>();
            biasMap.put("daily", biasDaily);
            biasMap.put("h1",    bias60m);
            biasMap.put("m15",   bias15m);
            biasMap.put("m5",    bias5m);
            result.put("bias", biasMap);

            // Setup
            Map<String,Object> setupMap = new LinkedHashMap<>();
            setupMap.put("found", setup != null);
            if (setup != null) {
                setupMap.put("direction",  setup.getDirection());
                setupMap.put("confidence", setup.getConfidence());
                setupMap.put("entry",      round4(setup.getEntry()));
                setupMap.put("sl",         round4(setup.getStopLoss()));
                setupMap.put("tp",         round4(setup.getTakeProfit()));
                setupMap.put("rr",         round2(setup.rrRatio()));
                setupMap.put("hasBos",     setup.isHasBos());
                setupMap.put("hasChoch",   setup.isHasChoch());
                setupMap.put("fvgTop",     setup.getFvgTop() > 0 ? round4(setup.getFvgTop()) : null);
                setupMap.put("fvgBottom",  setup.getFvgBottom() > 0 ? round4(setup.getFvgBottom()) : null);
                setupMap.put("passesConf", setup.getConfidence() >= effectiveMinConf);
                double beLevel = "long".equals(setup.getDirection())
                        ? setup.getEntry() + Math.abs(setup.getEntry() - setup.getStopLoss())
                        : setup.getEntry() - Math.abs(setup.getEntry() - setup.getStopLoss());
                setupMap.put("beLevel", round4(beLevel));
            }
            result.put("setup", setupMap);

            // News
            Map<String,Object> newsMap = new LinkedHashMap<>();
            newsMap.put("label",    sentiment.label());
            newsMap.put("adj",      setup != null ? sentiment.confidenceDelta(setup.getDirection()) : 0);
            newsMap.put("headline", sentiment.headline());
            result.put("news", newsMap);

            // Context
            Map<String,Object> ctxMap = new LinkedHashMap<>();
            ctxMap.put("rsScore",   context != MarketContext.NONE ? round2(context.rsScore() * 100) : 0);
            ctxMap.put("vixLevel",  context != MarketContext.NONE ? round2(context.vixLevel()) : 0);
            ctxMap.put("vixRegime", context != MarketContext.NONE ? context.vixRegime() : "n/a");
            ctxMap.put("rsLabel",   context != MarketContext.NONE ? context.rsLabel() : null);
            result.put("context", ctxMap);

            // Options flow
            Map<String,Object> optMap = new LinkedHashMap<>();
            optMap.put("hasFlowData", flow.hasData());
            if (flow.hasData()) {
                optMap.put("flowDir",   flow.flowDirection());
                optMap.put("pcRatio",   round2(flow.pcRatioVol()));
                optMap.put("unusual",   flow.unusualActivity());
                optMap.put("maxPain",   flow.maxPainStrike());
                optMap.put("flowLabel", flow.label());
            }
            optMap.put("hasRecData", rec.hasData());
            if (rec.hasData()) {
                optMap.put("contract",   rec.contractTicker());
                optMap.put("type",       rec.contractType());
                optMap.put("strike",     rec.strike());
                optMap.put("expiry",     rec.expirationDate());
                optMap.put("premium",    round2(rec.estimatedPremium()));
                optMap.put("delta",      round3(rec.delta()));
                optMap.put("iv",         round2(rec.iv() * 100));
                optMap.put("ivPct",      round2(rec.ivPercentile()));
                optMap.put("breakEven",  round2(rec.breakEvenPrice()));
                optMap.put("profitPer",  round2(rec.profitPerContract()));
                optMap.put("lossPer",    round2(rec.lossPerContract()));
                optMap.put("optRR",      round2(rec.optionsRR()));
                optMap.put("contracts",  rec.suggestedContracts());
            }
            result.put("options", optMap);

            // Key levels
            result.put("levels", levels);

            // Verdict
            result.put("verdict",      verdict);
            result.put("verdictClass", verdictClass);

            // Narrative — plain-English trader's read of the current situation
            result.put("narrative", buildNarrative(
                    ticker, price, dailyAtr, changePct,
                    biasDaily, bias60m, bias15m, bias5m,
                    setup, effectiveMinConf, levels,
                    sentiment, context, flow, rec));

        } catch (Exception e) {
            log.error("Analysis failed for {}: {}", ticker, e.getMessage(), e);
            result.put("success", false);
            result.put("error", "Analysis failed: " + e.getMessage());
        }

        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String computeBias(List<OHLCV> bars) {
        if (bars == null || bars.size() < 20) return "neutral";
        try { return mtf.getHtfBias(bars); } catch (Exception e) { return "neutral"; }
    }

    /**
     * Compute key levels from two sources:
     *  1) 15m intraday bars — today's session high/low, recent pivots, swept levels
     *  2) Daily bars — macro swing highs/lows over the last 30 days
     *
     * Intraday levels are labeled clearly so the user can see where price has been
     * *today* vs longer-term structure. A level is marked "swept" if price passed
     * through it and then recovered (classic SMC liquidity grab signal).
     */
    private List<Map<String, Object>> computeKeyLevels(
            List<OHLCV> bars15m, List<OHLCV> bars1d, double price, double dailyAtr) {

        List<Map<String, Object>> levels = new ArrayList<>();
        double tolerance = dailyAtr > 0 ? dailyAtr * 0.05 : price * 0.002; // ~0.2% or 5% ATR

        // ── 1. INTRADAY levels from 15m bars (last 60 bars ≈ 2–3 trading days) ──
        if (bars15m != null && bars15m.size() >= 8) {
            int lookback15 = Math.min(60, bars15m.size());
            List<OHLCV> recent15 = bars15m.subList(bars15m.size() - lookback15, bars15m.size());

            // Session high and low (all 15m bars in the lookback)
            double sessionHigh = recent15.stream().mapToDouble(OHLCV::getHigh).max().orElse(price);
            double sessionLow  = recent15.stream().mapToDouble(OHLCV::getLow).min().orElse(price);

            // Today's bars only (last 26 bars = 1 full session on 15m)
            int todayBars = Math.min(26, recent15.size());
            List<OHLCV> today15 = recent15.subList(recent15.size() - todayBars, recent15.size());
            double todayHigh = today15.stream().mapToDouble(OHLCV::getHigh).max().orElse(sessionHigh);
            double todayLow  = today15.stream().mapToDouble(OHLCV::getLow).min().orElse(sessionLow);

            // Check if today's high/low was "swept" — price pierced it then recovered
            // Swept high: price went above it on one bar's wick, closed back below it
            boolean todayHighSwept = today15.stream().anyMatch(b ->
                    b.getHigh() >= todayHigh * 0.9995 && b.getClose() < todayHigh * 0.999
                    && b.getClose() < b.getOpen()); // bearish close after touching the high
            boolean todayLowSwept = today15.stream().anyMatch(b ->
                    b.getLow() <= todayLow * 1.0005 && b.getClose() > todayLow * 1.001
                    && b.getClose() > b.getOpen()); // bullish close after touching the low

            // Add today's high as resistance (or swept level)
            if (Math.abs(todayHigh - price) / price > 0.0005) { // not the current price itself
                Map<String,Object> lv = new LinkedHashMap<>();
                lv.put("type",      todayHigh > price ? "resistance" : "swept-resistance");
                lv.put("price",     round2(todayHigh));
                lv.put("dist",      round2(Math.abs(todayHigh - price) / price * 100));
                lv.put("intraday",  true);
                if (todayHighSwept && todayHigh < price) {
                    lv.put("label", "⚡ Today's High (SWEPT — liquidity taken, watch for reversal)");
                    lv.put("swept", true);
                } else {
                    lv.put("label", todayHigh > price ? "Today's High (Intraday Resistance)" : "Today's High (Broken — now support)");
                    lv.put("swept", false);
                }
                levels.add(lv);
            }

            // Add today's low as support (or swept level)
            if (Math.abs(todayLow - price) / price > 0.0005) {
                Map<String,Object> lv = new LinkedHashMap<>();
                lv.put("type",      todayLow < price ? "support" : "swept-support");
                lv.put("price",     round2(todayLow));
                lv.put("dist",      round2(Math.abs(price - todayLow) / price * 100));
                lv.put("intraday",  true);
                if (todayLowSwept && todayLow > price) {
                    lv.put("label", "⚡ Today's Low (SWEPT — liquidity taken, watch for reversal)");
                    lv.put("swept", true);
                } else {
                    lv.put("label", todayLow < price ? "Today's Low (Intraday Support)" : "Today's Low (Broken — now resistance)");
                    lv.put("swept", false);
                }
                levels.add(lv);
            }

            // 15m pivot highs/lows with a 3-bar pivot (intraday structure)
            List<Double> pivotHighs = new ArrayList<>();
            List<Double> pivotLows  = new ArrayList<>();
            for (int i = 2; i < recent15.size() - 1; i++) {
                double h = recent15.get(i).getHigh();
                if (h > recent15.get(i-1).getHigh() && h > recent15.get(i-2).getHigh()
                        && h > recent15.get(i+1).getHigh()
                        && Math.abs(h - todayHigh) / price > 0.002) { // not a duplicate of today's high
                    pivotHighs.add(h);
                }
                double l = recent15.get(i).getLow();
                if (l < recent15.get(i-1).getLow() && l < recent15.get(i-2).getLow()
                        && l < recent15.get(i+1).getLow()
                        && Math.abs(l - todayLow) / price > 0.002) {
                    pivotLows.add(l);
                }
            }

            // Add up to 2 closest intraday pivot resistance levels above price
            pivotHighs.stream().filter(h -> h > price + tolerance)
                    .sorted().limit(2).forEach(h -> {
                Map<String,Object> lv = new LinkedHashMap<>();
                lv.put("type",      "resistance");
                lv.put("price",     round2(h));
                lv.put("dist",      round2((h - price) / price * 100));
                lv.put("intraday",  true);
                lv.put("swept",     false);
                lv.put("label",     "15m Pivot High (Intraday Resistance)");
                levels.add(lv);
            });

            // Add up to 2 closest intraday pivot support levels below price
            pivotLows.stream().filter(l -> l < price - tolerance)
                    .sorted(Comparator.reverseOrder()).limit(2).forEach(l -> {
                Map<String,Object> lv = new LinkedHashMap<>();
                lv.put("type",      "support");
                lv.put("price",     round2(l));
                lv.put("dist",      round2((price - l) / price * 100));
                lv.put("intraday",  true);
                lv.put("swept",     false);
                lv.put("label",     "15m Pivot Low (Intraday Support)");
                levels.add(lv);
            });
        }

        // ── 2. DAILY macro levels from the last 30 daily bars ──────────────────
        if (bars1d != null && bars1d.size() >= 10) {
            int lookback1d = Math.min(30, bars1d.size());
            List<OHLCV> recent1d = bars1d.subList(bars1d.size() - lookback1d, bars1d.size());

            double highestHigh = recent1d.stream().mapToDouble(OHLCV::getHigh).max().orElse(price);
            double lowestLow   = recent1d.stream().mapToDouble(OHLCV::getLow).min().orElse(price);

            List<Double> swingHighs = new ArrayList<>();
            List<Double> swingLows  = new ArrayList<>();
            for (int i = 2; i < recent1d.size() - 1; i++) {
                double h = recent1d.get(i).getHigh();
                if (h > recent1d.get(i-1).getHigh() && h > recent1d.get(i-2).getHigh()
                        && h > recent1d.get(i+1).getHigh()) swingHighs.add(h);
                double l = recent1d.get(i).getLow();
                if (l < recent1d.get(i-1).getLow() && l < recent1d.get(i-2).getLow()
                        && l < recent1d.get(i+1).getLow()) swingLows.add(l);
            }

            // Check if any intraday level already covers this zone (within tolerance)
            // to avoid duplicate labels at nearly the same price
            final List<Double> existingPrices = levels.stream()
                    .map(lv -> (double) lv.get("price")).collect(java.util.stream.Collectors.toList());

            swingHighs.stream().filter(h -> h > price + tolerance)
                    .filter(h -> existingPrices.stream().noneMatch(ep -> Math.abs(ep - h) / price < 0.005))
                    .sorted().limit(3).forEach(h -> {
                Map<String,Object> lv = new LinkedHashMap<>();
                lv.put("type",      "resistance");
                lv.put("price",     round2(h));
                lv.put("dist",      round2((h - price) / price * 100));
                lv.put("intraday",  false);
                lv.put("swept",     false);
                lv.put("label",     h >= highestHigh * 0.995 ? "30d High / Major Resistance" : "Daily Swing High");
                levels.add(lv);
            });

            swingLows.stream().filter(l -> l < price - tolerance)
                    .filter(l -> existingPrices.stream().noneMatch(ep -> Math.abs(ep - l) / price < 0.005))
                    .sorted(Comparator.reverseOrder()).limit(3).forEach(l -> {
                Map<String,Object> lv = new LinkedHashMap<>();
                lv.put("type",      "support");
                lv.put("price",     round2(l));
                lv.put("dist",      round2((price - l) / price * 100));
                lv.put("intraday",  false);
                lv.put("swept",     false);
                lv.put("label",     l <= lowestLow * 1.005 ? "30d Low / Major Support" : "Daily Swing Low");
                levels.add(lv);
            });
        }

        // Sort all levels by distance to price (closest first)
        levels.sort(Comparator.comparingDouble(lv -> (double) lv.get("dist")));
        return levels;
    }

    /**
     * Generates a plain-English trader's read of the current situation —
     * structure, long case, short case, which has higher probability, what to watch.
     */
    private Map<String, Object> buildNarrative(
            String ticker, double price, double atr, double changePct,
            String biasDaily, String bias1h, String bias15m, String bias5m,
            TradeSetup setup, int minConf,
            List<Map<String, Object>> levels,
            NewsSentiment news, MarketContext ctx,
            OptionsFlowResult flow, OptionsRecommendation rec) {

        Map<String, Object> n = new LinkedHashMap<>();

        // ── Count bearish vs bullish signals ────────────────────────────────
        int bullSignals = 0, bearSignals = 0;
        if ("bullish".equals(biasDaily)) bullSignals++; else if ("bearish".equals(biasDaily)) bearSignals++;
        if ("bullish".equals(bias1h))    bullSignals++; else if ("bearish".equals(bias1h))    bearSignals++;
        if ("bullish".equals(bias15m))   bullSignals++; else if ("bearish".equals(bias15m))   bearSignals++;
        if ("bullish".equals(bias5m))    bullSignals++; else if ("bearish".equals(bias5m))    bearSignals++;
        if (news.hasNews() && news.isBullish()) bullSignals++; else if (news.hasNews() && news.isBearish()) bearSignals++;
        if (ctx != MarketContext.NONE && ctx.rsScore() > 0.03) bullSignals++;
        else if (ctx != MarketContext.NONE && ctx.rsScore() < -0.03) bearSignals++;

        String overallBias = bullSignals > bearSignals + 1 ? "bullish"
                           : bearSignals > bullSignals + 1 ? "bearish" : "mixed";

        // ── Nearest levels above and below ──────────────────────────────────
        double nearestRes = levels.stream()
                .filter(lv -> "resistance".equals(lv.get("type")))
                .mapToDouble(lv -> (double) lv.get("price"))
                .min().orElse(price + atr * 2);
        double nearestSup = levels.stream()
                .filter(lv -> "support".equals(lv.get("type")))
                .mapToDouble(lv -> (double) lv.get("price"))
                .max().orElse(price - atr * 2);

        double distToRes = (nearestRes - price) / price * 100;
        double distToSup = (price - nearestSup) / price * 100;

        // ── Structure sentence ───────────────────────────────────────────────
        String structureSentence;
        if ("bullish".equals(biasDaily) && "bullish".equals(bias1h)) {
            structureSentence = ticker + " has bullish structure on both daily and 1H — price is above its SMA20 on higher timeframes, trend is up.";
        } else if ("bearish".equals(biasDaily) && "bearish".equals(bias1h)) {
            structureSentence = ticker + " has bearish structure on both daily and 1H — price is below its SMA20 on higher timeframes, trend is down.";
        } else if ("bullish".equals(biasDaily) && ("bearish".equals(bias1h) || "bearish".equals(bias15m))) {
            structureSentence = ticker + " has bullish daily structure but short-term weakness on 1H/15m — this is a pullback within an uptrend.";
        } else if ("bearish".equals(biasDaily) && ("bullish".equals(bias1h) || "bullish".equals(bias15m))) {
            structureSentence = ticker + " has bearish daily structure with a short-term bounce on 1H/15m — this looks like a relief rally within a downtrend.";
        } else {
            structureSentence = ticker + " has mixed/neutral structure across timeframes — no clear dominant trend right now.";
        }

        // ── Nearest resistance distance assessment ───────────────────────────
        String resComment;
        if (distToRes < 0.5) resComment = "Price is pressing right up against resistance at $" + String.format("%.2f", nearestRes) + " — high risk for longs here.";
        else if (distToRes < 1.0) resComment = "Resistance at $" + String.format("%.2f", nearestRes) + " is " + String.format("%.1f", distToRes) + "% away — limited upside room before the next wall.";
        else resComment = "Nearest resistance is $" + String.format("%.2f", nearestRes) + " (" + String.format("%.1f", distToRes) + "% away) — reasonable room to run if buyers step in.";

        String supComment;
        if (distToSup < 0.5) supComment = "Price is sitting right on support at $" + String.format("%.2f", nearestSup) + " — key level, break below is significant.";
        else if (distToSup < 1.0) supComment = "Support at $" + String.format("%.2f", nearestSup) + " is " + String.format("%.1f", distToSup) + "% below — tight cushion.";
        else supComment = "Support at $" + String.format("%.2f", nearestSup) + " gives " + String.format("%.1f", distToSup) + "% downside buffer.";

        // ── Long case ────────────────────────────────────────────────────────
        String longCase;
        boolean longLikely = bullSignals >= bearSignals && distToRes > 0.8;
        if ("bearish".equals(biasDaily) && "bearish".equals(bias1h)) {
            longCase = "LOW probability. Daily and 1H are both bearish — any bounce is fighting the dominant trend. A long would only make sense as a short-term scalp IF price sweeps the $"
                    + String.format("%.2f", nearestSup) + " support, shows a strong bullish displacement candle, and reclaims $"
                    + String.format("%.2f", price) + " with volume. Target would be the $" + String.format("%.2f", nearestRes) + " resistance zone.";
        } else if ("bullish".equals(biasDaily) && bearSignals > bullSignals) {
            longCase = "MEDIUM probability — this is a pullback in an uptrend. A long is valid if price holds above $"
                    + String.format("%.2f", nearestSup) + " (support) and shows a sweep + bullish displacement back above $"
                    + String.format("%.2f", price) + ". Target: $" + String.format("%.2f", nearestRes) + ". "
                    + resComment;
        } else if (distToRes < 0.6) {
            longCase = "CAUTION — price is " + String.format("%.1f", distToRes) + "% from resistance at $"
                    + String.format("%.2f", nearestRes) + ". Buying here means buying into a wall. "
                    + "Wait for price to either break through $" + String.format("%.2f", nearestRes)
                    + " with a strong close, or pull back to $" + String.format("%.2f", nearestSup) + " first.";
        } else {
            longCase = "VIABLE. " + resComment + " For a long entry, need: sweep of $"
                    + String.format("%.2f", nearestSup) + " support → strong bullish displacement candle → SMC FVG retest. "
                    + "Target $" + String.format("%.2f", nearestRes) + ". SL below $" + String.format("%.2f", nearestSup - atr * 0.3) + ".";
        }

        // ── Short case ───────────────────────────────────────────────────────
        String shortCase;
        boolean shortLikely = bearSignals >= bullSignals && distToSup > 0.8;
        if ("bullish".equals(biasDaily) && "bullish".equals(bias1h)) {
            shortCase = "LOW probability. Daily and 1H are both bullish — shorting here is fighting the trend. Only valid as a scalp if price sweeps $"
                    + String.format("%.2f", nearestRes) + " (liquidity grab), shows a sharp bearish rejection candle, and breaks back below $"
                    + String.format("%.2f", price) + ". Target $" + String.format("%.2f", nearestSup) + ".";
        } else if ("bearish".equals(biasDaily) && bullSignals > bearSignals) {
            shortCase = "MEDIUM probability — this is a bounce in a downtrend. A short makes sense if price rallies into the $"
                    + String.format("%.2f", nearestRes) + " resistance zone and fails with a bearish displacement. "
                    + "Target: $" + String.format("%.2f", nearestSup) + ". SL above $" + String.format("%.2f", nearestRes + atr * 0.3) + ".";
        } else if (distToSup < 0.6) {
            shortCase = "CAUTION — price is " + String.format("%.1f", distToSup) + "% from support at $"
                    + String.format("%.2f", nearestSup) + ". Shorting into support is risky. "
                    + "Wait for price to either break below $" + String.format("%.2f", nearestSup)
                    + " decisively, or bounce to $" + String.format("%.2f", nearestRes) + " for a better short entry.";
        } else {
            shortCase = "VIABLE. " + supComment + " For a short entry, need: sweep of $"
                    + String.format("%.2f", nearestRes) + " resistance (liquidity grab) → strong bearish displacement candle → SMC FVG retest. "
                    + "Target $" + String.format("%.2f", nearestSup) + ". SL above $" + String.format("%.2f", nearestRes + atr * 0.3) + ".";
        }

        // ── Probability verdict ──────────────────────────────────────────────
        String probabilityVerdict;
        if (bearSignals >= bullSignals + 2) {
            probabilityVerdict = "SHORT has higher probability right now. " + bearSignals + " bearish signals vs " + bullSignals + " bullish. Dominant trend is down.";
        } else if (bullSignals >= bearSignals + 2) {
            probabilityVerdict = "LONG has higher probability right now. " + bullSignals + " bullish signals vs " + bearSignals + " bearish. Dominant trend is up.";
        } else {
            probabilityVerdict = "Neither direction has a clear edge right now (" + bullSignals + " bullish vs " + bearSignals + " bearish signals). "
                    + "Wait for a decisive move — don't force a trade in a mixed market.";
        }

        // ── Key watch level ──────────────────────────────────────────────────
        String keyWatch;
        if (Math.abs(price - nearestSup) < Math.abs(nearestRes - price)) {
            keyWatch = "$" + String.format("%.2f", nearestSup) + " (support) is the critical level. "
                    + "Holding above it = long bias. Breaking below it = short bias toward $"
                    + String.format("%.2f", nearestSup - atr * 1.5) + ".";
        } else {
            keyWatch = "$" + String.format("%.2f", nearestRes) + " (resistance) is the critical level. "
                    + "Breaking above it with a strong close = long continuation to $"
                    + String.format("%.2f", nearestRes + atr * 1.5) + ". "
                    + "Rejecting here = short toward $" + String.format("%.2f", nearestSup) + ".";
        }

        // ── Max pain warning ─────────────────────────────────────────────────
        String maxPainNote = null;
        if (flow.hasData() && flow.maxPainStrike() > 0) {
            double mp = flow.maxPainStrike();
            double mpDist = (mp - price) / price * 100;
            if (Math.abs(mpDist) < 2.0) {
                maxPainNote = "⚠️ Max pain at $" + String.format("%.2f", mp) + " (" + String.format("%+.1f", mpDist) + "%) — "
                        + (mpDist < 0 ? "gravitational pull downward into expiry. Be careful with calls."
                                      : "gravitational pull upward into expiry. Supports longs.");
            }
        }

        // ── Existing setup note ──────────────────────────────────────────────
        String setupNote = null;
        if (setup != null && setup.getConfidence() >= minConf) {
            setupNote = "🔥 Scanner has an active " + setup.getDirection().toUpperCase()
                    + " setup right now at $" + String.format("%.2f", setup.getEntry())
                    + " (confidence " + setup.getConfidence() + "/100). Entry is live.";
        } else if (setup != null) {
            setupNote = "Scanner detected a " + setup.getDirection() + " pattern but confidence ("
                    + setup.getConfidence() + ") is below the minimum (" + minConf + ") — not strong enough to alert.";
        }

        // ── Assemble into named sections ─────────────────────────────────────
        n.put("structure",    structureSentence);
        n.put("overallBias",  overallBias);
        n.put("bullSignals",  bullSignals);
        n.put("bearSignals",  bearSignals);
        n.put("longCase",     longCase);
        n.put("shortCase",    shortCase);
        n.put("probability",  probabilityVerdict);
        n.put("keyWatch",     keyWatch);
        if (maxPainNote != null) n.put("maxPain", maxPainNote);
        if (setupNote   != null) n.put("setupNote", setupNote);

        return n;
    }

    private String buildNoSetupReason(String biasDaily, String bias15m, String bias5m, String strat) {
        if ("neutral".equals(biasDaily) && "neutral".equals(bias15m))
            return "📊 No setup — market structure neutral across all timeframes, waiting for directional bias";
        if ("smc".equals(strat))
            return "📊 No setup — SMC conditions not met (no sweep+FVG+displacement pattern detected on 5m)";
        if ("vwap".equals(strat))
            return "📊 No setup — price not sufficiently extended from VWAP to trigger fade signal";
        if ("breakout".equals(strat))
            return "📊 No setup — ORB range not yet established or price hasn't broken out with volume";
        return "📊 No setup detected — conditions not met";
    }

    private double round2(double v) { return Math.round(v * 100.0)   / 100.0; }
    private double round3(double v) { return Math.round(v * 1000.0)  / 1000.0; }
    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
