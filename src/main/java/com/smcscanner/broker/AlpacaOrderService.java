package com.smcscanner.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Alpaca Trading API integration for automated order placement.
 *
 * Supports both paper trading and live trading via configuration.
 * Paper: https://paper-api.alpaca.markets
 * Live:  https://api.alpaca.markets
 *
 * Places bracket orders (entry + SL + TP) for each scanner alert.
 * Includes safety guards: max position size, daily loss limit, confidence gate.
 */
@Service
public class AlpacaOrderService {
    private static final Logger log = LoggerFactory.getLogger(AlpacaOrderService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final ScannerConfig config;
    private final PolygonClient polygon;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    // Safety tracking
    private final Map<String, Integer> dailyOrderCount = new ConcurrentHashMap<>();
    private volatile double dailyPnl = 0.0;
    private volatile String lastResetDate = "";

    // ── Trailing stop tracking ──────────────────────────────────────────────
    // Tracks each filled position: original entry, SL, TP, current trail level
    // consecutiveCloses = number of consecutive 5m candle closes above the next trail threshold
    public record TrackedPosition(String symbol, String direction, double entry, double stopLoss,
                                   double takeProfit, String orderId, String stopOrderId,
                                   int trailLevel, int consecutiveCloses) {} // trailLevel: 0=original, 1=BE, 2=50%, 3=75%
    private final Map<String, TrackedPosition> trackedPositions = new ConcurrentHashMap<>();
    // Track last processed candle timestamp to avoid re-processing same candle
    private final Map<String, String> lastProcessedCandle = new ConcurrentHashMap<>();
    // Required consecutive candle closes above threshold before trailing
    private static final int REQUIRED_CONSECUTIVE_CLOSES = 2;
    // ATR buffer: trail SL this fraction of ATR below the profit level (prevents wick stopouts)
    private static final double ATR_BUFFER_MULT = 0.5;

    // Config defaults (overridden by env vars)
    private static final double DEFAULT_MAX_POSITION = 500.0;   // max $ per trade
    private static final double DEFAULT_DAILY_LOSS_LIMIT = -200.0; // stop trading after $200 loss
    private static final int    DEFAULT_MAX_DAILY_ORDERS = 10;
    private static final int    DEFAULT_MIN_CONFIDENCE = 75;     // only auto-trade 75+ confidence

    public AlpacaOrderService(ScannerConfig config, PolygonClient polygon) {
        this.config = config;
        this.polygon = polygon;
    }

    /** Check if Alpaca trading is enabled and configured. */
    public boolean isEnabled() {
        String key = config.getAlpacaApiKey();
        return key != null && !key.isBlank() && config.isAlpacaEnabled();
    }

    /**
     * Place a bracket order for a trade setup.
     * Returns the Alpaca order ID if successful, null if skipped/failed.
     */
    public String placeOrder(TradeSetup s) {
        if (!isEnabled()) return null;

        // Reset daily counters at market open
        resetDailyIfNeeded();

        // ── Safety checks ────────────────────────────────────────────────────
        if (s.getConfidence() < getMinAutoTradeConfidence()) {
            log.info("ALPACA SKIP {} conf={} < min={}", s.getTicker(), s.getConfidence(), getMinAutoTradeConfidence());
            return null;
        }

        if (dailyPnl <= getDailyLossLimit()) {
            log.warn("ALPACA DAILY_LOSS_LIMIT reached (pnl=${}) — no more orders today", dailyPnl);
            return null;
        }

        int todayOrders = dailyOrderCount.values().stream().mapToInt(Integer::intValue).sum();
        if (todayOrders >= getMaxDailyOrders()) {
            log.warn("ALPACA MAX_DAILY_ORDERS reached ({}) — no more orders today", todayOrders);
            return null;
        }

        // Skip crypto (Alpaca handles crypto differently)
        if (s.getTicker().startsWith("X:")) {
            log.debug("ALPACA SKIP crypto ticker {}", s.getTicker());
            return null;
        }

        try {
            // Calculate position size
            double maxPos = getMaxPositionSize();
            int qty = Math.max(1, (int)(maxPos / s.getEntry()));

            // Build bracket order
            boolean isLong = "long".equals(s.getDirection());
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("symbol", s.getTicker());
            order.put("qty", String.valueOf(qty));
            order.put("side", isLong ? "buy" : "sell");
            order.put("type", "limit");
            order.put("limit_price", String.format("%.2f", s.getEntry()));
            order.put("time_in_force", "day"); // cancel at EOD if not filled

            // Bracket legs: take profit + stop loss
            order.put("order_class", "bracket");
            order.put("take_profit", Map.of("limit_price", String.format("%.2f", s.getTakeProfit())));
            order.put("stop_loss", Map.of("stop_price", String.format("%.2f", s.getStopLoss())));

            String json = mapper.writeValueAsString(order);
            log.info("ALPACA ORDER {} {} qty={} entry=${} sl=${} tp=${} | {}",
                    isLong ? "BUY" : "SELL", s.getTicker(), qty, s.getEntry(),
                    s.getStopLoss(), s.getTakeProfit(), isPaper() ? "PAPER" : "LIVE");

            // POST to Alpaca
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.isSuccessful()) {
                    JsonNode node = mapper.readTree(body);
                    String orderId = node.path("id").asText();
                    String status = node.path("status").asText();
                    log.info("ALPACA ORDER PLACED: {} {} id={} status={}", s.getTicker(), s.getDirection(), orderId, status);

                    // Track for trailing stop management
                    trackedPositions.put(s.getTicker(), new TrackedPosition(
                            s.getTicker(), s.getDirection(), s.getEntry(),
                            s.getStopLoss(), s.getTakeProfit(), orderId, null, 0, 0));

                    dailyOrderCount.merge(s.getTicker(), 1, Integer::sum);
                    return orderId;
                } else {
                    JsonNode err = mapper.readTree(body);
                    String msg = err.path("message").asText(body);
                    log.error("ALPACA ORDER FAILED {}: {} ({})", s.getTicker(), msg, resp.code());
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("ALPACA ORDER ERROR {}: {}", s.getTicker(), e.getMessage());
            return null;
        }
    }

    /** Get current account info (buying power, equity, etc.). */
    public Map<String, Object> getAccount() {
        if (!isEnabled()) return Map.of("error", "Alpaca not enabled");
        try {
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/account")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .get().build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "{}";
                if (resp.isSuccessful()) {
                    JsonNode node = mapper.readTree(body);
                    Map<String, Object> acct = new LinkedHashMap<>();
                    acct.put("equity", node.path("equity").asText());
                    acct.put("buying_power", node.path("buying_power").asText());
                    acct.put("cash", node.path("cash").asText());
                    acct.put("portfolio_value", node.path("portfolio_value").asText());
                    acct.put("day_trade_count", node.path("daytrade_count").asInt());
                    acct.put("pattern_day_trader", node.path("pattern_day_trader").asBoolean());
                    acct.put("status", node.path("status").asText());
                    acct.put("paper", isPaper());
                    return acct;
                } else {
                    return Map.of("error", "API error: " + resp.code());
                }
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** Get all open positions. */
    public List<Map<String, Object>> getPositions() {
        if (!isEnabled()) return List.of();
        try {
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/positions")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .get().build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                if (resp.isSuccessful()) {
                    JsonNode arr = mapper.readTree(body);
                    List<Map<String, Object>> positions = new ArrayList<>();
                    for (JsonNode n : arr) {
                        Map<String, Object> pos = new LinkedHashMap<>();
                        pos.put("symbol", n.path("symbol").asText());
                        pos.put("qty", n.path("qty").asText());
                        pos.put("side", n.path("side").asText());
                        pos.put("avg_entry", n.path("avg_entry_price").asText());
                        pos.put("current_price", n.path("current_price").asText());
                        pos.put("unrealized_pl", n.path("unrealized_pl").asText());
                        pos.put("unrealized_plpc", n.path("unrealized_plpc").asText());
                        pos.put("market_value", n.path("market_value").asText());
                        positions.add(pos);
                    }
                    return positions;
                }
                return List.of();
            }
        } catch (Exception e) {
            log.error("ALPACA positions error: {}", e.getMessage());
            return List.of();
        }
    }

    /** Get today's orders. */
    public List<Map<String, Object>> getOrders() {
        if (!isEnabled()) return List.of();
        try {
            String today = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders?status=all&after=" + today + "T00:00:00Z&limit=50")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .get().build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                if (resp.isSuccessful()) {
                    JsonNode arr = mapper.readTree(body);
                    List<Map<String, Object>> orders = new ArrayList<>();
                    for (JsonNode n : arr) {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("id", n.path("id").asText());
                        o.put("symbol", n.path("symbol").asText());
                        o.put("side", n.path("side").asText());
                        o.put("qty", n.path("qty").asText());
                        o.put("type", n.path("type").asText());
                        o.put("status", n.path("status").asText());
                        o.put("limit_price", n.path("limit_price").asText());
                        o.put("filled_avg_price", n.path("filled_avg_price").asText());
                        o.put("created_at", n.path("created_at").asText());
                        orders.add(o);
                    }
                    return orders;
                }
                return List.of();
            }
        } catch (Exception e) {
            log.error("ALPACA orders error: {}", e.getMessage());
            return List.of();
        }
    }

    /** Cancel all open orders. */
    public boolean cancelAllOrders() {
        if (!isEnabled()) return false;
        try {
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .delete().build();
            try (Response resp = http.newCall(req).execute()) {
                log.info("ALPACA cancel all orders: {}", resp.code());
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            log.error("ALPACA cancel error: {}", e.getMessage());
            return false;
        }
    }

    // ── Smart Trailing Stop Management ─────────────────────────────────────
    //
    // Uses CONFIRMED 5-minute candle closes instead of raw live price.
    // This prevents getting stopped out by wicks.
    //
    // Approach:
    //   1. Fetch last few 5m bars from Polygon (confirmed closes only)
    //   2. Only process if we see a NEW candle close (skip duplicate checks)
    //   3. Check if the candle close is above the next trail threshold
    //   4. Require 2 CONSECUTIVE closes above threshold before moving SL
    //   5. Apply 0.5 ATR buffer below the profit lock level
    //
    // Trail levels:
    //   Level 0 → original SL (no change)
    //   Level 1 → at 50% to TP (1:1 R:R): move SL to breakeven + ATR buffer
    //   Level 2 → at 75% to TP: lock in 50% of profit - ATR buffer
    //   Level 3 → at 90% to TP: lock in 75% of profit - ATR buffer

    /**
     * Smart trailing stop check — called every 5 minutes by scheduler.
     * Uses confirmed 5m candle closes + 2 consecutive confirmations + ATR buffer.
     */
    public void checkTrailingStops() {
        if (!isEnabled() || trackedPositions.isEmpty()) return;

        // Fetch current positions from Alpaca to confirm they're still open
        List<Map<String, Object>> positions = getPositions();
        if (positions.isEmpty()) {
            if (!trackedPositions.isEmpty()) {
                log.info("TRAIL: No open positions, clearing {} tracked entries", trackedPositions.size());
                trackedPositions.clear();
                lastProcessedCandle.clear();
            }
            return;
        }

        // Build set of currently held symbols
        Set<String> heldSymbols = new HashSet<>();
        for (Map<String, Object> pos : positions) {
            heldSymbols.add((String) pos.get("symbol"));
        }

        // Check each tracked position using candle closes
        for (Map.Entry<String, TrackedPosition> e : new ArrayList<>(trackedPositions.entrySet())) {
            String symbol = e.getKey();
            TrackedPosition tp = e.getValue();

            if (!heldSymbols.contains(symbol)) {
                log.info("TRAIL: {} no longer held — removing from tracker", symbol);
                trackedPositions.remove(symbol);
                lastProcessedCandle.remove(symbol);
                continue;
            }

            try {
                processTrailingForSymbol(symbol, tp);
            } catch (Exception ex) {
                log.error("TRAIL: Error processing {} — {}", symbol, ex.getMessage());
            }
        }
    }

    /**
     * Process trailing stop for a single symbol using 5m candle closes.
     */
    private void processTrailingForSymbol(String symbol, TrackedPosition tp) {
        // Fetch last 20 confirmed 5m bars from Polygon
        List<OHLCV> bars = polygon.getBars(symbol, "5m", 20);
        if (bars.size() < 3) {
            log.debug("TRAIL: {} — not enough 5m bars ({})", symbol, bars.size());
            return;
        }

        // The LAST bar may still be forming (current candle). Use second-to-last as the latest CONFIRMED close.
        // Polygon returns bars in chronological order. The last complete bar is bars[size-2]
        // if we're mid-candle, or bars[size-1] if the candle just closed.
        // To be safe, always use the second-to-last bar as the confirmed close.
        OHLCV confirmedBar = bars.get(bars.size() - 2);
        String candleTs = confirmedBar.getTimestamp();

        // Skip if we already processed this candle
        String lastTs = lastProcessedCandle.get(symbol);
        if (candleTs.equals(lastTs)) {
            return; // same candle, no new data
        }
        lastProcessedCandle.put(symbol, candleTs);

        double candleClose = confirmedBar.getClose();
        boolean isLong = "long".equals(tp.direction());
        double entry = tp.entry();
        double target = tp.takeProfit();
        double totalMove = Math.abs(target - entry);

        // Compute ATR from recent 5m bars (use last 14 bars for ATR)
        double atr = computeAtr5m(bars);

        // Calculate progress based on CANDLE CLOSE (not live price)
        double progress = isLong
                ? (candleClose - entry) / totalMove
                : (entry - candleClose) / totalMove;

        // Determine what the NEXT level would be
        int candidateLevel = tp.trailLevel();
        if (progress >= 0.90 && tp.trailLevel() < 3) {
            candidateLevel = 3;
        } else if (progress >= 0.75 && tp.trailLevel() < 2) {
            candidateLevel = 2;
        } else if (progress >= 0.50 && tp.trailLevel() < 1) {
            candidateLevel = 1;
        }

        // If candle close qualifies for a higher level, increment consecutive count
        if (candidateLevel > tp.trailLevel()) {
            int newConsecutive = tp.consecutiveCloses() + 1;
            log.info("TRAIL: {} candle close ${} — {}% to TP — L{} confirmation {}/{}",
                    symbol, String.format("%.2f", candleClose),
                    String.format("%.1f", progress * 100),
                    candidateLevel, newConsecutive, REQUIRED_CONSECUTIVE_CLOSES);

            if (newConsecutive >= REQUIRED_CONSECUTIVE_CLOSES) {
                // ✅ Confirmed! Move the stop loss with ATR buffer
                double atrBuffer = atr * ATR_BUFFER_MULT;
                double rawStopPrice;

                if (candidateLevel == 1) {
                    // Breakeven: entry + small buffer (don't lose money on wicks)
                    rawStopPrice = isLong ? entry + atrBuffer * 0.2 : entry - atrBuffer * 0.2;
                } else if (candidateLevel == 2) {
                    // Lock 50% profit
                    double profit50 = totalMove * 0.50;
                    rawStopPrice = isLong ? entry + profit50 : entry - profit50;
                } else {
                    // Lock 75% profit
                    double profit75 = totalMove * 0.75;
                    rawStopPrice = isLong ? entry + profit75 : entry - profit75;
                }

                // Apply ATR buffer: for longs, SL sits BELOW the raw level; for shorts, ABOVE
                double bufferedStop = isLong
                        ? rawStopPrice - atrBuffer
                        : rawStopPrice + atrBuffer;

                // Ensure we never move SL backwards (always tighter)
                if (isLong && bufferedStop <= tp.stopLoss()) {
                    log.info("TRAIL: {} buffered SL ${} <= current SL ${} — skipping",
                            symbol, String.format("%.2f", bufferedStop), String.format("%.2f", tp.stopLoss()));
                    return;
                }
                if (!isLong && bufferedStop >= tp.stopLoss()) {
                    log.info("TRAIL: {} buffered SL ${} >= current SL ${} — skipping",
                            symbol, String.format("%.2f", bufferedStop), String.format("%.2f", tp.stopLoss()));
                    return;
                }

                boolean updated = updateStopOrder(symbol, bufferedStop, isLong);
                if (updated) {
                    trackedPositions.put(symbol, new TrackedPosition(
                            tp.symbol(), tp.direction(), tp.entry(), bufferedStop,
                            tp.takeProfit(), tp.orderId(), tp.stopOrderId(), candidateLevel, 0));
                    log.info("TRAIL ✓ {} L{}→L{} | SL moved to ${} (raw=${}, ATR buffer=${}) | close=${}",
                            symbol, tp.trailLevel(), candidateLevel,
                            String.format("%.2f", bufferedStop),
                            String.format("%.2f", rawStopPrice),
                            String.format("%.2f", atrBuffer),
                            String.format("%.2f", candleClose));
                }
            } else {
                // Not enough confirmations yet — just update the counter
                trackedPositions.put(symbol, new TrackedPosition(
                        tp.symbol(), tp.direction(), tp.entry(), tp.stopLoss(),
                        tp.takeProfit(), tp.orderId(), tp.stopOrderId(), tp.trailLevel(), newConsecutive));
            }
        } else {
            // Candle close doesn't qualify — reset consecutive counter
            if (tp.consecutiveCloses() > 0) {
                log.debug("TRAIL: {} candle close ${} below L{} threshold — resetting consecutive count",
                        symbol, String.format("%.2f", candleClose), tp.trailLevel() + 1);
                trackedPositions.put(symbol, new TrackedPosition(
                        tp.symbol(), tp.direction(), tp.entry(), tp.stopLoss(),
                        tp.takeProfit(), tp.orderId(), tp.stopOrderId(), tp.trailLevel(), 0));
            }
        }
    }

    /**
     * Compute ATR from 5-minute bars (14-period ATR).
     */
    private double computeAtr5m(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period < 1) return 0.0;
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            OHLCV curr = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(),
                    Math.max(Math.abs(curr.getHigh() - prev.getClose()),
                             Math.abs(curr.getLow() - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    /**
     * Update the stop loss order for a position.
     * Alpaca approach: find the open stop order for this symbol, cancel it, place new one.
     */
    private boolean updateStopOrder(String symbol, double newStopPrice, boolean isLong) {
        try {
            // 1. Find open stop orders for this symbol
            Request listReq = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders?status=open&symbols=" + symbol + "&limit=50")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .get().build();

            String stopOrderId = null;
            int stopQty = 0;
            try (Response resp = http.newCall(listReq).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                JsonNode arr = mapper.readTree(body);
                for (JsonNode n : arr) {
                    String type = n.path("type").asText();
                    String side = n.path("side").asText();
                    // Stop order is the opposite side: long position has a "sell" stop
                    boolean isStopOrder = "stop".equals(type)
                            && ((isLong && "sell".equals(side)) || (!isLong && "buy".equals(side)));
                    if (isStopOrder) {
                        stopOrderId = n.path("id").asText();
                        stopQty = n.path("qty").asInt(1);
                        break;
                    }
                }
            }

            if (stopOrderId == null) {
                log.warn("TRAIL: No stop order found for {} — cannot update", symbol);
                return false;
            }

            // 2. Replace the stop order with PATCH (Alpaca supports order replacement)
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("stop_price", String.format("%.2f", newStopPrice));
            patch.put("qty", String.valueOf(stopQty));
            String patchJson = mapper.writeValueAsString(patch);

            Request patchReq = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders/" + stopOrderId)
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .patch(RequestBody.create(patchJson, JSON))
                    .build();

            try (Response resp = http.newCall(patchReq).execute()) {
                if (resp.isSuccessful()) {
                    log.info("TRAIL: {} stop order updated to ${}", symbol, String.format("%.2f", newStopPrice));
                    return true;
                } else {
                    String errBody = resp.body() != null ? resp.body().string() : "";
                    log.error("TRAIL: Failed to update stop for {} ({}): {}", symbol, resp.code(), errBody);
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("TRAIL: Error updating stop for {}: {}", symbol, e.getMessage());
            return false;
        }
    }

    /** Get tracked positions (for dashboard display). */
    public Map<String, TrackedPosition> getTrackedPositions() {
        return Collections.unmodifiableMap(trackedPositions);
    }

    // ── Configuration helpers ────────────────────────────────────────────────

    private String getBaseUrl() {
        String url = config.getAlpacaBaseUrl();
        if (url != null && !url.isBlank()) return url;
        return isPaper() ? "https://paper-api.alpaca.markets" : "https://api.alpaca.markets";
    }

    private boolean isPaper() {
        return config.isAlpacaPaper();
    }

    private double getMaxPositionSize() {
        double v = config.getAlpacaMaxPosition();
        return v > 0 ? v : DEFAULT_MAX_POSITION;
    }

    private double getDailyLossLimit() {
        double v = config.getAlpacaDailyLossLimit();
        return v < 0 ? v : DEFAULT_DAILY_LOSS_LIMIT;
    }

    private int getMaxDailyOrders() {
        int v = config.getAlpacaMaxDailyOrders();
        return v > 0 ? v : DEFAULT_MAX_DAILY_ORDERS;
    }

    private int getMinAutoTradeConfidence() {
        int v = config.getAlpacaMinConfidence();
        return v > 0 ? v : DEFAULT_MIN_CONFIDENCE;
    }

    private void resetDailyIfNeeded() {
        String today = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        if (!today.equals(lastResetDate)) {
            dailyOrderCount.clear();
            dailyPnl = 0.0;
            lastResetDate = today;
        }
    }

    /** Update daily P&L from position closes (called by resolver). */
    public void recordPnl(double pnl) {
        this.dailyPnl += pnl;
    }
}
