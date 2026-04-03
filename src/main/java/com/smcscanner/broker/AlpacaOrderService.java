package com.smcscanner.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    // Safety tracking
    private final Map<String, Integer> dailyOrderCount = new ConcurrentHashMap<>();
    private volatile double dailyPnl = 0.0;
    private volatile String lastResetDate = "";

    // ── Trailing stop tracking ──────────────────────────────────────────────
    // Tracks each filled position: original entry, SL, TP, current trail level
    public record TrackedPosition(String symbol, String direction, double entry, double stopLoss,
                                   double takeProfit, String orderId, String stopOrderId,
                                   int trailLevel) {} // trailLevel: 0=original, 1=BE, 2=50%, 3=75%
    private final Map<String, TrackedPosition> trackedPositions = new ConcurrentHashMap<>();

    // Config defaults (overridden by env vars)
    private static final double DEFAULT_MAX_POSITION = 500.0;   // max $ per trade
    private static final double DEFAULT_DAILY_LOSS_LIMIT = -200.0; // stop trading after $200 loss
    private static final int    DEFAULT_MAX_DAILY_ORDERS = 10;
    private static final int    DEFAULT_MIN_CONFIDENCE = 75;     // only auto-trade 75+ confidence

    public AlpacaOrderService(ScannerConfig config) {
        this.config = config;
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
                            s.getStopLoss(), s.getTakeProfit(), orderId, null, 0));

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

    // ── Trailing Stop Management ────────────────────────────────────────────

    /**
     * Check all tracked positions and adjust stop losses based on profit level.
     * Called every 30s by the scheduler during market hours.
     *
     * Trail levels:
     *   Level 0 → original SL (no change)
     *   Level 1 → at 1:1 R:R (50% to TP): move SL to breakeven (entry)
     *   Level 2 → at 75% to TP: move SL to lock in 50% of profit
     *   Level 3 → at 90% to TP: move SL to lock in 75% of profit
     */
    public void checkTrailingStops() {
        if (!isEnabled() || trackedPositions.isEmpty()) return;

        // Fetch current positions from Alpaca
        List<Map<String, Object>> positions = getPositions();
        if (positions.isEmpty()) {
            // No open positions — clear tracking (positions were closed by SL/TP)
            if (!trackedPositions.isEmpty()) {
                log.info("TRAIL: No open positions, clearing {} tracked entries", trackedPositions.size());
                trackedPositions.clear();
            }
            return;
        }

        // Build symbol→currentPrice map
        Map<String, Double> currentPrices = new HashMap<>();
        for (Map<String, Object> pos : positions) {
            String symbol = (String) pos.get("symbol");
            try {
                double price = Double.parseDouble((String) pos.get("current_price"));
                currentPrices.put(symbol, price);
            } catch (Exception ignored) {}
        }

        // Check each tracked position
        for (Map.Entry<String, TrackedPosition> e : trackedPositions.entrySet()) {
            String symbol = e.getKey();
            TrackedPosition tp = e.getValue();
            Double currentPrice = currentPrices.get(symbol);

            if (currentPrice == null) {
                // Position closed (hit SL or TP) — remove from tracking
                log.info("TRAIL: {} no longer held — removing from tracker", symbol);
                trackedPositions.remove(symbol);
                continue;
            }

            boolean isLong = "long".equals(tp.direction());
            double entry = tp.entry();
            double origSL = tp.stopLoss();
            double target = tp.takeProfit();
            double risk = Math.abs(entry - origSL);
            double totalMove = Math.abs(target - entry);

            // Calculate how far price has moved toward TP (0.0 = at entry, 1.0 = at TP)
            double progress = isLong
                    ? (currentPrice - entry) / totalMove
                    : (entry - currentPrice) / totalMove;

            // Determine new trail level based on progress
            int newLevel = tp.trailLevel();
            double newStopPrice = 0;

            if (progress >= 0.90 && tp.trailLevel() < 3) {
                // 90%+ to TP → lock in 75% of profit
                newLevel = 3;
                double profit75 = totalMove * 0.75;
                newStopPrice = isLong ? entry + profit75 : entry - profit75;
                log.info("TRAIL L3: {} at {}% to TP → SL to ${} (lock 75% profit)",
                        symbol, String.format("%.1f", progress * 100), String.format("%.2f", newStopPrice));
            } else if (progress >= 0.75 && tp.trailLevel() < 2) {
                // 75%+ to TP → lock in 50% of profit
                newLevel = 2;
                double profit50 = totalMove * 0.50;
                newStopPrice = isLong ? entry + profit50 : entry - profit50;
                log.info("TRAIL L2: {} at {}% to TP → SL to ${} (lock 50% profit)",
                        symbol, String.format("%.1f", progress * 100), String.format("%.2f", newStopPrice));
            } else if (progress >= 0.50 && tp.trailLevel() < 1) {
                // 50%+ to TP (1:1 R:R) → move to breakeven
                newLevel = 1;
                newStopPrice = entry;
                log.info("TRAIL L1: {} at {}% to TP → SL to BE ${}",
                        symbol, String.format("%.1f", progress * 100), String.format("%.2f", newStopPrice));
            }

            if (newLevel > tp.trailLevel() && newStopPrice > 0) {
                // Find and update the stop order
                boolean updated = updateStopOrder(symbol, newStopPrice, isLong);
                if (updated) {
                    trackedPositions.put(symbol, new TrackedPosition(
                            tp.symbol(), tp.direction(), tp.entry(), tp.stopLoss(),
                            tp.takeProfit(), tp.orderId(), tp.stopOrderId(), newLevel));
                    log.info("TRAIL: {} SL moved to ${} (level {}→{})",
                            symbol, String.format("%.2f", newStopPrice), tp.trailLevel(), newLevel);
                }
            }
        }
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
