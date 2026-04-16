package com.smcscanner.tracking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.broker.AlpacaOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists every live trade alert to disk so we can generate daily reports
 * and track cumulative win/loss rates across restarts.
 *
 * File: data/live-trades.json — append-only JSON array of trade records.
 * Each record has: ticker, direction, entry, sl, tp, confidence, strategy,
 * timestamp, date, outcome (initially "OPEN"), pnlPct (0 until resolved).
 *
 * Outcomes are updated via the /api/trade/resolve endpoint or the adaptive
 * outcome recording flow.
 */
@Service
public class LiveTradeLog {
    private static final Logger log = LoggerFactory.getLogger(LiveTradeLog.class);
    private static final String FILE_PATH = resolveStoragePath("live-trades.json");
    private static final String BACKUP_FILE_PATH = FILE_PATH + ".bak";
    private static final int BROKER_REBUILD_LOOKBACK_DAYS = 30;
    private static final int SCANNER_ALERT_RETENTION_DAYS = 60;
    private static final int HISTORY_RESPONSE_LIMIT = 1000;
    private static final long ENTRY_MATCH_LOOKBACK_MS = 10 * 60 * 1000L;
    private static final long ENTRY_MATCH_LOOKAHEAD_MS = 2 * 60 * 60 * 1000L;
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper mapper = new ObjectMapper();
    private final PolygonClient client;
    private final AlpacaOrderService alpaca;
    private final List<Map<String, Object>> trades = Collections.synchronizedList(new ArrayList<>());

    public LiveTradeLog(PolygonClient client, AlpacaOrderService alpaca) {
        this.client = client;
        this.alpaca = alpaca;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        List<Map<String, Object>> loaded = loadPersistedTrades();
        if (!loaded.isEmpty()) {
            trades.addAll(loaded);
            pruneScannerAlertHistory();
            log.info("Loaded {} live trade records from {}", trades.size(), FILE_PATH);
        } else {
            log.warn("Live trade log file not found at {} — starting with empty history", FILE_PATH);
        }
        bootstrapFromBrokerIfEmpty();
    }

    private void ensureTradeHistoryAvailable() {
        synchronized (trades) {
            if (!trades.isEmpty()) return;
        }

        List<Map<String, Object>> loaded = loadPersistedTrades();
        if (!loaded.isEmpty()) {
            synchronized (trades) {
                if (trades.isEmpty()) {
                    trades.addAll(loaded);
                    pruneScannerAlertHistory();
                    log.warn("Recovered {} live trade record(s) from persisted storage on demand", loaded.size());
                }
            }
            return;
        }

        bootstrapFromBrokerIfEmpty();
    }

    public Map<String, Object> getStorageInfo() {
        ensureTradeHistoryAvailable();
        File f = new File(FILE_PATH);
        File backup = new File(BACKUP_FILE_PATH);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("path", f.getAbsolutePath());
        info.put("exists", f.exists());
        info.put("sizeBytes", f.exists() ? f.length() : 0L);
        info.put("backupPath", backup.getAbsolutePath());
        info.put("backupExists", backup.exists());
        info.put("backupSizeBytes", backup.exists() ? backup.length() : 0L);
        synchronized (trades) {
            info.put("records", trades.size());
        }
        return info;
    }

    /** Record a new live trade alert. Called from ScannerService after Discord alert sent. */
    public void recordTrade(TradeSetup s, String strategyType) {
        ZonedDateTime now = ZonedDateTime.now(ET);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString().substring(0, 8));
        record.put("ticker", s.getTicker());
        record.put("direction", s.getDirection());
        record.put("entry", s.getEntry());
        record.put("stopLoss", s.getStopLoss());
        record.put("takeProfit", s.getTakeProfit());
        record.put("confidence", s.getConfidence());
        record.put("strategy", strategyType);
        record.put("atr", s.getAtr());
        record.put("optionsContract", s.getOptionsContract());
        record.put("optionsPremium", s.getOptionsPremium());
        record.put("date", now.format(DATE_FMT));
        record.put("time", now.format(TIME_FMT));
        record.put("timestamp", now.toInstant().toEpochMilli());
        record.put("outcome", "OPEN");
        record.put("pnlPct", 0.0);
        record.put("pnlAmount", null);
        trades.add(record);
        persist();
        log.info("Live trade logged: {} {} {} conf={}", s.getTicker(), s.getDirection(), strategyType, s.getConfidence());
    }

    /** Resolve a trade outcome (WIN/LOSS/BE_STOP/TIMEOUT). Called from API or adaptive flow. */
    public boolean resolveTrade(String ticker, String outcome, Double pnlAmount) {
        synchronized (trades) {
            // Find most recent OPEN trade for this ticker
            for (int i = trades.size() - 1; i >= 0; i--) {
                Map<String, Object> t = trades.get(i);
                if (ticker.equals(t.get("ticker")) && "OPEN".equals(t.get("outcome"))) {
                    double entry = toDouble(t.get("entry"));
                    double resolvedAmount = pnlAmount == null ? 0.0 : pnlAmount;
                    double resolvedPct = entry > 0 ? (resolvedAmount / entry) * 100.0 : 0.0;
                    t.put("outcome", outcome);
                    t.put("pnlPct", Math.round(resolvedPct * 100.0) / 100.0);
                    t.put("pnlAmount", Math.round(resolvedAmount * 100.0) / 100.0);
                    t.put("resolvedAt", ZonedDateTime.now(ET).toInstant().toEpochMilli());
                    persist();
                    log.info("Trade resolved: {} {} pnl=${}", ticker, outcome,
                            Math.round(resolvedAmount * 100.0) / 100.0);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if a gap_overnight signal was already recorded today for this ticker.
     * Survives service restarts because it reads from the persisted trade log.
     */
    public boolean hasOvernightFiredToday(String ticker) {
        String today = ZonedDateTime.now(ET).format(DATE_FMT);
        return getTradesForDate(today).stream()
                .anyMatch(t -> ticker.equals(t.get("ticker"))
                        && String.valueOf(t.get("strategy")).startsWith("gap_overnight"));
    }

    /** Get all trades for a specific date (yyyy-MM-dd). */
    public List<Map<String, Object>> getTradesForDate(String date) {
        ensureTradeHistoryAvailable();
        synchronized (trades) {
            return trades.stream()
                    .filter(t -> date.equals(t.get("date")))
                    .collect(Collectors.toList());
        }
    }

    /** Get today's trades. */
    public List<Map<String, Object>> getTodayTrades() {
        ensureTradeHistoryAvailable();
        backfillBrokerResolvedPnL();
        return getResolvedTradesForDate(ZonedDateTime.now(ET).format(DATE_FMT));
    }

    /** Get realized trades that closed on a specific ET date (yyyy-MM-dd). */
    public List<Map<String, Object>> getResolvedTradesForDate(String date) {
        ensureTradeHistoryAvailable();
        synchronized (trades) {
            return trades.stream()
                    .filter(t -> isRealizedOutcome(String.valueOf(t.get("outcome"))))
                    .filter(t -> date.equals(resolveDateEt(t)))
                    .sorted(Comparator.comparingLong(this::resolveTimestamp).reversed())
                    .collect(Collectors.toList());
        }
    }

    /** Summary for realized trades that closed on a specific ET date (yyyy-MM-dd). */
    public Map<String, Object> getResolvedSummaryForDate(String date) {
        ensureTradeHistoryAvailable();
        List<Map<String, Object>> dayTrades = getResolvedTradesForDate(date);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", date);
        summary.put("totalAlerts", dayTrades.size());

        long wins = dayTrades.stream().filter(t -> "WIN".equals(t.get("outcome"))).count();
        long losses = dayTrades.stream().filter(t -> "LOSS".equals(t.get("outcome"))).count();
        long beStops = dayTrades.stream().filter(t -> "BE_STOP".equals(t.get("outcome"))).count();
        long resolved = wins + losses + beStops;

        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("beStops", beStops);
        summary.put("notFilled", 0);
        summary.put("open", 0);
        summary.put("resolved", resolved);
        summary.put("winRate", resolved > 0 ? Math.round(wins * 100.0 / (wins + losses) * 10) / 10.0 : 0);

        double totalPnl = dayTrades.stream()
                .mapToDouble(t -> toDouble(t.get("pnlPct")))
                .sum();
        summary.put("totalPnlPct", Math.round(totalPnl * 100) / 100.0);
        double totalPnlAmount = dayTrades.stream()
                .mapToDouble(t -> toDouble(t.get("pnlAmount")))
                .sum();
        summary.put("totalPnlAmount", Math.round(totalPnlAmount * 100) / 100.0);
        summary.put("trades", dayTrades);
        return summary;
    }

    /** Get all trades (for full history report). */
    public List<Map<String, Object>> getAllTrades() {
        ensureTradeHistoryAvailable();
        backfillBrokerResolvedPnL();
        synchronized (trades) {
            return new ArrayList<>(trades);
        }
    }

    /** Get recent trades for history UI, capped to keep payloads fast. */
    public List<Map<String, Object>> getRecentTradesForUi() {
        ensureTradeHistoryAvailable();
        backfillBrokerResolvedPnL();
        synchronized (trades) {
            return trades.stream()
                    .sorted(Comparator.comparingLong((Map<String, Object> t) ->
                            ((Number) t.getOrDefault("timestamp", 0L)).longValue()).reversed())
                    .limit(HISTORY_RESPONSE_LIMIT)
                    .collect(Collectors.toList());
        }
    }

    public int getHistoryResponseLimit() {
        return HISTORY_RESPONSE_LIMIT;
    }

    /** Best-effort lookup for the most recent OPEN trade matching a broker position. */
    public Map<String, Object> findOpenTradeForPosition(String positionSymbol, String underlyingTicker) {
        ensureTradeHistoryAvailable();
        String normalizedSymbol = normalizeOptionSymbol(positionSymbol);
        synchronized (trades) {
            return trades.stream()
                    .filter(t -> "OPEN".equals(t.get("outcome")))
                    .filter(t -> {
                        String contract = normalizeOptionSymbol(String.valueOf(t.getOrDefault("optionsContract", "")));
                        String ticker = String.valueOf(t.getOrDefault("ticker", ""));
                        if (!normalizedSymbol.isBlank() && normalizedSymbol.equalsIgnoreCase(contract)) return true;
                        return underlyingTicker != null && !underlyingTicker.isBlank() && underlyingTicker.equalsIgnoreCase(ticker);
                    })
                    .max(Comparator.comparingLong(t -> ((Number) t.getOrDefault("timestamp", 0L)).longValue()))
                    .map(LinkedHashMap::new)
                    .orElse(null);
        }
    }

    /** Generate a daily summary for a given date. */
    public Map<String, Object> getDailySummary(String date) {
        ensureTradeHistoryAvailable();
        List<Map<String, Object>> dayTrades = getTradesForDate(date);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", date);
        summary.put("totalAlerts", dayTrades.size());

        long wins = dayTrades.stream().filter(t -> "WIN".equals(t.get("outcome"))).count();
        long losses = dayTrades.stream().filter(t -> "LOSS".equals(t.get("outcome"))).count();
        long beStops = dayTrades.stream().filter(t -> "BE_STOP".equals(t.get("outcome"))).count();
        long notFilled = dayTrades.stream().filter(t -> "NOT_FILLED".equals(t.get("outcome"))).count();
        long open = dayTrades.stream().filter(t -> "OPEN".equals(t.get("outcome"))).count();
        long resolved = wins + losses + beStops;

        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("beStops", beStops);
        summary.put("notFilled", notFilled);
        summary.put("open", open);
        summary.put("resolved", resolved);
        summary.put("winRate", resolved > 0 ? Math.round(wins * 100.0 / (wins + losses) * 10) / 10.0 : 0);

        // P&L
        double totalPnl = dayTrades.stream()
                .filter(t -> isRealizedOutcome(String.valueOf(t.get("outcome"))))
                .mapToDouble(t -> toDouble(t.get("pnlPct")))
                .sum();
        summary.put("totalPnlPct", Math.round(totalPnl * 100) / 100.0);
        double totalPnlAmount = dayTrades.stream()
                .filter(t -> isRealizedOutcome(String.valueOf(t.get("outcome"))))
                .mapToDouble(t -> toDouble(t.get("pnlAmount")))
                .sum();
        summary.put("totalPnlAmount", Math.round(totalPnlAmount * 100) / 100.0);

        // By ticker
        Map<String, Long> byTicker = dayTrades.stream()
                .collect(Collectors.groupingBy(t -> (String) t.get("ticker"), Collectors.counting()));
        summary.put("byTicker", byTicker);

        // Trade list sorted by time
        List<Map<String, Object>> sorted = dayTrades.stream()
                .sorted(Comparator.comparingLong(t -> ((Number) t.getOrDefault("timestamp", 0L)).longValue()))
                .collect(Collectors.toList());
        summary.put("trades", sorted);

        return summary;
    }

    /** Generate cumulative stats across all recorded trades. */
    public Map<String, Object> getCumulativeStats() {
        ensureTradeHistoryAvailable();
        synchronized (trades) {
            Map<String, Object> stats = new LinkedHashMap<>();
            long total = trades.size();
            long wins = trades.stream().filter(t -> "WIN".equals(t.get("outcome"))).count();
            long losses = trades.stream().filter(t -> "LOSS".equals(t.get("outcome"))).count();
            long beStops = trades.stream().filter(t -> "BE_STOP".equals(t.get("outcome"))).count();
            long notFilled = trades.stream().filter(t -> "NOT_FILLED".equals(t.get("outcome"))).count();
            long open = trades.stream().filter(t -> "OPEN".equals(t.get("outcome"))).count();
            long realizedDollarTrades = trades.stream()
                    .filter(t -> isRealizedOutcome(String.valueOf(t.get("outcome"))))
                    .filter(t -> {
                        Object amount = t.get("pnlAmount");
                        return amount != null && !String.valueOf(amount).isBlank();
                    })
                    .count();

            stats.put("totalAlerts", total);
            stats.put("wins", wins);
            stats.put("losses", losses);
            stats.put("beStops", beStops);
            stats.put("notFilled", notFilled);
            stats.put("open", open);
            stats.put("realizedDollarTrades", realizedDollarTrades);
            stats.put("winRate", (wins + losses) > 0
                    ? Math.round(wins * 100.0 / (wins + losses) * 10) / 10.0 : 0);

            double totalPnl = trades.stream()
                    .filter(t -> isRealizedOutcome(String.valueOf(t.get("outcome"))))
                    .mapToDouble(t -> toDouble(t.get("pnlPct")))
                    .sum();
            stats.put("totalPnlPct", Math.round(totalPnl * 100) / 100.0);
            double totalPnlAmount = trades.stream()
                    .filter(t -> isRealizedOutcome(String.valueOf(t.get("outcome"))))
                    .mapToDouble(t -> toDouble(t.get("pnlAmount")))
                    .sum();
            stats.put("totalPnlAmount", Math.round(totalPnlAmount * 100) / 100.0);

            // Unique trading days
            long tradingDays = trades.stream()
                    .map(t -> (String) t.get("date"))
                    .distinct().count();
            stats.put("tradingDays", tradingDays);
            stats.put("avgTradesPerDay", tradingDays > 0
                    ? Math.round(total * 10.0 / tradingDays) / 10.0 : 0);

            return stats;
        }
    }

    /**
     * Auto-resolve OPEN trades by fetching current price and checking SL/TP.
     * Called before daily report to ensure accurate outcome tracking.
     */
    public void resolveOpenTrades() {
        ensureTradeHistoryAvailable();
        List<Map<String, Object>> openTrades;
        synchronized (trades) {
            openTrades = trades.stream()
                    .filter(t -> "OPEN".equals(t.get("outcome")))
                    .collect(Collectors.toList());
        }
        if (openTrades.isEmpty()) return;

        // Group by ticker to minimize API calls
        Map<String, List<Map<String, Object>>> byTicker = openTrades.stream()
                .collect(Collectors.groupingBy(t -> (String) t.get("ticker")));
        Set<String> brokerOpenTickers = getBrokerOpenTickers();
        Map<String, List<Map<String, Object>>> recentOrdersBySymbol = getRecentOrdersBySymbol();
        Map<String, List<Map<String, Object>>> recentFilledOrders = getRecentFilledOrdersBySymbol();
        Map<String, List<Map<String, Object>>> recentFilledOrdersByUnderlying = getRecentFilledOrdersByUnderlying(recentFilledOrders);
        Set<String> usedOrderIds = new HashSet<>();

        int resolved = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : byTicker.entrySet()) {
            String ticker = e.getKey();
            try {
                // Fetch 5m bars to get intraday high/low/close for accurate SL/TP check
                List<OHLCV> bars = client.getBars(ticker, "5m", 80); // ~6.5h = full session
                if (bars == null || bars.isEmpty()) continue;

                // Session high, low, and latest close
                double sessionHigh = bars.stream().mapToDouble(OHLCV::getHigh).max().orElse(0);
                double sessionLow  = bars.stream().mapToDouble(OHLCV::getLow).min().orElse(0);
                double lastPrice   = bars.get(bars.size() - 1).getClose();

                for (Map<String, Object> t : e.getValue()) {
                    double entry = ((Number) t.get("entry")).doubleValue();
                    double sl    = ((Number) t.get("stopLoss")).doubleValue();
                    double tp    = ((Number) t.get("takeProfit")).doubleValue();
                    String dir   = (String) t.get("direction");
                    boolean isLong = "long".equalsIgnoreCase(dir);

                    String outcome = null;
                    double pnlPct = 0.0;

                    // ── Broker data takes priority (ground truth for options P&L) ──────────
                    // Check broker FIRST. If the actual options position was closed at a loss,
                    // we must record LOSS even if the underlying stock later hit the TP price.
                    // Stock-price check is only used as a fallback when no broker match exists.
                    boolean brokerStillOpen = brokerOpenTickers.contains(ticker);
                    if (!brokerStillOpen && markNotFilledIfNeeded(t, recentOrdersBySymbol)) {
                        outcome = "NOT_FILLED";
                        pnlPct = 0.0;
                    } else if (!brokerStillOpen) {
                        boolean resolvedFromOrders = applyBrokerRealizedPnl(t, recentFilledOrders, recentFilledOrdersByUnderlying, usedOrderIds);
                        if (resolvedFromOrders) {
                            outcome = (String) t.get("outcome");
                            pnlPct = ((Number) t.getOrDefault("pnlPct", 0.0)).doubleValue();
                        }
                    }

                    // ── Stock-price fallback (used only when broker data is unavailable) ────
                    if (outcome == null) {
                        if (isLong) {
                            if (sessionLow <= sl) {
                                outcome = "LOSS";
                                pnlPct = (sl - entry) / entry * 100.0;
                            } else if (sessionHigh >= tp) {
                                outcome = "WIN";
                                pnlPct = (tp - entry) / entry * 100.0;
                            } else {
                                // Still holding — mark with current P&L
                                pnlPct = (lastPrice - entry) / entry * 100.0;
                            }
                        } else { // short
                            if (sessionHigh >= sl) {
                                outcome = "LOSS";
                                pnlPct = (entry - sl) / entry * 100.0;
                            } else if (sessionLow <= tp) {
                                outcome = "WIN";
                                pnlPct = (entry - tp) / entry * 100.0;
                            } else {
                                // Still holding — mark with current P&L
                                pnlPct = (entry - lastPrice) / entry * 100.0;
                            }
                        }

                        // Broker closed it but no P&L data — classify by stock price direction
                        if (outcome == null && !brokerStillOpen) {
                            if (Math.abs(pnlPct) < 0.10) {
                                outcome = "BE_STOP";
                                pnlPct = 0.0;
                            } else if (pnlPct > 0) {
                                outcome = "WIN";
                            } else {
                                outcome = "LOSS";
                            }
                            t.put("resolutionSource", "ALPACA_FLAT");
                        }
                    }

                    if (outcome != null) {
                        t.put("outcome", outcome);
                        t.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
                        if (!t.containsKey("pnlAmount")) t.put("pnlAmount", null);
                        t.put("resolvedAt", ZonedDateTime.now(ET).toInstant().toEpochMilli());
                        if ("NOT_FILLED".equals(outcome)) {
                            t.remove("exitPrice");
                        } else if (t.containsKey("resolutionSource")) {
                            t.put("exitPrice", lastPrice);
                        } else {
                            t.put("exitPrice", outcome.equals("WIN") ? tp : sl);
                        }
                        resolved++;
                        log.info("Auto-resolved {} {} → {} (pnl={}%)", ticker, dir, outcome,
                                Math.round(pnlPct * 100.0) / 100.0);
                    } else {
                        // Still open — update unrealized P&L for display
                        t.put("unrealizedPnl", Math.round(pnlPct * 100.0) / 100.0);
                        t.put("lastPrice", lastPrice);
                        log.info("Still OPEN: {} {} entry={} last={} unrealPnl={}%",
                                ticker, dir, entry, lastPrice, Math.round(pnlPct * 100.0) / 100.0);
                    }
                }
                Thread.sleep(150); // rate limit between tickers
            } catch (Exception ex) {
                log.warn("Failed to resolve {} trades: {}", ticker, ex.getMessage());
            }
        }
        if (resolved > 0) {
            persist();
            log.info("Auto-resolved {} open trades via price check", resolved);
        }
        backfillBrokerResolvedPnL();
    }

    private void backfillBrokerResolvedPnL() {
        Map<String, List<Map<String, Object>>> recentOrdersBySymbol = getRecentOrdersBySymbol();
        Map<String, List<Map<String, Object>>> fillActivitiesBySymbol = getRecentFillActivitiesBySymbol();
        Map<String, List<Map<String, Object>>> filledOrdersBySymbol = getRecentFilledOrdersBySymbol();
        Map<String, List<Map<String, Object>>> filledOrdersByUnderlying = getRecentFilledOrdersByUnderlying(filledOrdersBySymbol);
        Set<String> usedOrderIds = new HashSet<>();
        Set<String> usedActivityIds = new HashSet<>();
        boolean changed = false;

        synchronized (trades) {
            List<Map<String, Object>> sortedTrades = trades.stream()
                    .sorted(Comparator.comparingLong(t -> ((Number) t.getOrDefault("timestamp", 0L)).longValue()))
                    .collect(Collectors.toList());
            for (Map<String, Object> trade : sortedTrades) {
                if ("OPEN".equals(trade.get("outcome"))) continue;
                if (markNotFilledIfNeeded(trade, recentOrdersBySymbol)) {
                    changed = true;
                    continue;
                }
                Object amount = trade.get("pnlAmount");
                if (amount != null && !String.valueOf(amount).isBlank()) continue;
                if (applyBrokerRealizedPnlFromActivities(trade, fillActivitiesBySymbol, usedActivityIds)
                        || applyBrokerRealizedPnl(trade, filledOrdersBySymbol, filledOrdersByUnderlying, usedOrderIds)) {
                    changed = true;
                }
            }
        }

        if (changed) {
            persist();
            log.info("Backfilled broker dollar P&L for closed trades");
        }
    }

    private boolean applyBrokerRealizedPnlFromActivities(Map<String, Object> trade,
                                                         Map<String, List<Map<String, Object>>> fillActivitiesBySymbol,
                                                         Set<String> usedActivityIds) {
        String optionsContract = String.valueOf(trade.getOrDefault("optionsContract", ""));
        String symbol = normalizeOptionSymbol(optionsContract);
        if (symbol.isBlank()) return false;
        List<Map<String, Object>> activities = fillActivitiesBySymbol.getOrDefault(symbol, List.of());
        if (activities.isEmpty()) return false;

        long tradeTs = ((Number) trade.getOrDefault("timestamp", 0L)).longValue();
        Map<String, Object> buy = null;
        Map<String, Object> sell = null;
        for (Map<String, Object> activity : activities) {
            String activityId = String.valueOf(activity.getOrDefault("id", ""));
            if (usedActivityIds.contains(activityId)) continue;
            String side = String.valueOf(activity.getOrDefault("side", ""));
            long ts = ((Number) activity.getOrDefault("transaction_time_epoch", 0L)).longValue();
            if ("buy".equalsIgnoreCase(side) && ts >= tradeTs - 7_200_000L && ts <= tradeTs + 7_200_000L) {
                if (buy == null || ts < ((Number) buy.getOrDefault("transaction_time_epoch", 0L)).longValue()) {
                    buy = activity;
                }
            }
            if ("sell".equalsIgnoreCase(side) && ts >= tradeTs) {
                if (sell == null || ts > ((Number) sell.getOrDefault("transaction_time_epoch", 0L)).longValue()) {
                    sell = activity;
                }
            }
        }
        if (buy == null || sell == null) return false;

        double buyAmount = Math.abs(toDouble(buy.get("net_amount")));
        double sellAmount = Math.abs(toDouble(sell.get("net_amount")));
        double qty = Math.max(1.0, Math.abs(toDouble(sell.get("qty"))));
        double entryFill = buyAmount / (100.0 * qty);
        double exitFill = sellAmount / (100.0 * qty);
        if (entryFill <= 0 || exitFill <= 0) return false;

        double pnlAmount = sellAmount - buyAmount;
        double pnlPct = buyAmount > 0 ? (pnlAmount / buyAmount) * 100.0 : 0.0;
        String outcome = Math.abs(pnlPct) < 0.10 ? "BE_STOP" : (pnlPct > 0 ? "WIN" : "LOSS");
        trade.put("outcome", outcome);
        trade.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
        trade.put("pnlAmount", Math.round(pnlAmount * 100.0) / 100.0);
        trade.put("exitPrice", exitFill);
        trade.put("entryFillPrice", entryFill);
        trade.put("resolutionSource", "ALPACA_ACTIVITIES");
        trade.put("brokerSymbol", symbol);
        trade.put("brokerExitAt", sell.get("transaction_time"));
        usedActivityIds.add(String.valueOf(buy.getOrDefault("id", "")));
        usedActivityIds.add(String.valueOf(sell.getOrDefault("id", "")));
        return true;
    }

    private boolean applyBrokerRealizedPnl(Map<String, Object> trade,
                                           Map<String, List<Map<String, Object>>> filledOrdersBySymbol,
                                           Map<String, List<Map<String, Object>>> filledOrdersByUnderlying,
                                           Set<String> usedOrderIds) {
        String optionsContract = String.valueOf(trade.getOrDefault("optionsContract", ""));
        String symbol = normalizeOptionSymbol(optionsContract);
        List<Map<String, Object>> orders = optionsContract.isBlank()
                ? List.of()
                : filledOrdersBySymbol.getOrDefault(symbol, List.of());
        if (orders.isEmpty()) {
            String ticker = String.valueOf(trade.getOrDefault("ticker", ""));
            orders = filledOrdersByUnderlying.getOrDefault(ticker, List.of());
        }
        if (orders.isEmpty()) return false;

        long tradeTs = ((Number) trade.getOrDefault("timestamp", 0L)).longValue();
        Map<String, Object> buyOrder = null;
        Map<String, Object> sellOrder = null;
        for (Map<String, Object> order : orders) {
            String orderId = String.valueOf(order.getOrDefault("id", ""));
            if (usedOrderIds.contains(orderId)) continue;
            String side = String.valueOf(order.getOrDefault("side", ""));
            long ts = ((Number) order.getOrDefault("created_at_epoch", 0L)).longValue();
            if ("buy".equalsIgnoreCase(side) && ts >= tradeTs - 7_200_000L && ts <= tradeTs + 7_200_000L) {
                if (buyOrder == null || ts < ((Number) buyOrder.getOrDefault("created_at_epoch", 0L)).longValue()) {
                    buyOrder = order;
                }
            }
            if ("sell".equalsIgnoreCase(side) && ts >= tradeTs) {
                if (sellOrder == null || ts > ((Number) sellOrder.getOrDefault("created_at_epoch", 0L)).longValue()) {
                    sellOrder = order;
                }
            }
        }
        if (buyOrder == null || sellOrder == null) return false;

        double entryFill = toDouble(buyOrder.get("filled_avg_price"));
        double exitFill = toDouble(sellOrder.get("filled_avg_price"));
        if (entryFill <= 0 || exitFill <= 0) return false;

        double qty = Math.max(1.0, Math.abs(toDouble(sellOrder.get("qty"))));
        double pnlAmount = (exitFill - entryFill) * 100.0 * qty;
        double pnlPct = (exitFill - entryFill) / entryFill * 100.0;
        String outcome = Math.abs(pnlPct) < 0.10 ? "BE_STOP" : (pnlPct > 0 ? "WIN" : "LOSS");
        trade.put("outcome", outcome);
        trade.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
        trade.put("pnlAmount", Math.round(pnlAmount * 100.0) / 100.0);
        trade.put("exitPrice", exitFill);
        trade.put("entryFillPrice", entryFill);
        trade.put("resolutionSource", "ALPACA_ORDERS");
        trade.put("brokerSymbol", String.valueOf(sellOrder.getOrDefault("symbol", symbol)));
        trade.put("brokerExitAt", sellOrder.get("created_at"));
        usedOrderIds.add(String.valueOf(buyOrder.getOrDefault("id", "")));
        usedOrderIds.add(String.valueOf(sellOrder.getOrDefault("id", "")));
        return true;
    }

    private Map<String, List<Map<String, Object>>> getRecentFilledOrdersBySymbol() {
        if (alpaca == null || !alpaca.isEnabled()) return Map.of();
        try {
            return alpaca.getOrders(BROKER_REBUILD_LOOKBACK_DAYS).stream()
                    .filter(o -> "filled".equalsIgnoreCase(String.valueOf(o.getOrDefault("status", ""))))
                    .peek(o -> o.put("created_at_epoch", parseEpoch(String.valueOf(o.getOrDefault("created_at", "")))))
                    .collect(Collectors.groupingBy(o -> normalizeOptionSymbol(String.valueOf(o.getOrDefault("symbol", "")))));
        } catch (Exception e) {
            log.warn("Could not fetch Alpaca filled orders for realized P&L: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, List<Map<String, Object>>> getRecentOrdersBySymbol() {
        if (alpaca == null || !alpaca.isEnabled()) return Map.of();
        try {
            return alpaca.getOrders(BROKER_REBUILD_LOOKBACK_DAYS).stream()
                    .peek(o -> o.put("created_at_epoch", parseEpoch(String.valueOf(o.getOrDefault("created_at", "")))))
                    .collect(Collectors.groupingBy(o -> normalizeOptionSymbol(String.valueOf(o.getOrDefault("symbol", "")))));
        } catch (Exception e) {
            log.warn("Could not fetch Alpaca orders for fill-status reconciliation: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, List<Map<String, Object>>> getRecentFillActivitiesBySymbol() {
        if (alpaca == null || !alpaca.isEnabled()) return Map.of();
        try {
            return alpaca.getFillActivities(BROKER_REBUILD_LOOKBACK_DAYS).stream()
                    .peek(a -> a.put("transaction_time_epoch", parseEpoch(String.valueOf(a.getOrDefault("transaction_time", "")))))
                    .collect(Collectors.groupingBy(a -> normalizeOptionSymbol(String.valueOf(a.getOrDefault("symbol", "")))));
        } catch (Exception e) {
            log.warn("Could not fetch Alpaca fill activities for realized P&L: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, List<Map<String, Object>>> getRecentFilledOrdersByUnderlying(Map<String, List<Map<String, Object>>> bySymbol) {
        return bySymbol.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(o -> underlyingFromOcc(String.valueOf(o.getOrDefault("symbol", "")))));
    }

    private long parseEpoch(String isoTs) {
        if (isoTs == null || isoTs.isBlank()) return 0L;
        try {
            return Instant.parse(isoTs).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String normalizeOptionSymbol(String symbol) {
        if (symbol == null) return "";
        return symbol.startsWith("O:") ? symbol.substring(2) : symbol;
    }

    private String underlyingFromOcc(String symbol) {
        String normalized = normalizeOptionSymbol(symbol);
        if (normalized.isBlank()) return "";
        int idx = 0;
        while (idx < normalized.length() && !Character.isDigit(normalized.charAt(idx))) idx++;
        return idx > 0 ? normalized.substring(0, idx) : normalized;
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private boolean isRealizedOutcome(String outcome) {
        return "WIN".equals(outcome) || "LOSS".equals(outcome) || "BE_STOP".equals(outcome) || "TIMEOUT".equals(outcome);
    }

    private boolean isBrokerBackedTrade(Map<String, Object> trade) {
        Object amount = trade.get("pnlAmount");
        if (amount != null && !String.valueOf(amount).isBlank()) return true;
        String source = String.valueOf(trade.getOrDefault("resolutionSource", ""));
        return "ALPACA_ORDERS".equals(source)
                || "ALPACA_ACTIVITIES".equals(source)
                || "ALPACA_BOOTSTRAP".equals(source)
                || "ALPACA_BOOTSTRAP_ACTIVITIES".equals(source);
    }

    private void pruneScannerAlertHistory() {
        synchronized (trades) {
            Instant cutoff = ZonedDateTime.now(ET)
                    .minusDays(SCANNER_ALERT_RETENTION_DAYS)
                    .toInstant();
            boolean removed = trades.removeIf(trade ->
                    !isBrokerBackedTrade(trade)
                            && Instant.ofEpochMilli(resolveTimestamp(trade)).isBefore(cutoff));
            if (removed) {
                log.info("Pruned scanner-only alerts older than {} days", SCANNER_ALERT_RETENTION_DAYS);
            }
        }
    }

    private long resolveTimestamp(Map<String, Object> trade) {
        Object resolvedAt = trade.get("resolvedAt");
        if (resolvedAt instanceof Number n) return n.longValue();
        if (resolvedAt != null) {
            try {
                return Long.parseLong(String.valueOf(resolvedAt));
            } catch (Exception ignored) {
                return parseEpoch(String.valueOf(resolvedAt));
            }
        }
        Object brokerExitAt = trade.get("brokerExitAt");
        if (brokerExitAt != null) return parseEpoch(String.valueOf(brokerExitAt));
        return ((Number) trade.getOrDefault("timestamp", 0L)).longValue();
    }

    private String resolveDateEt(Map<String, Object> trade) {
        long ts = resolveTimestamp(trade);
        if (ts <= 0) return String.valueOf(trade.getOrDefault("date", ""));
        return Instant.ofEpochMilli(ts).atZone(ET).format(DATE_FMT);
    }

    private boolean markNotFilledIfNeeded(Map<String, Object> trade,
                                          Map<String, List<Map<String, Object>>> ordersBySymbol) {
        String optionsContract = String.valueOf(trade.getOrDefault("optionsContract", ""));
        String symbol = normalizeOptionSymbol(optionsContract);
        if (symbol.isBlank()) return false;
        if ("NOT_FILLED".equals(trade.get("outcome"))) return true;

        List<Map<String, Object>> orders = ordersBySymbol.getOrDefault(symbol, List.of());
        if (orders.isEmpty()) return false;

        long tradeTs = ((Number) trade.getOrDefault("timestamp", 0L)).longValue();
        Map<String, Object> entryOrder = orders.stream()
                .filter(o -> "buy".equalsIgnoreCase(String.valueOf(o.getOrDefault("side", ""))))
                .filter(o -> {
                    long ts = ((Number) o.getOrDefault("created_at_epoch", 0L)).longValue();
                    return ts >= (tradeTs - ENTRY_MATCH_LOOKBACK_MS) && ts <= (tradeTs + ENTRY_MATCH_LOOKAHEAD_MS);
                })
                .min(Comparator.comparingLong(o -> ((Number) o.getOrDefault("created_at_epoch", 0L)).longValue()))
                .orElse(null);
        if (entryOrder == null) return false;

        String status = String.valueOf(entryOrder.getOrDefault("status", ""));
        double filledQty = toDouble(entryOrder.get("filled_qty"));
        String filledAt = String.valueOf(entryOrder.getOrDefault("filled_at", ""));
        boolean isUnfilled = !"filled".equalsIgnoreCase(status)
                || filledQty <= 0.0
                || filledAt == null
                || filledAt.isBlank()
                || "null".equalsIgnoreCase(filledAt);
        if (!isUnfilled) return false;

        trade.put("outcome", "NOT_FILLED");
        trade.put("pnlPct", 0.0);
        trade.put("pnlAmount", null);
        trade.put("resolutionSource", "ALPACA_NOT_FILLED");
        trade.put("brokerSymbol", symbol);
        trade.put("brokerOrderStatus", status);
        trade.put("brokerOrderId", entryOrder.get("id"));
        trade.put("resolvedAt", ZonedDateTime.now(ET).toInstant().toEpochMilli());
        trade.remove("exitPrice");
        return true;
    }

    private Set<String> getBrokerOpenTickers() {
        if (alpaca == null || !alpaca.isEnabled()) return Set.of();
        try {
            return alpaca.getPositions().stream()
                    .map(pos -> {
                        String assetClass = String.valueOf(pos.getOrDefault("asset_class", "us_equity"));
                        String trackedUnderlying = String.valueOf(pos.getOrDefault("tracked_underlying", ""));
                        String symbol = String.valueOf(pos.getOrDefault("symbol", ""));
                        if ("us_option".equals(assetClass)) {
                            if (!trackedUnderlying.isBlank()) return trackedUnderlying;
                            return underlyingFromOcc(symbol);
                        }
                        return symbol;
                    })
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Could not fetch Alpaca open tickers for trade reconciliation: {}", e.getMessage());
            return Set.of();
        }
    }

    /** Build Discord embed for daily summary report. */
    public Map<String, Object> buildDailyDiscordEmbed(String date) {
        Map<String, Object> summary = getDailySummary(date);
        long totalAlerts = ((Number) summary.get("totalAlerts")).longValue();
        if (totalAlerts == 0) return null;

        long wins = ((Number) summary.get("wins")).longValue();
        long losses = ((Number) summary.get("losses")).longValue();
        long beStops = ((Number) summary.get("beStops")).longValue();
        long open = ((Number) summary.get("open")).longValue();
        double winRate = ((Number) summary.get("winRate")).doubleValue();
        double pnl = ((Number) summary.get("totalPnlPct")).doubleValue();

        List<Map<String, Object>> fields = new ArrayList<>();

        // Summary line
        fields.add(Map.of("name", "📊 Day Summary",
                "value", String.format("**%d** alerts | **%d** W / **%d** L / **%d** BE | **%d** open",
                        totalAlerts, wins, losses, beStops, open),
                "inline", false));

        // Win rate + P&L
        if (wins + losses > 0) {
            String wrEmoji = winRate >= 50 ? "🟢" : winRate >= 35 ? "🟡" : "🔴";
            String pnlEmoji = pnl >= 0 ? "📈" : "📉";
            fields.add(Map.of("name", wrEmoji + " Win Rate",
                    "value", String.format("%.1f%%", winRate), "inline", true));
            fields.add(Map.of("name", pnlEmoji + " Day P&L",
                    "value", String.format("%+.2f%%", pnl), "inline", true));
        }

        // Trade list (compact)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dayTrades = (List<Map<String, Object>>) summary.get("trades");
        StringBuilder tradeLines = new StringBuilder();
        for (Map<String, Object> t : dayTrades) {
            String dir = "long".equals(t.get("direction")) ? "▲" : "▼";
            String outcome = (String) t.getOrDefault("outcome", "OPEN");
            String outcomeEmoji = switch (outcome) {
                case "WIN" -> "✅";
                case "LOSS" -> "❌";
                case "BE_STOP" -> "⏸️";
                default -> "⏳";
            };
            double entry = ((Number) t.get("entry")).doubleValue();
            double sl    = ((Number) t.get("stopLoss")).doubleValue();
            double tp    = ((Number) t.get("takeProfit")).doubleValue();

            String resultStr;
            if ("WIN".equals(outcome)) {
                double tradePnl = ((Number) t.getOrDefault("pnlPct", 0.0)).doubleValue();
                resultStr = String.format("→TP $%.2f (%+.1f%%)", tp, tradePnl);
            } else if ("LOSS".equals(outcome)) {
                double tradePnl = ((Number) t.getOrDefault("pnlPct", 0.0)).doubleValue();
                resultStr = String.format("→SL $%.2f (%+.1f%%)", sl, tradePnl);
            } else if ("BE_STOP".equals(outcome)) {
                resultStr = "→BE (0%)";
            } else if (t.containsKey("lastPrice")) {
                double last = ((Number) t.get("lastPrice")).doubleValue();
                double unreal = ((Number) t.getOrDefault("unrealizedPnl", 0.0)).doubleValue();
                resultStr = String.format("now $%.2f [%+.1f%%]", last, unreal);
            } else {
                resultStr = "HOLDING";
            }

            tradeLines.append(String.format("%s %s %s%s $%.2f %s\n",
                    t.get("time"), outcomeEmoji, t.get("ticker"), dir,
                    entry, resultStr));
        }
        if (tradeLines.length() > 0) {
            // Discord field value max 1024 chars
            String val = tradeLines.length() > 1000
                    ? tradeLines.substring(0, 997) + "..."
                    : tradeLines.toString();
            fields.add(Map.of("name", "📋 Trades", "value", "```\n" + val + "```", "inline", false));
        }

        // Cumulative stats
        Map<String, Object> cumulative = getCumulativeStats();
        fields.add(Map.of("name", "📈 Cumulative",
                "value", String.format("WR: **%.1f%%** | Total: **%d** trades | P&L: **%+.2f%%** | Days: **%d**",
                        ((Number) cumulative.get("winRate")).doubleValue(),
                        ((Number) cumulative.get("totalAlerts")).longValue(),
                        ((Number) cumulative.get("totalPnlPct")).doubleValue(),
                        ((Number) cumulative.get("tradingDays")).longValue()),
                "inline", false));

        Map<String, Object> embed = new HashMap<>();
        embed.put("title", "📊 Daily Trade Report — " + date);
        embed.put("color", pnl >= 0 ? 0x2ECC71 : 0xE74C3C);
        embed.put("fields", fields);
        embed.put("footer", Map.of("text", "SD Scanner | Daily Report"));
        return embed;
    }

    private void persist() {
        try {
            File f = new File(FILE_PATH);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Path target = f.toPath();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Path backup = Path.of(BACKUP_FILE_PATH);
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), trades);
            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to persist live trades: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> loadPersistedTrades() {
        List<Map<String, Object>> loaded = readTradesFile(new File(FILE_PATH), "primary");
        if (!loaded.isEmpty()) return loaded;
        return readTradesFile(new File(BACKUP_FILE_PATH), "backup");
    }

    private List<Map<String, Object>> readTradesFile(File file, String label) {
        if (!file.exists() || !file.isFile() || file.length() <= 0) return List.of();
        try {
            return mapper.readValue(file, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not load {} live trade file {}: {}", label, file.getAbsolutePath(), e.getMessage());
            return List.of();
        }
    }

    private void bootstrapFromBrokerIfEmpty() {
        if (alpaca == null || !alpaca.isEnabled()) return;
        synchronized (trades) {
            if (!trades.isEmpty()) return;
        }

        try {
            List<Map<String, Object>> fills = alpaca.getFillActivities(BROKER_REBUILD_LOOKBACK_DAYS).stream()
                    .sorted(Comparator.comparingLong(a -> parseEpoch(String.valueOf(a.getOrDefault("transaction_time", "")))))
                    .collect(Collectors.toList());
            if (!fills.isEmpty()) {
                List<Map<String, Object>> rebuilt = rebuildTradesFromActivities(fills);
                if (!rebuilt.isEmpty()) {
                    rebuilt.sort(Comparator.comparingLong(t -> ((Number) t.getOrDefault("timestamp", 0L)).longValue()));
                    synchronized (trades) {
                        if (!trades.isEmpty()) return;
                        trades.addAll(rebuilt);
                    }
                    persist();
                    log.warn("Bootstrapped {} live trade record(s) from Alpaca fill activities because {} was empty/missing", rebuilt.size(), FILE_PATH);
                    return;
                }
            }

            List<Map<String, Object>> orders = alpaca.getOrders(BROKER_REBUILD_LOOKBACK_DAYS).stream()
                    .filter(o -> "filled".equalsIgnoreCase(String.valueOf(o.getOrDefault("status", ""))))
                    .sorted(Comparator.comparingLong(o -> parseEpoch(String.valueOf(o.getOrDefault("created_at", "")))))
                    .collect(Collectors.toList());
            if (orders.isEmpty()) return;

            Map<String, Deque<Map<String, Object>>> openBuysBySymbol = new HashMap<>();
            List<Map<String, Object>> rebuilt = new ArrayList<>();

            for (Map<String, Object> order : orders) {
                String symbol = String.valueOf(order.getOrDefault("symbol", ""));
                String side = String.valueOf(order.getOrDefault("side", ""));
                if (symbol.isBlank() || side.isBlank()) continue;

                if ("buy".equalsIgnoreCase(side)) {
                    openBuysBySymbol.computeIfAbsent(symbol, ignored -> new ArrayDeque<>()).addLast(order);
                    continue;
                }

                if (!"sell".equalsIgnoreCase(side)) continue;
                Deque<Map<String, Object>> buys = openBuysBySymbol.getOrDefault(symbol, new ArrayDeque<>());
                if (buys.isEmpty()) continue;

                Map<String, Object> buy = buys.pollFirst();
                rebuilt.add(buildTradeFromBrokerRoundTrip(symbol, buy, order));
            }

            Set<String> openSymbols = alpaca.getPositions().stream()
                    .map(pos -> String.valueOf(pos.getOrDefault("symbol", "")))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());

            for (String symbol : openSymbols) {
                Deque<Map<String, Object>> buys = openBuysBySymbol.getOrDefault(symbol, new ArrayDeque<>());
                while (!buys.isEmpty()) {
                    rebuilt.add(buildOpenTradeFromBroker(symbol, buys.pollFirst()));
                }
            }

            if (rebuilt.isEmpty()) return;
            rebuilt.sort(Comparator.comparingLong(t -> ((Number) t.getOrDefault("timestamp", 0L)).longValue()));
            synchronized (trades) {
                if (!trades.isEmpty()) return;
                trades.addAll(rebuilt);
            }
            persist();
            log.warn("Bootstrapped {} live trade record(s) from Alpaca because {} was empty/missing", rebuilt.size(), FILE_PATH);
        } catch (Exception e) {
            log.warn("Could not bootstrap live trade history from Alpaca: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> rebuildTradesFromActivities(List<Map<String, Object>> fills) {
        Map<String, Deque<Map<String, Object>>> openBuysBySymbol = new HashMap<>();
        List<Map<String, Object>> rebuilt = new ArrayList<>();

        for (Map<String, Object> fill : fills) {
            String symbol = String.valueOf(fill.getOrDefault("symbol", ""));
            String side = String.valueOf(fill.getOrDefault("side", ""));
            if (symbol.isBlank() || side.isBlank()) continue;
            if ("buy".equalsIgnoreCase(side)) {
                openBuysBySymbol.computeIfAbsent(symbol, ignored -> new ArrayDeque<>()).addLast(fill);
                continue;
            }
            if (!"sell".equalsIgnoreCase(side)) continue;
            Deque<Map<String, Object>> buys = openBuysBySymbol.getOrDefault(symbol, new ArrayDeque<>());
            if (buys.isEmpty()) continue;
            rebuilt.add(buildTradeFromBrokerActivities(symbol, buys.pollFirst(), fill));
        }

        Set<String> openSymbols = alpaca.getPositions().stream()
                .map(pos -> String.valueOf(pos.getOrDefault("symbol", "")))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        for (String symbol : openSymbols) {
            Deque<Map<String, Object>> buys = openBuysBySymbol.getOrDefault(symbol, new ArrayDeque<>());
            while (!buys.isEmpty()) {
                rebuilt.add(buildOpenTradeFromBrokerActivity(symbol, buys.pollFirst()));
            }
        }
        return rebuilt;
    }

    private Map<String, Object> buildTradeFromBrokerActivities(String symbol, Map<String, Object> buy, Map<String, Object> sell) {
        double buyAmount = Math.abs(toDouble(buy.get("net_amount")));
        double sellAmount = Math.abs(toDouble(sell.get("net_amount")));
        double qty = Math.max(1.0, Math.abs(toDouble(sell.get("qty"))));
        double entryFill = buyAmount / (100.0 * qty);
        double exitFill = sellAmount / (100.0 * qty);
        double pnlAmount = sellAmount - buyAmount;
        double pnlPct = buyAmount > 0 ? (pnlAmount / buyAmount) * 100.0 : 0.0;
        String outcome = Math.abs(pnlPct) < 0.10 ? "BE_STOP" : (pnlPct > 0 ? "WIN" : "LOSS");

        Map<String, Object> record = buildBrokerSeedRecordFromActivity(symbol, buy);
        record.put("outcome", outcome);
        record.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
        record.put("pnlAmount", Math.round(pnlAmount * 100.0) / 100.0);
        record.put("resolvedAt", parseEpoch(String.valueOf(sell.getOrDefault("transaction_time", ""))));
        record.put("brokerExitAt", sell.get("transaction_time"));
        record.put("exitPrice", exitFill);
        record.put("entryFillPrice", entryFill);
        record.put("resolutionSource", "ALPACA_BOOTSTRAP_ACTIVITIES");
        record.put("brokerSymbol", symbol);
        return record;
    }

    private Map<String, Object> buildOpenTradeFromBrokerActivity(String symbol, Map<String, Object> buy) {
        Map<String, Object> record = buildBrokerSeedRecordFromActivity(symbol, buy);
        record.put("outcome", "OPEN");
        record.put("pnlPct", 0.0);
        record.put("pnlAmount", null);
        record.put("resolutionSource", "ALPACA_BOOTSTRAP_ACTIVITIES");
        record.put("brokerSymbol", symbol);
        return record;
    }

    private Map<String, Object> buildBrokerSeedRecordFromActivity(String symbol, Map<String, Object> buy) {
        long entryTs = parseEpoch(String.valueOf(buy.getOrDefault("transaction_time", "")));
        ZonedDateTime entryTime = entryTs > 0
                ? Instant.ofEpochMilli(entryTs).atZone(ET)
                : ZonedDateTime.now(ET);
        double qty = Math.max(1.0, Math.abs(toDouble(buy.get("qty"))));
        double buyAmount = Math.abs(toDouble(buy.get("net_amount")));
        double entryFill = qty > 0 ? buyAmount / (100.0 * qty) : 0.0;
        String underlying = underlyingFromOcc(symbol);
        String direction = inferDirectionFromOcc(symbol);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString().substring(0, 8));
        record.put("ticker", underlying);
        record.put("direction", direction);
        record.put("entry", entryFill);
        record.put("stopLoss", 0.0);
        record.put("takeProfit", 0.0);
        record.put("confidence", 0);
        record.put("strategy", "broker-bootstrap");
        record.put("atr", 0.0);
        record.put("optionsContract", symbol);
        record.put("optionsPremium", entryFill);
        record.put("date", entryTime.format(DATE_FMT));
        record.put("time", entryTime.format(TIME_FMT));
        record.put("timestamp", entryTs);
        return record;
    }

    private Map<String, Object> buildTradeFromBrokerRoundTrip(String symbol, Map<String, Object> buy, Map<String, Object> sell) {
        double entryFill = toDouble(buy.get("filled_avg_price"));
        double exitFill = toDouble(sell.get("filled_avg_price"));
        double qty = Math.max(1.0, Math.abs(toDouble(sell.get("qty"))));
        double pnlAmount = (exitFill - entryFill) * 100.0 * qty;
        double pnlPct = entryFill > 0 ? (exitFill - entryFill) / entryFill * 100.0 : 0.0;
        String outcome = Math.abs(pnlPct) < 0.10 ? "BE_STOP" : (pnlPct > 0 ? "WIN" : "LOSS");

        Map<String, Object> record = buildBrokerSeedRecord(symbol, buy);
        record.put("outcome", outcome);
        record.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
        record.put("pnlAmount", Math.round(pnlAmount * 100.0) / 100.0);
        record.put("resolvedAt", parseEpoch(String.valueOf(sell.getOrDefault("created_at", ""))));
        record.put("brokerExitAt", sell.get("created_at"));
        record.put("exitPrice", exitFill);
        record.put("entryFillPrice", entryFill);
        record.put("resolutionSource", "ALPACA_BOOTSTRAP");
        record.put("brokerSymbol", symbol);
        return record;
    }

    private Map<String, Object> buildOpenTradeFromBroker(String symbol, Map<String, Object> buy) {
        Map<String, Object> record = buildBrokerSeedRecord(symbol, buy);
        record.put("outcome", "OPEN");
        record.put("pnlPct", 0.0);
        record.put("pnlAmount", null);
        record.put("resolutionSource", "ALPACA_BOOTSTRAP");
        record.put("brokerSymbol", symbol);
        return record;
    }

    private Map<String, Object> buildBrokerSeedRecord(String symbol, Map<String, Object> buy) {
        long entryTs = parseEpoch(String.valueOf(buy.getOrDefault("created_at", "")));
        ZonedDateTime entryTime = entryTs > 0
                ? Instant.ofEpochMilli(entryTs).atZone(ET)
                : ZonedDateTime.now(ET);
        double entryFill = toDouble(buy.get("filled_avg_price"));
        String underlying = underlyingFromOcc(symbol);
        String direction = inferDirectionFromOcc(symbol);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", UUID.randomUUID().toString().substring(0, 8));
        record.put("ticker", underlying);
        record.put("direction", direction);
        record.put("entry", entryFill);
        record.put("stopLoss", 0.0);
        record.put("takeProfit", 0.0);
        record.put("confidence", 0);
        record.put("strategy", "broker-bootstrap");
        record.put("atr", 0.0);
        record.put("optionsContract", symbol);
        record.put("optionsPremium", entryFill);
        record.put("date", entryTime.format(DATE_FMT));
        record.put("time", entryTime.format(TIME_FMT));
        record.put("timestamp", entryTs);
        return record;
    }

    private String inferDirectionFromOcc(String symbol) {
        String normalized = normalizeOptionSymbol(symbol);
        int idx = 0;
        while (idx < normalized.length() && !Character.isDigit(normalized.charAt(idx))) idx++;
        if (idx + 6 >= normalized.length()) return "unknown";
        char cp = normalized.charAt(idx + 6);
        return cp == 'P' ? "short" : cp == 'C' ? "long" : "unknown";
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
}
