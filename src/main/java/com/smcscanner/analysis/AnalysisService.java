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

            // ── Compute key levels from daily bars ───────────────────────────
            List<Map<String, Object>> levels = computeKeyLevels(bars1d, price);

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
            result.put("success",  true);
            result.put("ticker",   ticker);
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

    /** Extract recent swing highs/lows as key supply/demand levels. */
    private List<Map<String, Object>> computeKeyLevels(List<OHLCV> dailyBars, double price) {
        List<Map<String, Object>> levels = new ArrayList<>();
        if (dailyBars == null || dailyBars.size() < 10) return levels;

        // Last 30 bars: find pivots
        int lookback = Math.min(30, dailyBars.size());
        List<OHLCV> recent = dailyBars.subList(dailyBars.size() - lookback, dailyBars.size());

        double highestHigh = recent.stream().mapToDouble(OHLCV::getHigh).max().orElse(price);
        double lowestLow   = recent.stream().mapToDouble(OHLCV::getLow).min().orElse(price);

        // Find swing highs (local maxima) and lows (local minima) with a 3-bar pivot
        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows  = new ArrayList<>();
        for (int i = 2; i < recent.size() - 1; i++) {
            double h = recent.get(i).getHigh();
            if (h > recent.get(i-1).getHigh() && h > recent.get(i-2).getHigh()
                    && h > recent.get(i+1).getHigh()) swingHighs.add(h);
            double l = recent.get(i).getLow();
            if (l < recent.get(i-1).getLow() && l < recent.get(i-2).getLow()
                    && l < recent.get(i+1).getLow()) swingLows.add(l);
        }

        // Show closest resistance above price
        swingHighs.stream().filter(h -> h > price)
                .sorted().limit(3).forEach(h -> {
            Map<String,Object> lv = new LinkedHashMap<>();
            lv.put("type",  "resistance");
            lv.put("price", round2(h));
            lv.put("dist",  round2((h - price) / price * 100));
            lv.put("label", h >= highestHigh * 0.995 ? "52d High / Major Resistance" : "Swing High");
            levels.add(lv);
        });

        // Show closest support below price
        swingLows.stream().filter(l -> l < price)
                .sorted(Comparator.reverseOrder()).limit(3).forEach(l -> {
            Map<String,Object> lv = new LinkedHashMap<>();
            lv.put("type",  "support");
            lv.put("price", round2(l));
            lv.put("dist",  round2((price - l) / price * 100));
            lv.put("label", l <= lowestLow * 1.005 ? "52d Low / Major Support" : "Swing Low");
            levels.add(lv);
        });

        // Sort by distance to price
        levels.sort(Comparator.comparingDouble(lv -> (double) lv.get("dist")));
        return levels;
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
