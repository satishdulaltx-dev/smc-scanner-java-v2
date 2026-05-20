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
    private static final int MIN_SCORE_TO_APPROVE = 55; // approve if Claude gives >= 55/100
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
