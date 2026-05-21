package com.smcscanner.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Calls Claude Vision API to visually analyze a chart before firing an alert.
 * Uses Haiku (fast, cheap ~$0.001/call) as the primary gate.
 * Falls back to approve=true on any error so a Claude outage never blocks alerts.
 */
@Service
public class ChartVisionService {

    private static final Logger log = LoggerFactory.getLogger(ChartVisionService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";
    private static final int MIN_SCORE_TO_APPROVE = 50; // approve if Claude gives >= 50/100
    private static final long CACHE_MS = 10 * 60 * 1000L; // 10 min per ticker

    private final ScannerConfig config;
    private final ObjectMapper  mapper = new ObjectMapper();
    private final OkHttpClient  http   = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    // Cache: ticker → [timestamp, VisionVerdict]
    private final ConcurrentHashMap<String, long[]>       cacheTs      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VisionVerdict> cacheVerdict = new ConcurrentHashMap<>();

    public ChartVisionService(ScannerConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        if (config.isDev()) return false; // never call Anthropic API on dev — costs money
        String key = config.getAnthropicApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * Analyzes the chart visually. Returns a verdict with approve flag + score + reason.
     * Always returns approve=true on error/timeout (fail open — don't block alerts).
     */
    public VisionVerdict analyze(List<OHLCV> bars, TradeSetup signal) {
        if (!isEnabled()) {
            return VisionVerdict.noKey();
        }
        String ticker = signal.getTicker();

        // Check cache — same ticker within 10 min gets the same verdict
        Long[] ts = new Long[]{ cacheTs.getOrDefault(ticker, new long[]{0})[0] };
        if (System.currentTimeMillis() - ts[0] < CACHE_MS) {
            VisionVerdict cached = cacheVerdict.get(ticker);
            if (cached != null) {
                log.info("VISION_CACHE {} — reusing verdict approve={} score={}", ticker, cached.approve(), cached.score());
                return cached;
            }
        }

        try {
            byte[] png = ChartRenderer.render(bars, signal);
            String b64 = Base64.getEncoder().encodeToString(png);

            String prompt = buildPrompt(signal);

            Map<String, Object> imageContent = Map.of(
                "type", "image",
                "source", Map.of(
                    "type", "base64",
                    "media_type", "image/png",
                    "data", b64
                )
            );
            Map<String, Object> textContent = Map.of("type", "text", "text", prompt);

            Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", 300,
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(imageContent, textContent)
                ))
            );

            Request req = new Request.Builder()
                .url(API_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .addHeader("x-api-key", config.getAnthropicApiKey())
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("VISION_API_ERROR {} status={}", ticker, resp.code());
                    return VisionVerdict.failOpen("API error " + resp.code());
                }
                String raw = resp.body().string();
                VisionVerdict verdict = parseVerdict(raw, ticker);
                // Cache result
                cacheTs.put(ticker, new long[]{ System.currentTimeMillis() });
                cacheVerdict.put(ticker, verdict);
                log.info("VISION {} dir={} conf={} → approve={} score={} reason={}",
                        ticker, signal.getDirection(), signal.getConfidence(),
                        verdict.approve(), verdict.score(), verdict.reason());
                return verdict;
            }
        } catch (Exception e) {
            log.warn("VISION_EXCEPTION {} — {}", ticker, e.getMessage());
            return VisionVerdict.failOpen(e.getMessage());
        }
    }

    /**
     * Proactive scan — Claude looks at the chart cold and decides if a setup is forming.
     * No rule-based signal is required. Returns empty if no setup found or score < MIN_PROACTIVE_SCORE.
     */
    public java.util.Optional<VisionSetup> proactiveScan(List<OHLCV> bars, String ticker) {
        if (!isEnabled()) return java.util.Optional.empty();

        // Dedupe: skip if we already scanned this ticker in the last 25 min
        Long[] ts = new Long[]{ cacheTs.getOrDefault("SCAN_" + ticker, new long[]{0})[0] };
        if (System.currentTimeMillis() - ts[0] < 25 * 60 * 1000L) {
            return java.util.Optional.empty();
        }

        try {
            byte[] png = ChartRenderer.renderBasic(bars, ticker);
            String b64 = Base64.getEncoder().encodeToString(png);

            // Build context from bar data
            OHLCV last = bars.get(bars.size() - 1);
            double price = last.getClose();
            double[] vwap = calcVwapFromBars(bars);
            double vwapNow = vwap[vwap.length - 1];
            double atr = calcAtr(bars, 14);

            String prompt = buildProactivePrompt(ticker, price, vwapNow, atr);

            Map<String, Object> imageContent = Map.of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", "image/png", "data", b64)
            );
            Map<String, Object> textContent = Map.of("type", "text", "text", prompt);

            Map<String, Object> body = Map.of(
                "model", MODEL,
                "max_tokens", 400,
                "messages", List.of(Map.of("role", "user",
                    "content", List.of(imageContent, textContent)))
            );

            Request req = new Request.Builder()
                .url(API_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .addHeader("x-api-key", config.getAnthropicApiKey())
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("VISION_SCAN_API_ERROR {} status={}", ticker, resp.code());
                    return java.util.Optional.empty();
                }
                String raw = resp.body().string();
                java.util.Optional<VisionSetup> result = parseProactiveResponse(raw, ticker, price, atr);
                cacheTs.put("SCAN_" + ticker, new long[]{ System.currentTimeMillis() });
                result.ifPresentOrElse(
                    vs -> log.info("VISION_SCAN_FOUND {} dir={} entry={} score={} pattern={}", ticker, vs.direction(), vs.entry(), vs.score(), vs.pattern()),
                    ()  -> log.debug("VISION_SCAN_NONE {} — no setup found", ticker)
                );
                return result;
            }
        } catch (Exception e) {
            log.warn("VISION_SCAN_EXCEPTION {} — {}", ticker, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String buildProactivePrompt(String ticker, double price, double vwap, double atr) {
        return String.format(
            "You are a professional intraday trader analyzing a 5-minute candlestick chart for %s.\n\n" +
            "Current data:\n" +
            "- Price: %.2f\n" +
            "- VWAP: %.2f (%s VWAP)\n" +
            "- ATR(14): %.2f\n\n" +
            "Look at the chart carefully and identify if a high-probability setup is forming RIGHT NOW " +
            "or will likely trigger in the next 1-3 bars. Consider:\n" +
            "- Trend direction and momentum (candle structure, higher highs/lows)\n" +
            "- Volume behavior (increasing on breakouts, declining on consolidations)\n" +
            "- Key levels (VWAP, swing highs/lows, consolidation zones)\n" +
            "- Chart patterns (flags, wedges, FVG retests, order block tests, breakouts)\n" +
            "- Any divergence between price and volume\n\n" +
            "If you see a clear setup, provide exact prices anchored to the chart.\n" +
            "If there is no clear setup, return setup_found: false.\n\n" +
            "Respond ONLY with valid JSON:\n" +
            "{\"setup_found\": true/false, \"score\": 0-100, \"direction\": \"long\"/\"short\", " +
            "\"entry\": <price>, \"stop_loss\": <price>, \"take_profit\": <price>, " +
            "\"pattern\": \"<pattern name>\", \"reason\": \"<one sentence>\"}",
            ticker, price, vwap, price > vwap ? "above" : "below", atr
        );
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<VisionSetup> parseProactiveResponse(String raw, String ticker, double currentPrice, double atr) {
        try {
            Map<String, Object> resp = mapper.readValue(raw, Map.class);
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            if (content == null || content.isEmpty()) return java.util.Optional.empty();
            String text = (String) content.get(0).get("text");
            int start = text.indexOf('{');
            int end   = text.lastIndexOf('}') + 1;
            if (start < 0 || end <= start) return java.util.Optional.empty();
            Map<String, Object> r = mapper.readValue(text.substring(start, end), Map.class);

            if (!Boolean.TRUE.equals(r.get("setup_found"))) return java.util.Optional.empty();

            int score = r.get("score") instanceof Number ? ((Number) r.get("score")).intValue() : 0;
            if (score < 65) return java.util.Optional.empty(); // proactive bar is higher than validator

            String dir  = (String) r.getOrDefault("direction", "long");
            double entry = toDouble(r.get("entry"),     currentPrice);
            double sl    = toDouble(r.get("stop_loss"), currentPrice - atr);
            double tp    = toDouble(r.get("take_profit"),currentPrice + 2 * atr);
            String pattern = (String) r.getOrDefault("pattern", "Vision pattern");
            String reason  = (String) r.getOrDefault("reason",  "");

            // Sanity-check: SL and TP must be on correct sides of entry
            if ("long".equals(dir)  && (sl >= entry || tp <= entry)) return java.util.Optional.empty();
            if ("short".equals(dir) && (sl <= entry || tp >= entry)) return java.util.Optional.empty();

            return java.util.Optional.of(new VisionSetup(ticker, dir, entry, sl, tp, score, pattern, reason));
        } catch (Exception e) {
            log.warn("VISION_SCAN_PARSE {} — {}", ticker, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private double toDouble(Object v, double fallback) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return fallback; }
    }

    private double[] calcVwapFromBars(List<OHLCV> bars) {
        double[] vwap = new double[bars.size()];
        double cumPV = 0, cumVol = 0;
        for (int i = 0; i < bars.size(); i++) {
            OHLCV b = bars.get(i);
            double tp = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
            cumPV  += tp * b.getVolume();
            cumVol += b.getVolume();
            vwap[i] = cumVol > 0 ? cumPV / cumVol : b.getClose();
        }
        return vwap;
    }

    private double calcAtr(List<OHLCV> bars, int period) {
        if (bars.size() < 2) return 0;
        double sum = 0; int count = 0;
        for (int i = Math.max(1, bars.size() - period); i < bars.size(); i++) {
            OHLCV cur = bars.get(i), prev = bars.get(i - 1);
            double tr = Math.max(cur.getHigh() - cur.getLow(),
                        Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                 Math.abs(cur.getLow()  - prev.getClose())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private String buildPrompt(TradeSetup s) {
        return String.format(
            "You are analyzing a 5-minute candlestick chart for an options trading signal.\n\n" +
            "Signal details:\n" +
            "- Ticker: %s\n" +
            "- Direction: %s\n" +
            "- Strategy: %s\n" +
            "- Entry: %.2f | Stop Loss: %.2f | Take Profit: %.2f\n" +
            "- Rule-based confidence: %d/100\n\n" +
            "Look at the chart and evaluate:\n" +
            "1. Is the trend aligned with the signal direction?\n" +
            "2. Does volume support the move (increasing on the signal bar)?\n" +
            "3. Is price at a meaningful level (VWAP, support/resistance) or mid-range noise?\n" +
            "4. Do the candles show conviction (strong close, not doji/indecision at entry)?\n" +
            "5. Any red flags — exhaustion, overextension, volume dry-up?\n\n" +
            "Respond ONLY with valid JSON, nothing else:\n" +
            "{\"approve\": true/false, \"score\": 0-100, \"reason\": \"one sentence\"}",
            s.getTicker(), s.getDirection(), s.getVolatility(),
            s.getEntry(), s.getStopLoss(), s.getTakeProfit(), s.getConfidence()
        );
    }

    @SuppressWarnings("unchecked")
    private VisionVerdict parseVerdict(String raw, String ticker) {
        try {
            Map<String, Object> resp = mapper.readValue(raw, Map.class);
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            if (content == null || content.isEmpty()) return VisionVerdict.failOpen("empty response");
            String text = (String) content.get(0).get("text");
            // Extract JSON from response (Claude sometimes wraps in markdown)
            int start = text.indexOf('{');
            int end   = text.lastIndexOf('}') + 1;
            if (start < 0 || end <= start) return VisionVerdict.failOpen("no JSON in response");
            Map<String, Object> verdict = mapper.readValue(text.substring(start, end), Map.class);
            boolean approve = Boolean.TRUE.equals(verdict.get("approve"));
            int score = verdict.get("score") instanceof Number ? ((Number) verdict.get("score")).intValue() : 50;
            String reason = (String) verdict.getOrDefault("reason", "");
            // Apply minimum score gate
            if (score < MIN_SCORE_TO_APPROVE) approve = false;
            return new VisionVerdict(approve, score, reason, false);
        } catch (Exception e) {
            log.warn("VISION_PARSE_ERROR {} — {}", ticker, e.getMessage());
            return VisionVerdict.failOpen("parse error");
        }
    }
}
