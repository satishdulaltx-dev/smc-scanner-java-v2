package com.smcscanner.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final String TRACKED_FILE_PATH = resolveStoragePath("tracked-positions.json");
    private static final String TRACKED_BACKUP_FILE_PATH = TRACKED_FILE_PATH + ".bak";

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
    // Hybrid live management:
    // - move SL to breakeven at 1R
    // - keep classic TP active until 1.5R
    // - once 1.5R is reached, switch to ATR trailing
    // peakClose = highest confirmed 5m close reached (for longs) / lowest (for shorts)
    // consecutiveReversal = number of consecutive closes moving against direction
    // optionsContract = OCC symbol (e.g. "AAPL250418C00200000") for options trades, null for equity
    // stopOrderId is reused for options-only metadata because options have no native stop order
    // entryEpochMs: wall-clock time when order was placed — used for scalp time exit
    public record TrackedPosition(String symbol, String direction, double entry, double stopLoss,
                                   double takeProfit, String orderId, String stopOrderId,
                                   double peakClose, int consecutiveReversal, String optionsContract,
                                   long entryEpochMs) {
        // Backward-compat constructor for deserialization of existing tracked-positions.json (no entryEpochMs)
        public TrackedPosition(String symbol, String direction, double entry, double stopLoss,
                               double takeProfit, String orderId, String stopOrderId,
                               double peakClose, int consecutiveReversal, String optionsContract) {
            this(symbol, direction, entry, stopLoss, takeProfit, orderId, stopOrderId,
                 peakClose, consecutiveReversal, optionsContract, System.currentTimeMillis());
        }
    }
    private record HybridState(double originalStopLoss, double originalTakeProfit, boolean breakEvenArmed, boolean trailArmed, String brokerStopOrderId) {}
    private final Map<String, TrackedPosition> trackedPositions = new ConcurrentHashMap<>();
    // Track last processed candle timestamp to avoid re-processing same candle
    private final Map<String, String> lastProcessedCandle = new ConcurrentHashMap<>();
    // ATR multipliers for normal trail and reversal-tightened trail
    private static final double ATR_TRAIL_NORMAL   = 0.75; // trail at peak - 0.75 ATR while running
    private static final double ATR_TRAIL_REVERSAL = 0.30; // tighten to peak - 0.3 ATR on confirmed reversal
    private static final int    REVERSAL_CLOSES    = 2;    // consecutive closes against direction to confirm reversal
    private static final double HYBRID_BE_R        = 1.0;  // move SL to breakeven at 1R
    private static final double HYBRID_TRAIL_R     = 1.5;  // start ATR trailing only after 1.5R
    private static final double SCALP_BE_R         = 0.60; // scalp moves to BE faster
    private static final double SCALP_TRAIL_R      = 0.90; // scalp arms trail after first push
    private static final double SCALP_TRAIL_NORMAL = 0.35; // tighter trail for fast momentum
    private static final double SCALP_TRAIL_REVERSAL = 0.18;
    // Options P&L thresholds — trigger BE/trail based on dollar profit regardless of underlying R
    private static final double OPTIONS_PNL_BE_THRESHOLD    = 75.0;  // $75 unrealized → move SL to breakeven
    private static final double OPTIONS_PNL_TRAIL_THRESHOLD = 150.0; // $150 unrealized → arm ATR trail

    // Config defaults (overridden by env vars)
    private static final double DEFAULT_MAX_POSITION = 500.0;   // max $ per trade
    private static final double DEFAULT_DAILY_LOSS_LIMIT = -200.0; // stop trading after $200 loss
    private static final int    DEFAULT_MAX_DAILY_ORDERS = 10;
    private static final int    DEFAULT_MIN_CONFIDENCE = 75;     // only auto-trade 75+ confidence
    private record BuyingPowerSnapshot(double buyingPower, double optionsBuyingPower) {}
    private record PositionCheck(String symbol, TrackedPosition tracked) {
        boolean hasPosition() { return symbol != null && !symbol.isBlank(); }
    }

    public AlpacaOrderService(ScannerConfig config, PolygonClient polygon) {
        this.config = config;
        this.polygon = polygon;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        loadTrackedPositions();
    }

    public Map<String, Object> getTrackedStorageInfo() {
        File f = new File(TRACKED_FILE_PATH);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("path", f.getAbsolutePath());
        info.put("exists", f.exists());
        info.put("sizeBytes", f.exists() ? f.length() : 0L);
        info.put("trackedCount", trackedPositions.size());
        return info;
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
            PositionCheck existing = findExistingPositionForTicker(s.getTicker());
            if (existing.hasPosition()) {
                if (existing.tracked() != null && !existing.tracked().direction().equalsIgnoreCase(s.getDirection())) {
                    log.warn("ALPACA OPPOSITE SIGNAL {} new={} existing={} — closing current position {} and blocking re-entry this cycle",
                            s.getTicker(), s.getDirection(), existing.tracked().direction(), existing.symbol());
                    closeOptionsPosition(s.getTicker(), existing.symbol());
                } else {
                    log.warn("ALPACA SKIP {} {} — existing open position already held ({})",
                            s.getTicker(), s.getDirection(), existing.symbol());
                }
                return null;
            }

            // Options-only: never buy shares — skip if no contract was resolved
            if (!s.hasOptionsData() || s.getOptionsContract() == null || s.getOptionsContract().isBlank()) {
                log.warn("ALPACA SKIP {} — no options contract on setup (options-only mode) hasData={} contract={}",
                        s.getTicker(), s.hasOptionsData(), s.getOptionsContract());
                return null;
            }
            log.info("ALPACA PLACING OPTIONS ORDER {} contract={} premium={} conf={}",
                    s.getTicker(), s.getOptionsContract(), s.getOptionsPremium(), s.getConfidence());
            return placeOptionsOrder(s, null);
        } catch (Exception e) {
            log.error("ALPACA ORDER ERROR {}: {}", s.getTicker(), e.getMessage());
            return null;
        }
    }

    /** Place a paper test order using exactly one options contract. */
    public String placeTestOrder(TradeSetup s) {
        if (!isEnabled()) return null;
        try {
            if (!s.hasOptionsData() || s.getOptionsContract() == null || s.getOptionsContract().isBlank()) {
                log.warn("ALPACA TEST ORDER SKIP {} — no options contract on setup", s.getTicker());
                return null;
            }
            log.info("ALPACA TEST ORDER {} contract={} premium={} conf={} qty=1",
                    s.getTicker(), s.getOptionsContract(), s.getOptionsPremium(), s.getConfidence());
            return placeOptionsOrder(s, 1);
        } catch (Exception e) {
            log.error("ALPACA TEST ORDER ERROR {}: {}", s.getTicker(), e.getMessage());
            return null;
        }
    }

    /**
     * Place a limit buy order for an options contract.
     *
     * Alpaca options orders:
     *  - Symbol: OCC format e.g. "AAPL250418C00200000"
     *  - Side: always "buy" (we buy calls for longs, buy puts for shorts)
     *  - Qty: number of contracts (sized from buying power / (premium * 100))
     *  - Type: limit at the current ask (optionsPremium)
     *  - No bracket legs — options have defined max loss (premium paid)
     *  - time_in_force: "day"
     */
    private String placeOptionsOrder(TradeSetup s, Integer forcedContracts) {
        try {
            // Polygon returns "O:AAPL250418C00200000" — strip the "O:" prefix for Alpaca
            String rawSymbol = s.getOptionsContract();
            String occSymbol = rawSymbol.startsWith("O:") ? rawSymbol.substring(2) : rawSymbol;
            double premium   = s.getOptionsPremium();  // per-share premium (×100 = contract cost)
            if (premium <= 0) {
                log.warn("ALPACA OPTIONS SKIP {}: invalid premium={}", s.getTicker(), premium);
                return null;
            }

            BuyingPowerSnapshot power = getBuyingPowerSnapshot();
            double buyingPower   = power.buyingPower();
            double optionsBuyingPower = power.optionsBuyingPower();
            double contractCost  = premium * 100.0;  // 1 contract = 100 shares
            double maxPositionBudget = getMaxPositionBudget();
            double effectiveBudget = forcedContracts != null
                    ? contractCost * Math.max(1, forcedContracts)
                    : Math.min(maxPositionBudget, optionsBuyingPower > 0 ? optionsBuyingPower : buyingPower);
            int affordableContracts = Math.max(1, (int)(effectiveBudget / contractCost));
            int suggestedContracts = s.getOptionsSuggested() > 0 ? s.getOptionsSuggested() : affordableContracts;
            int contracts = forcedContracts != null
                    ? Math.max(1, forcedContracts)
                    : Math.max(1, Math.min(affordableContracts, suggestedContracts));

            // Options are always a "buy" — calls for LONG setups, puts for SHORT
            // asset_class MUST be "us_option" or Alpaca treats it as a stock order
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("symbol", occSymbol);
            order.put("qty", String.valueOf(contracts));
            order.put("side", "buy");
            order.put("type", "limit");
            order.put("limit_price", String.format("%.2f", premium));
            order.put("time_in_force", "day");
            order.put("asset_class", "us_option");

            String json = mapper.writeValueAsString(order);
            log.info("ALPACA OPTIONS ORDER ATTEMPT: symbol={} qty={} premium=${} totalCost=${} bp=${} optionsBp=${} budget=${} suggestedQty={} dir={} mode={}",
                    occSymbol, contracts,
                    String.format("%.2f", premium),
                    String.format("%.2f", contractCost * contracts),
                    String.format("%.2f", buyingPower),
                    String.format("%.2f", optionsBuyingPower),
                    String.format("%.2f", effectiveBudget),
                    suggestedContracts,
                    s.getDirection(), isPaper() ? "PAPER" : "LIVE");
            log.info("ALPACA OPTIONS ORDER CONTEXT: ticker={} rawContract={} normalizedContract={} hasOptionsData={} stopLoss={} takeProfit={} entry={}",
                    s.getTicker(), rawSymbol, occSymbol, s.hasOptionsData(),
                    String.format("%.2f", s.getStopLoss()),
                    String.format("%.2f", s.getTakeProfit()),
                    String.format("%.2f", s.getEntry()));
            log.info("ALPACA OPTIONS ORDER BODY: {}", json);

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
                    String status  = node.path("status").asText();
                    log.info("ALPACA OPTIONS ORDER PLACED: {} id={} status={} response={}",
                            occSymbol, orderId, status, shortenForLog(body));

                    // Track position: underlying entry/sl/tp for ATR trailing,
                    // optionsContract stored so we can sell-to-close when SL is hit
                    putTrackedPosition(s.getTicker(), new TrackedPosition(
                            s.getTicker(), s.getDirection(), s.getEntry(),
                            s.getStopLoss(), s.getTakeProfit(), orderId,
                            encodeHybridState(s.getStopLoss(), s.getTakeProfit(), false, false, null),
                            s.getEntry(), 0, occSymbol, System.currentTimeMillis()));

                    dailyOrderCount.merge(s.getTicker(), 1, Integer::sum);
                    return orderId;
                } else {
                    String msg = extractAlpacaError(body);
                    log.error("ALPACA OPTIONS ORDER FAILED {}: status={} message={} response={}",
                            occSymbol, resp.code(), msg, shortenForLog(body));
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("ALPACA OPTIONS ORDER ERROR {}: {}", s.getTicker(), e.getMessage());
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
                    Map<String, TrackedPosition> trackedByOption = new HashMap<>();
                    Map<String, TrackedPosition> trackedByUnderlying = new HashMap<>();
                    for (TrackedPosition tp : trackedPositions.values()) {
                        trackedByUnderlying.put(tp.symbol(), tp);
                        if (tp.optionsContract() != null && !tp.optionsContract().isBlank()) {
                            trackedByOption.put(tp.optionsContract(), tp);
                        }
                    }
                    for (JsonNode n : arr) {
                        Map<String, Object> pos = new LinkedHashMap<>();
                        String symbol = n.path("symbol").asText();
                        pos.put("symbol", symbol);
                        pos.put("qty", n.path("qty").asText());
                        pos.put("side", n.path("side").asText());
                        pos.put("avg_entry", n.path("avg_entry_price").asText());
                        pos.put("current_price", n.path("current_price").asText());
                        pos.put("unrealized_pl", n.path("unrealized_pl").asText());
                        pos.put("unrealized_plpc", n.path("unrealized_plpc").asText());
                        pos.put("market_value", n.path("market_value").asText());
                        String assetClass = n.path("asset_class").asText("us_equity");
                        pos.put("asset_class", assetClass);

                        TrackedPosition tracked = "us_option".equals(assetClass)
                                ? trackedByOption.get(symbol)
                                : trackedByUnderlying.get(symbol);
                        if (tracked != null) {
                            pos.put("tracked", true);
                            pos.put("tracked_underlying", tracked.symbol());
                            pos.put("tracked_direction", tracked.direction());
                            pos.put("tracked_entry", tracked.entry());
                            pos.put("tracked_stop_loss", tracked.stopLoss());
                            pos.put("tracked_take_profit", tracked.takeProfit());
                            pos.put("tracked_peak_close", tracked.peakClose());
                            pos.put("tracked_reversal_count", tracked.consecutiveReversal());
                            pos.put("tracked_options_contract", tracked.optionsContract());
                            HybridState hybridState = parseHybridState(tracked);
                            String trailLabel;
                            if (!hybridState.trailArmed()) {
                                trailLabel = hybridState.breakEvenArmed()
                                        ? "HYBRID BE ARMED"
                                        : "HYBRID PRE-BE";
                            } else {
                                trailLabel = tracked.consecutiveReversal() >= REVERSAL_CLOSES
                                        ? "HYBRID REVERSAL-TIGHT (0.3 ATR)"
                                        : "HYBRID NORMAL-TRAIL (0.75 ATR)";
                            }
                            pos.put("tracked_trail_label", trailLabel);
                            pos.put("tracked_trail_method", "App-managed hybrid: BE at 1R, trail after 1.5R on underlying price");
                        } else {
                            pos.put("tracked", false);
                        }
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
        return getOrders(1);
    }

    /** Get recent orders for the last N days. */
    public List<Map<String, Object>> getOrders(int lookbackDays) {
        if (!isEnabled()) return List.of();
        try {
            int safeLookbackDays = Math.max(1, lookbackDays);
            String after = ZonedDateTime.now(ET)
                    .minusDays(safeLookbackDays - 1L)
                    .toLocalDate()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders?status=all&after=" + after + "T00:00:00Z&limit=500&direction=desc")
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
                        o.put("filled_qty", n.path("filled_qty").asText());
                        o.put("type", n.path("type").asText());
                        o.put("status", n.path("status").asText());
                        o.put("limit_price", n.path("limit_price").asText());
                        o.put("filled_avg_price", n.path("filled_avg_price").asText());
                        o.put("created_at", n.path("created_at").asText());
                        o.put("filled_at", n.path("filled_at").asText());
                        o.put("updated_at", n.path("updated_at").asText());
                        o.put("asset_class", n.path("asset_class").asText());
                        orders.add(o);
                    }
                    return orders;
                }
                return List.of();
            }
        } catch (Exception e) {
            log.error("ALPACA orders error ({}d): {}", lookbackDays, e.getMessage());
            return List.of();
        }
    }

    /** Get recent fill activities for the last N days. */
    public List<Map<String, Object>> getFillActivities(int lookbackDays) {
        if (!isEnabled()) return List.of();
        try {
            int safeLookbackDays = Math.max(1, lookbackDays);
            String after = ZonedDateTime.now(ET)
                    .minusDays(safeLookbackDays - 1L)
                    .toLocalDate()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/account/activities/FILL?after=" + after + "&page_size=500&direction=desc")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .get().build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                if (!resp.isSuccessful()) return List.of();
                JsonNode arr = mapper.readTree(body);
                List<Map<String, Object>> activities = new ArrayList<>();
                for (JsonNode n : arr) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id", n.path("id").asText());
                    a.put("symbol", n.path("symbol").asText());
                    a.put("side", n.path("side").asText());
                    a.put("qty", n.path("qty").asText());
                    a.put("price", n.path("price").asText());
                    a.put("net_amount", n.path("net_amount").asText());
                    a.put("transaction_time", n.path("transaction_time").asText());
                    a.put("type", n.path("activity_type").asText("FILL"));
                    activities.add(a);
                }
                return activities;
            }
        } catch (Exception e) {
            log.error("ALPACA fill activities error ({}d): {}", lookbackDays, e.getMessage());
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
                persistTrackedPositions();
            }
            return;
        }

        // Build set of currently held symbols + options P&L map
        Set<String> heldSymbols = new HashSet<>();
        Map<String, Double> optionsPnlMap = new HashMap<>();
        for (Map<String, Object> pos : positions) {
            String posSymbol = (String) pos.get("symbol");
            heldSymbols.add(posSymbol);
            try {
                double pl = Double.parseDouble((String) pos.getOrDefault("unrealized_pl", "0"));
                optionsPnlMap.put(posSymbol, pl);
            } catch (Exception ignored) {}
        }

        // Check each tracked position using candle closes
        long nowMs = System.currentTimeMillis();
        for (Map.Entry<String, TrackedPosition> e : new ArrayList<>(trackedPositions.entrySet())) {
            String symbol = e.getKey();
            TrackedPosition tp = e.getValue();

            // For options: position shows the OCC symbol, not the underlying ticker
            String checkSymbol = (tp.optionsContract() != null && !tp.optionsContract().isBlank())
                    ? tp.optionsContract() : symbol;
            if (!heldSymbols.contains(checkSymbol)) {
                log.info("TRAIL: {} no longer held — removing from tracker", symbol);
                removeTrackedPosition(symbol);
                lastProcessedCandle.remove(symbol);
                continue;
            }

            // ── Scalp fail-safe: only kill stale options, not runners ───────
            // Strong scalp winners should be allowed to keep trailing. We only
            // force-close when the trade is still stale after enough time has
            // passed and the position never developed.
            long heldMinutes = (nowMs - tp.entryEpochMs()) / 60_000L;
            if (heldMinutes >= 30
                    && tp.optionsContract() != null && !tp.optionsContract().isBlank()
                    && Math.abs(tp.peakClose() - tp.entry()) < Math.abs(tp.entry() - tp.stopLoss()) * 0.35) {
                log.warn("SCALP_STALE_TIMEOUT: {} held {}min with weak progress — force-closing options",
                        symbol, heldMinutes);
                closeOptionsPosition(symbol, tp.optionsContract());
                removeTrackedPosition(symbol);
                lastProcessedCandle.remove(symbol);
                continue;
            }

            try {
                double optionsPnl = optionsPnlMap.getOrDefault(checkSymbol, 0.0);
                processTrailingForSymbol(symbol, tp, optionsPnl);
            } catch (Exception ex) {
                log.error("TRAIL: Error processing {} — {}", symbol, ex.getMessage());
            }
        }
    }

    /**
     * Process trailing stop for a single symbol using 5m candle closes.
     *
     * Hybrid ATR trailing logic:
     * - While price runs: trail SL at peak_close - 0.75 ATR (gives room to breathe)
     * - On reversal (2 consecutive closes against direction): tighten to peak_close - 0.3 ATR
     * - Hard floor: original SL — never go backwards
     * - Only activates when trade is in profit (trail > entry for longs)
     */
    private void processTrailingForSymbol(String symbol, TrackedPosition tp, double optionsPnlDollars) {
        // Determine timeframe before fetching: scalp trades trail on 1m bars, all others on 5m.
        // Pre-check scalpManaged using stored state so we pick the right resolution upfront.
        HybridState hybridPre = parseHybridState(tp);
        double originalStopPre = hybridPre.originalStopLoss();
        double riskPre = Math.abs(tp.entry() - originalStopPre);
        if (riskPre <= 0) riskPre = Math.abs(tp.entry() - tp.stopLoss());
        boolean isScalpPreCheck = riskPre > 0 && isScalpManaged(tp.entry(), originalStopPre, hybridPre.originalTakeProfit());
        String barTimeframe = isScalpPreCheck ? "1m" : "5m";
        int barCount = isScalpPreCheck ? 30 : 20; // 30×1m = 30 min of ATR context for scalp

        List<OHLCV> bars = polygon.getBars(symbol, barTimeframe, barCount);
        if (bars.size() < 3) {
            log.debug("TRAIL: {} — not enough {} bars ({})", symbol, barTimeframe, bars.size());
            return;
        }

        // Use second-to-last bar as confirmed close (last bar may still be forming)
        OHLCV confirmedBar = bars.get(bars.size() - 2);
        String candleTs = barTimeframe + ":" + confirmedBar.getTimestamp();

        // Skip if we already processed this candle
        String lastTs = lastProcessedCandle.get(symbol);
        if (candleTs.equals(lastTs)) return;
        lastProcessedCandle.put(symbol, candleTs);

        double candleClose = confirmedBar.getClose();
        boolean isLong = "long".equals(tp.direction());
        double atr = computeAtr5m(bars); // works for any timeframe — named for legacy
        if (atr <= 0) return;
        HybridState hybrid = hybridPre;
        double originalStop = originalStopPre;
        double risk = Math.abs(tp.entry() - originalStop);
        if (risk <= 0) risk = Math.abs(tp.entry() - tp.stopLoss());
        if (risk <= 0) return;
        boolean scalpManaged = isScalpManaged(tp.entry(), originalStop, hybrid.originalTakeProfit());
        double beTrigger = isLong
                ? tp.entry() + risk * (scalpManaged ? SCALP_BE_R : HYBRID_BE_R)
                : tp.entry() - risk * (scalpManaged ? SCALP_BE_R : HYBRID_BE_R);
        double trailTrigger = isLong
                ? tp.entry() + risk * (scalpManaged ? SCALP_TRAIL_R : HYBRID_TRAIL_R)
                : tp.entry() - risk * (scalpManaged ? SCALP_TRAIL_R : HYBRID_TRAIL_R);
        boolean breakEvenArmed = hybrid.breakEvenArmed();
        boolean trailArmed = hybrid.trailArmed();

        // ── Options P&L override: arm BE/trail based on dollar profit, not just underlying R ──
        // Underlying price moves slowly but options P&L can spike fast. Without this,
        // a $355 options gain can sit with the original SL because the underlying hasn't
        // reached the 1R / 1.5R threshold in price terms yet.
        boolean isOptionsPosition = tp.optionsContract() != null && !tp.optionsContract().isBlank();
        if (isOptionsPosition && optionsPnlDollars > 0) {
            if (!breakEvenArmed && optionsPnlDollars >= OPTIONS_PNL_BE_THRESHOLD) {
                breakEvenArmed = true;
                log.info("TRAIL OPTIONS_PNL_BE {}: unrealized P&L=${} >= ${}  — arming breakeven (underlying R not yet reached)",
                        symbol, String.format("%.2f", optionsPnlDollars), OPTIONS_PNL_BE_THRESHOLD);
            }
            if (!trailArmed && optionsPnlDollars >= OPTIONS_PNL_TRAIL_THRESHOLD) {
                trailArmed = true;
                log.info("TRAIL OPTIONS_PNL_TRAIL {}: unrealized P&L=${} >= ${} — arming ATR trail (underlying R not yet reached)",
                        symbol, String.format("%.2f", optionsPnlDollars), OPTIONS_PNL_TRAIL_THRESHOLD);
            }
        }

        // ── 1. Update peak close ──────────────────────────────────────────────
        double newPeak = tp.peakClose();
        if (isLong  && candleClose > newPeak) newPeak = candleClose;
        if (!isLong && candleClose < newPeak) newPeak = candleClose;

        if (!breakEvenArmed) {
            boolean touchedBe = isLong ? newPeak >= beTrigger : newPeak <= beTrigger;
            if (touchedBe) {
                breakEvenArmed = true;
                if ((isLong && tp.stopLoss() < tp.entry()) || (!isLong && tp.stopLoss() > tp.entry())) {
                    log.info("TRAIL HYBRID {}: 1R reached — moving SL to breakeven ${}",
                            symbol, String.format("%.2f", tp.entry()));
                    putTrackedPosition(symbol, new TrackedPosition(
                            tp.symbol(), tp.direction(), tp.entry(), tp.entry(),
                            tp.takeProfit(), tp.orderId(),
                            encodeHybridState(originalStop, hybrid.originalTakeProfit(), true, trailArmed, hybrid.brokerStopOrderId()),
                            newPeak, tp.consecutiveReversal(), tp.optionsContract(), tp.entryEpochMs()));
                    tp = trackedPositions.getOrDefault(symbol, tp);
                }
            }
        }
        if (!trailArmed) {
            trailArmed = isLong ? newPeak >= trailTrigger : newPeak <= trailTrigger;
            if (trailArmed) {
                log.info("TRAIL HYBRID {}: 1.5R reached — arming ATR trail", symbol);
            }
        }

        // ── 2. Detect reversal (close pulling back from peak) ─────────────────
        // A reversal close = candle moves against direction by > 0.2 ATR from peak
        boolean isReversalClose = isLong
                ? candleClose < newPeak - atr * 0.20
                : candleClose > newPeak + atr * 0.20;
        int newReversalCount = isReversalClose ? tp.consecutiveReversal() + 1 : 0;
        double progress = isLong ? newPeak - tp.entry() : tp.entry() - newPeak;
        boolean weakClose = isLong ? candleClose < confirmedBar.getOpen() : candleClose > confirmedBar.getOpen();
        long heldMinutes = Math.max(0L, (System.currentTimeMillis() - tp.entryEpochMs()) / 60_000L);

        // ── 3. Calculate target stop ──────────────────────────────────────────
        double atrMult = scalpManaged
                ? (newReversalCount >= 1 ? SCALP_TRAIL_REVERSAL : SCALP_TRAIL_NORMAL)
                : ((newReversalCount >= REVERSAL_CLOSES) ? ATR_TRAIL_REVERSAL : ATR_TRAIL_NORMAL);
        double targetStop = isLong
                ? newPeak - atr * atrMult
                : newPeak + atr * atrMult;

        // ── 3b. Enforce 1.5R floor once trail is armed ────────────────────────
        // Once trail arms, the stop must never retreat below the 1.5R profit level.
        // This guarantees that if SL is hit after trail arms, the exit is ≥ 1.5R profit.
        if (trailArmed) {
            if (isLong) {
                targetStop = Math.max(targetStop, trailTrigger);
            } else {
                targetStop = Math.min(targetStop, trailTrigger);
            }
        }

        // ── 4. Pre-trail hard take profit (matches hybrid backtest) ───────────────
        if (!trailArmed) {
            boolean tpHit = isLong
                    ? candleClose >= tp.takeProfit()
                    : candleClose <= tp.takeProfit();
            if (tpHit) {
                log.info("TRAIL HYBRID TP HIT {} underlying=${} TP=${} — closing position",
                        symbol, String.format("%.2f", candleClose), String.format("%.2f", tp.takeProfit()));
                if (isOptionsPosition) {
                    closeOptionsPosition(symbol, tp.optionsContract());
                } else {
                    closeEquityPosition(symbol);
                }
                removeTrackedPosition(symbol);
                lastProcessedCandle.remove(symbol);
                return;
            }
        }

        // ── 5. Check if underlying has breached the current stop level ────────────
        boolean slBreached = isLong
                ? candleClose <= tp.stopLoss()
                : candleClose >= tp.stopLoss();

        if (slBreached) {
            log.info("TRAIL SL HIT {} underlying=${} vs SL=${} — closing position",
                    symbol, String.format("%.2f", candleClose), String.format("%.2f", tp.stopLoss()));
            if (isOptionsPosition) {
                closeOptionsPosition(symbol, tp.optionsContract());
            } else {
                closeEquityPosition(symbol);
            }
            removeTrackedPosition(symbol);
            lastProcessedCandle.remove(symbol);
            return;
        }

        if (scalpManaged) {
            boolean immediateFailure = weakClose && progress < risk * 0.25;
            boolean noFollowThrough = heldMinutes >= 10 && progress < risk * 0.35;
            boolean bankOnStall = progress > 0 && (newReversalCount >= 1 || (heldMinutes >= 5 && weakClose));
            if (immediateFailure || noFollowThrough || bankOnStall) {
                log.info("SCALP_EXIT {} close=${} held={}m progressR={} weakClose={} reversalCount={} — closing to protect momentum edge",
                        symbol,
                        String.format("%.2f", candleClose),
                        heldMinutes,
                        String.format("%.2f", progress / risk),
                        weakClose,
                        newReversalCount);
                if (isOptionsPosition) {
                    closeOptionsPosition(symbol, tp.optionsContract());
                } else {
                    closeEquityPosition(symbol);
                }
                removeTrackedPosition(symbol);
                lastProcessedCandle.remove(symbol);
                return;
            }
        }

        // ── 6. Before trail activation, only keep BE + state updates ─────────────
        if (!trailArmed) {
            if (newPeak != tp.peakClose() || newReversalCount != tp.consecutiveReversal() || breakEvenArmed != hybrid.breakEvenArmed()) {
                putTrackedPosition(symbol, new TrackedPosition(
                        tp.symbol(), tp.direction(), tp.entry(), tp.stopLoss(),
                        tp.takeProfit(), tp.orderId(),
                        encodeHybridState(originalStop, hybrid.originalTakeProfit(), breakEvenArmed, false, hybrid.brokerStopOrderId()),
                        newPeak, newReversalCount, tp.optionsContract(), tp.entryEpochMs()));
            }
            return;
        }

        // ── 7. Dynamic TP: tracks SL with the same original bracket spread ─────────
        // As SL moves up, TP moves up by the same amount — neither is a fixed number.
        double bracketSpread = Math.abs(hybrid.originalTakeProfit() - hybrid.originalStopLoss());
        double dynamicTp = isLong ? targetStop + bracketSpread : targetStop - bracketSpread;

        // Check if price hit the dynamic TP
        boolean tpHit = isLong ? candleClose >= dynamicTp : candleClose <= dynamicTp;
        if (tpHit) {
            log.info("TRAIL TP HIT {} close=${} dynamicTP=${} — closing position",
                    symbol, String.format("%.2f", candleClose), String.format("%.2f", dynamicTp));
            if (isOptionsPosition) {
                closeOptionsPosition(symbol, tp.optionsContract());
            } else {
                closeEquityPosition(symbol);
            }
            removeTrackedPosition(symbol);
            lastProcessedCandle.remove(symbol);
            return;
        }

        // ── 8. Once trail is armed, use ATR trailing only if improving ───────────
        boolean inProfit  = isLong ? targetStop > tp.entry() : targetStop < tp.entry();
        boolean improving = isLong ? targetStop > tp.stopLoss() : targetStop < tp.stopLoss();

        if (inProfit && improving) {
            // Options: no stop order to patch — just update internal trail level
            // The next candle check will close if underlying crosses this new level
            if (isOptionsPosition) {
                String mode = newReversalCount >= REVERSAL_CLOSES ? "REVERSAL-TIGHT" : "NORMAL-TRAIL";
                log.info("TRAIL ✓ {} [OPTIONS/{}] peak=${} close=${} SL: ${} → ${} TP: ${} ({}x ATR)",
                        symbol, mode,
                        String.format("%.2f", newPeak), String.format("%.2f", candleClose),
                        String.format("%.2f", tp.stopLoss()), String.format("%.2f", targetStop),
                        String.format("%.2f", dynamicTp), atrMult);
                putTrackedPosition(symbol, new TrackedPosition(
                        tp.symbol(), tp.direction(), tp.entry(), targetStop,
                        dynamicTp, tp.orderId(),
                        encodeHybridState(originalStop, hybrid.originalTakeProfit(), true, true, hybrid.brokerStopOrderId()),
                        newPeak, newReversalCount, tp.optionsContract(), tp.entryEpochMs()));
                return;
            }
            boolean updated = updateStopOrder(symbol, targetStop, isLong, hybrid.brokerStopOrderId());
            if (updated) {
                String mode = newReversalCount >= REVERSAL_CLOSES ? "REVERSAL-TIGHT" : "NORMAL-TRAIL";
                log.info("TRAIL ✓ {} [{}] peak=${} close=${} SL: ${} → ${} TP: ${} ({}x ATR)",
                        symbol, mode,
                        String.format("%.2f", newPeak),
                        String.format("%.2f", candleClose),
                        String.format("%.2f", tp.stopLoss()),
                        String.format("%.2f", targetStop),
                        String.format("%.2f", dynamicTp),
                        atrMult);
                putTrackedPosition(symbol, new TrackedPosition(
                        tp.symbol(), tp.direction(), tp.entry(), targetStop,
                        dynamicTp, tp.orderId(),
                        encodeHybridState(originalStop, hybrid.originalTakeProfit(), true, true, hybrid.brokerStopOrderId()),
                        newPeak, newReversalCount, tp.optionsContract(), tp.entryEpochMs()));
                return;
            }
        }

        // No SL move — just update peak, reversal counter, and dynamic TP
        if (newPeak != tp.peakClose() || newReversalCount != tp.consecutiveReversal() || dynamicTp != tp.takeProfit()) {
            if (newReversalCount >= REVERSAL_CLOSES) {
                log.info("TRAIL: {} reversal confirmed ({} closes) — waiting for profit zone to tighten SL",
                        symbol, newReversalCount);
            }
            putTrackedPosition(symbol, new TrackedPosition(
                    tp.symbol(), tp.direction(), tp.entry(), tp.stopLoss(),
                    dynamicTp, tp.orderId(),
                    encodeHybridState(originalStop, hybrid.originalTakeProfit(), true, true, hybrid.brokerStopOrderId()),
                    newPeak, newReversalCount, tp.optionsContract(), tp.entryEpochMs()));
        }
    }

    private boolean isScalpManaged(double entry, double stopLoss, double takeProfit) {
        double risk = Math.abs(entry - stopLoss);
        double reward = Math.abs(takeProfit - entry);
        return risk > 0 && reward <= risk * 1.1;
    }

    /**
     * Close an options position by placing a market sell-to-close order.
     * Called when the underlying price hits the ATR trailing stop level.
     */
    private void closeOptionsPosition(String underlying, String occSymbol) {
        try {
            // Strip "O:" prefix if present (Polygon format → Alpaca format)
            if (occSymbol.startsWith("O:")) occSymbol = occSymbol.substring(2);

            // Find how many contracts we hold
            Request posReq = new Request.Builder()
                    .url(getBaseUrl() + "/v2/positions/" + occSymbol)
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .get().build();

            int qty = 1;
            try (Response resp = http.newCall(posReq).execute()) {
                if (resp.isSuccessful()) {
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    JsonNode node = mapper.readTree(body);
                    qty = Math.abs(node.path("qty").asInt(1));
                }
            }

            // Place market sell-to-close
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("symbol", occSymbol);
            order.put("qty", String.valueOf(qty));
            order.put("side", "sell");
            order.put("type", "market");
            order.put("time_in_force", "day");
            order.put("asset_class", "us_option");

            String json = mapper.writeValueAsString(order);
            log.info("TRAIL OPTIONS CLOSE ATTEMPT: underlying={} symbol={} qty={} mode={} body={}",
                    underlying, occSymbol, qty, isPaper() ? "PAPER" : "LIVE", json);
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/orders")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.isSuccessful()) {
                    log.info("TRAIL OPTIONS CLOSE ✓ {} ({}) qty={} response={}",
                            underlying, occSymbol, qty, shortenForLog(body));
                } else {
                    log.error("TRAIL OPTIONS CLOSE FAILED {} ({}): status={} message={} response={}",
                            underlying, occSymbol, resp.code(), extractAlpacaError(body), shortenForLog(body));
                }
            }
        } catch (Exception e) {
            log.error("TRAIL OPTIONS CLOSE ERROR {} ({}): {}", underlying, occSymbol, e.getMessage());
        }
    }

    /** Close an equity position at market (fallback for non-options). */
    private void closeEquityPosition(String symbol) {
        try {
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/positions/" + symbol)
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .delete().build();
            try (Response resp = http.newCall(req).execute()) {
                log.info("TRAIL EQUITY CLOSE {} ({})", symbol, resp.code());
            }
        } catch (Exception e) {
            log.error("TRAIL EQUITY CLOSE ERROR {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Recover a position that was found in Alpaca but not in the tracker or trade log.
     * Fetches daily ATR for the underlying and constructs reasonable SL/TP levels from
     * the current price so the trailing-stop system can manage the position going forward.
     *
     * Uses 1.5 ATR stop, 3.0 ATR TP (2:1 R:R) from current underlying close.
     * If the position is already tracked, returns the existing record unchanged.
     */
    public TrackedPosition tryRecoverPositionIntoTracker(String occSymbol, String underlying,
                                                         String direction, double fillPrice) {
        if (trackedPositions.containsKey(underlying)) return trackedPositions.get(underlying);

        List<OHLCV> dailyBars = polygon.getBars(underlying, "day", 20);
        if (dailyBars == null || dailyBars.size() < 5) {
            log.warn("AUTO_RECOVER {}: not enough daily bars to estimate SL/TP", underlying);
            return null;
        }

        double currentPrice = dailyBars.get(dailyBars.size() - 1).getClose();
        double atr = computeAtrFromBars(dailyBars, 14);
        if (atr <= 0) return null;

        boolean isLong = "long".equals(direction);
        double sl = round2(isLong ? currentPrice - atr * 1.5 : currentPrice + atr * 1.5);
        double tp = round2(isLong ? currentPrice + atr * 3.0 : currentPrice - atr * 3.0);

        TrackedPosition recovered = new TrackedPosition(
                underlying, direction, currentPrice, sl, tp,
                null,
                encodeHybridState(sl, tp, false, false, null),
                currentPrice, 0, occSymbol, System.currentTimeMillis());
        putTrackedPosition(underlying, recovered);
        log.info("AUTO_RECOVER {}: reconstructed tracking entry={} sl={} tp={} atr={} (1.5R/3R from current price)",
                underlying, String.format("%.2f", currentPrice), String.format("%.2f", sl),
                String.format("%.2f", tp), String.format("%.2f", atr));
        return recovered;
    }

    private double computeAtrFromBars(List<OHLCV> bars, int period) {
        int p = Math.min(period, bars.size() - 1);
        if (p < 1) return 0.0;
        double sum = 0;
        for (int i = bars.size() - p; i < bars.size(); i++) {
            OHLCV curr = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(),
                    Math.max(Math.abs(curr.getHigh() - prev.getClose()),
                             Math.abs(curr.getLow() - prev.getClose())));
            sum += tr;
        }
        return sum / p;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

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
    private boolean updateStopOrder(String symbol, double newStopPrice, boolean isLong, String knownStopOrderId) {
        try {
            String stopOrderId = knownStopOrderId;
            int stopQty = 0;

            if (stopOrderId != null && !stopOrderId.isBlank()) {
                Request getReq = new Request.Builder()
                        .url(getBaseUrl() + "/v2/orders/" + stopOrderId)
                        .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                        .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                        .get().build();
                try (Response resp = http.newCall(getReq).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "{}";
                    if (resp.isSuccessful()) {
                        JsonNode node = mapper.readTree(body);
                        stopQty = node.path("qty").asInt(1);
                    }
                }
            }

            // 1. Find open stop orders for this symbol if we don't already know the id
            if (stopOrderId == null || stopOrderId.isBlank()) {
                Request listReq = new Request.Builder()
                        .url(getBaseUrl() + "/v2/orders?status=open&symbols=" + symbol + "&limit=50")
                        .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                        .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                        .get().build();

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

    private HybridState parseHybridState(TrackedPosition tp) {
        if (tp.stopOrderId() == null || tp.stopOrderId().isBlank() || !tp.stopOrderId().startsWith("meta|")) {
            boolean breakEven = "long".equals(tp.direction()) ? tp.stopLoss() >= tp.entry() : tp.stopLoss() <= tp.entry();
            return new HybridState(tp.stopLoss(), tp.takeProfit(), breakEven, false, null);
        }
        double originalStop = tp.stopLoss();
        double originalTp   = tp.takeProfit();
        boolean be = false;
        boolean trail = false;
        String brokerStopOrderId = null;
        String[] parts = tp.stopOrderId().split("\\|");
        for (String part : parts) {
            if (part.startsWith("orig=")) {
                try { originalStop = Double.parseDouble(part.substring(5)); } catch (Exception ignored) {}
            } else if (part.startsWith("origtp=")) {
                try { originalTp = Double.parseDouble(part.substring(7)); } catch (Exception ignored) {}
            } else if (part.startsWith("be=")) {
                be = "1".equals(part.substring(3));
            } else if (part.startsWith("trail=")) {
                trail = "1".equals(part.substring(6));
            } else if (part.startsWith("broker=")) {
                String value = part.substring(7);
                brokerStopOrderId = value.isBlank() ? null : value;
            }
        }
        return new HybridState(originalStop, originalTp, be, trail, brokerStopOrderId);
    }

    private String encodeHybridState(double originalStopLoss, double originalTakeProfit, boolean breakEvenArmed, boolean trailArmed, String brokerStopOrderId) {
        String broker = brokerStopOrderId == null ? "" : brokerStopOrderId;
        return "meta|orig=" + String.format(Locale.US, "%.6f", originalStopLoss)
                + "|origtp=" + String.format(Locale.US, "%.6f", originalTakeProfit)
                + "|be=" + (breakEvenArmed ? "1" : "0")
                + "|trail=" + (trailArmed ? "1" : "0")
                + "|broker=" + broker;
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

    /**
     * Fetch available buying power from Alpaca account.
     * Falls back to DEFAULT_MAX_POSITION if account cannot be reached.
     */
    private BuyingPowerSnapshot getBuyingPowerSnapshot() {
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
                    double bp = node.path("buying_power").asDouble(0);
                    double optionsBp = node.path("options_buying_power").asDouble(0);
                    if (bp > 0) {
                        log.debug("ALPACA buying_power=${} options_buying_power=${}",
                                String.format("%.2f", bp), String.format("%.2f", optionsBp));
                        return new BuyingPowerSnapshot(bp, optionsBp);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ALPACA: could not fetch buying_power — falling back to ${}", DEFAULT_MAX_POSITION);
        }
        return new BuyingPowerSnapshot(DEFAULT_MAX_POSITION, DEFAULT_MAX_POSITION);
    }

    private double getMaxPositionBudget() {
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

    private String extractAlpacaError(String body) {
        if (body == null || body.isBlank()) return "(empty response body)";
        try {
            JsonNode err = mapper.readTree(body);
            String message = err.path("message").asText();
            if (message != null && !message.isBlank()) return message;
        } catch (Exception ignored) {
            // Fall back to raw body below.
        }
        return shortenForLog(body);
    }

    private String shortenForLog(String text) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    private void resetDailyIfNeeded() {
        String today = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        if (!today.equals(lastResetDate)) {
            dailyOrderCount.clear();
            dailyPnl = 0.0;
            lastResetDate = today;
        }
    }

    /**
     * Smart EOD close at 3:55 PM ET — close losers, keep winners running.
     *
     * Logic per position:
     *   unrealizedPlPct >= +0.5% → keep (already in profit, let it run overnight)
     *   unrealizedPlPct <  +0.5% → close (flat or losing, avoid overnight risk)
     *
     * Returns count of positions closed.
     */
    public int closeLosingPositions() {
        if (!isEnabled()) return 0;

        List<Map<String, Object>> positions = getPositions();
        if (positions.isEmpty()) return 0;

        // Cancel pending entry orders so no new fills happen at EOD
        cancelAllOrders();

        int closed = 0;
        List<String> kept = new ArrayList<>();

        for (Map<String, Object> pos : positions) {
            String symbol = (String) pos.get("symbol");
            double unrealizedPlPct;
            try {
                unrealizedPlPct = Double.parseDouble((String) pos.get("unrealized_plpc")) * 100;
            } catch (Exception e) {
                unrealizedPlPct = 0.0;
            }

            if (unrealizedPlPct >= 0.5) {
                kept.add(String.format("%s(+%.2f%%)", symbol, unrealizedPlPct));
                log.info("EOD CLOSE: keeping {} — unrealized={:+.2f}% (in profit)", symbol, unrealizedPlPct);
                continue;
            }

            // Close this position
            try {
                Request req = new Request.Builder()
                        .url(getBaseUrl() + "/v2/positions/" + symbol)
                        .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                        .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                        .delete().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        closed++;
                        log.info("EOD CLOSE: closed {} — unrealized={:+.2f}%", symbol, unrealizedPlPct);
                        removeTrackedPosition(symbol);
                        lastProcessedCandle.remove(symbol);
                    } else {
                        log.warn("EOD CLOSE: failed to close {} ({})", symbol, resp.code());
                    }
                }
            } catch (Exception e) {
                log.error("EOD CLOSE: error closing {}: {}", symbol, e.getMessage());
            }
        }

        if (!kept.isEmpty()) log.info("EOD CLOSE: kept open — {}", String.join(", ", kept));
        return closed;
    }

    /**
     * Force-close ALL open positions at EOD (fallback, used if closeLosingPositions not called).
     * Cancels all open orders first, then liquidates all positions.
     */
    public int closeAllPositions() {
        if (!isEnabled()) return 0;
        int closed = 0;

        cancelAllOrders();
        log.info("EOD CLOSE: cancelled all open orders");

        try {
            Request req = new Request.Builder()
                    .url(getBaseUrl() + "/v2/positions")
                    .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                    .delete().build();

            try (Response resp = http.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "[]";
                if (resp.isSuccessful()) {
                    JsonNode arr = mapper.readTree(body);
                    if (arr.isArray()) {
                        closed = arr.size();
                        for (JsonNode n : arr) log.info("EOD CLOSE: {} — {}", n.path("symbol").asText(), n.path("status").asText());
                    }
                    log.info("EOD CLOSE: liquidated {} position(s)", closed);
                }
            }
        } catch (Exception e) {
            log.error("EOD CLOSE: error liquidating positions — {}", e.getMessage());
        }

        trackedPositions.clear();
        lastProcessedCandle.clear();
        persistTrackedPositions();
        return closed;
    }

    /**
     * Close only equity (stock) positions — leaves options positions untouched.
     * Useful for liquidating leftover stock positions while keeping options open.
     * Returns number of equity positions closed.
     */
    public int closeAllEquityPositions() {
        if (!isEnabled()) return 0;

        List<Map<String, Object>> positions = getPositions();
        if (positions.isEmpty()) {
            log.info("CLOSE EQUITY: no open positions found");
            return 0;
        }

        int closed = 0;
        int skipped = 0;
        for (Map<String, Object> pos : positions) {
            String symbol     = String.valueOf(pos.get("symbol"));
            String assetClass = String.valueOf(pos.getOrDefault("asset_class", "us_equity"));

            if ("us_option".equals(assetClass)) {
                log.info("CLOSE EQUITY: skipping options position {}", symbol);
                skipped++;
                continue;
            }

            // It's equity — close it
            try {
                Request req = new Request.Builder()
                        .url(getBaseUrl() + "/v2/positions/" + symbol)
                        .addHeader("APCA-API-KEY-ID", config.getAlpacaApiKey())
                        .addHeader("APCA-API-SECRET-KEY", config.getAlpacaSecretKey())
                        .delete().build();
                try (Response resp = http.newCall(req).execute()) {
                    log.info("CLOSE EQUITY: {} → HTTP {}", symbol, resp.code());
                    if (resp.isSuccessful()) {
                        closed++;
                        // Remove from trailing stop tracker if tracked
                        removeTrackedPosition(symbol);
                        lastProcessedCandle.remove(symbol);
                    }
                }
            } catch (Exception e) {
                log.error("CLOSE EQUITY: error closing {} — {}", symbol, e.getMessage());
            }
        }

        log.info("CLOSE EQUITY: closed {} equity position(s), skipped {} options", closed, skipped);
        return closed;
    }

    /** Update daily P&L from position closes (called by resolver). */
    public void recordPnl(double pnl) {
        this.dailyPnl += pnl;
    }

    private void putTrackedPosition(String symbol, TrackedPosition tracked) {
        trackedPositions.put(symbol, tracked);
        persistTrackedPositions();
    }

    private void removeTrackedPosition(String symbol) {
        trackedPositions.remove(symbol);
        persistTrackedPositions();
    }

    private void loadTrackedPositions() {
        Map<String, TrackedPosition> loaded = readTrackedPositionsFile(new File(TRACKED_FILE_PATH), "primary");
        if (loaded.isEmpty()) {
            loaded = readTrackedPositionsFile(new File(TRACKED_BACKUP_FILE_PATH), "backup");
        }
        if (!loaded.isEmpty()) {
            trackedPositions.clear();
            trackedPositions.putAll(loaded);
            log.info("Loaded {} tracked option/equity positions from {}", trackedPositions.size(), TRACKED_FILE_PATH);
        }
    }

    private void persistTrackedPositions() {
        try {
            File f = new File(TRACKED_FILE_PATH);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Path target = f.toPath();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Path backup = Path.of(TRACKED_BACKUP_FILE_PATH);
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), trackedPositions);
            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Could not persist tracked positions: {}", e.getMessage());
        }
    }

    private Map<String, TrackedPosition> readTrackedPositionsFile(File file, String label) {
        if (!file.exists() || !file.isFile() || file.length() <= 0) return Map.of();
        try {
            return mapper.readValue(file, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not load {} tracked positions file {}: {}", label, file.getAbsolutePath(), e.getMessage());
            return Map.of();
        }
    }

    private static String resolveStoragePath(String fileName) {
        List<File> existingFiles = new ArrayList<>();
        List<File> writableTargets = new ArrayList<>();

        String envDir = System.getenv("TRADE_LOG_DIR");
        if (envDir != null && !envDir.isBlank()) {
            File envFile = new File(envDir.replaceAll("/$", ""), fileName);
            if (envFile.exists() && envFile.isFile() && envFile.length() > 0) {
                existingFiles.add(envFile);
            }
            File envParent = envFile.getParentFile();
            if (envParent != null && (envParent.exists() || envParent.mkdirs()) && envParent.canWrite()) {
                writableTargets.add(envFile);
            }
        }

        File railwayFile = new File("/data", fileName);
        if (railwayFile.exists() && railwayFile.isFile() && railwayFile.length() > 0) {
            existingFiles.add(railwayFile);
        }
        File railwayVolume = new File("/data");
        if (railwayVolume.exists() && railwayVolume.isDirectory() && railwayVolume.canWrite()) {
            writableTargets.add(railwayFile);
        }

        File localFile = new File("data", fileName);
        if (localFile.exists() && localFile.isFile() && localFile.length() > 0) {
            existingFiles.add(localFile);
        }
        File localParent = localFile.getParentFile();
        if (localParent != null && (localParent.exists() || localParent.mkdirs()) && localParent.canWrite()) {
            writableTargets.add(localFile);
        }

        if (!existingFiles.isEmpty()) {
            for (File candidate : existingFiles) {
                if (candidate.getAbsolutePath().startsWith("/data/")) return candidate.getPath();
            }
            return existingFiles.get(0).getPath();
        }

        for (File candidate : writableTargets) {
            if (candidate.getAbsolutePath().startsWith("/data/")) return candidate.getPath();
        }
        if (!writableTargets.isEmpty()) return writableTargets.get(0).getPath();
        return localFile.getPath();
    }

    private PositionCheck findExistingPositionForTicker(String ticker) {
        for (Map<String, Object> pos : getPositions()) {
            String symbol = String.valueOf(pos.getOrDefault("symbol", ""));
            String assetClass = String.valueOf(pos.getOrDefault("asset_class", "us_equity"));
            String positionTicker = "us_option".equals(assetClass) ? underlyingFromOcc(symbol) : symbol;
            if (!ticker.equalsIgnoreCase(positionTicker)) continue;

            TrackedPosition tracked = null;
            Object trackedFlag = pos.get("tracked");
            if (trackedFlag instanceof Boolean b && b) {
                tracked = trackedPositions.get(String.valueOf(pos.getOrDefault("tracked_underlying", ticker)));
            }
            return new PositionCheck(symbol, tracked);
        }
        return new PositionCheck("", null);
    }

    private String underlyingFromOcc(String symbol) {
        if (symbol == null || symbol.isBlank()) return "";
        String normalized = symbol.startsWith("O:") ? symbol.substring(2) : symbol;
        int idx = 0;
        while (idx < normalized.length() && !Character.isDigit(normalized.charAt(idx))) idx++;
        return idx > 0 ? normalized.substring(0, idx) : normalized;
    }
}
