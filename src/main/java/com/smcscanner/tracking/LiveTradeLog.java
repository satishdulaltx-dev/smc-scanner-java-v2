package com.smcscanner.tracking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import java.io.File;
import java.io.IOException;
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
    // TRADE_LOG_DIR env var lets Railway volume be mounted at e.g. /data
    // Set TRADE_LOG_DIR=/data in Railway + add a Volume mounted at /data
    private static final String FILE_PATH = System.getenv("TRADE_LOG_DIR") != null
            ? System.getenv("TRADE_LOG_DIR").replaceAll("/$", "") + "/live-trades.json"
            : "data/live-trades.json";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper mapper = new ObjectMapper();
    private final PolygonClient client;
    private final List<Map<String, Object>> trades = Collections.synchronizedList(new ArrayList<>());

    public LiveTradeLog(PolygonClient client) {
        this.client = client;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        File f = new File(FILE_PATH);
        if (f.exists()) {
            try {
                List<Map<String, Object>> loaded = mapper.readValue(f, new TypeReference<>() {});
                trades.addAll(loaded);
                log.info("Loaded {} live trade records from {}", trades.size(), FILE_PATH);
            } catch (Exception e) {
                log.warn("Could not load live trades: {}", e.getMessage());
            }
        }
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
        record.put("date", now.format(DATE_FMT));
        record.put("time", now.format(TIME_FMT));
        record.put("timestamp", now.toInstant().toEpochMilli());
        record.put("outcome", "OPEN");
        record.put("pnlPct", 0.0);
        trades.add(record);
        persist();
        log.info("Live trade logged: {} {} {} conf={}", s.getTicker(), s.getDirection(), strategyType, s.getConfidence());
    }

    /** Resolve a trade outcome (WIN/LOSS/BE_STOP/TIMEOUT). Called from API or adaptive flow. */
    public boolean resolveTrade(String ticker, String outcome, double pnlPct) {
        synchronized (trades) {
            // Find most recent OPEN trade for this ticker
            for (int i = trades.size() - 1; i >= 0; i--) {
                Map<String, Object> t = trades.get(i);
                if (ticker.equals(t.get("ticker")) && "OPEN".equals(t.get("outcome"))) {
                    t.put("outcome", outcome);
                    t.put("pnlPct", pnlPct);
                    t.put("resolvedAt", ZonedDateTime.now(ET).toInstant().toEpochMilli());
                    persist();
                    log.info("Trade resolved: {} {} pnl={}%", ticker, outcome, pnlPct);
                    return true;
                }
            }
        }
        return false;
    }

    /** Get all trades for a specific date (yyyy-MM-dd). */
    public List<Map<String, Object>> getTradesForDate(String date) {
        synchronized (trades) {
            return trades.stream()
                    .filter(t -> date.equals(t.get("date")))
                    .collect(Collectors.toList());
        }
    }

    /** Get today's trades. */
    public List<Map<String, Object>> getTodayTrades() {
        return getTradesForDate(ZonedDateTime.now(ET).format(DATE_FMT));
    }

    /** Get all trades (for full history report). */
    public List<Map<String, Object>> getAllTrades() {
        synchronized (trades) {
            return new ArrayList<>(trades);
        }
    }

    /** Generate a daily summary for a given date. */
    public Map<String, Object> getDailySummary(String date) {
        List<Map<String, Object>> dayTrades = getTradesForDate(date);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", date);
        summary.put("totalAlerts", dayTrades.size());

        long wins = dayTrades.stream().filter(t -> "WIN".equals(t.get("outcome"))).count();
        long losses = dayTrades.stream().filter(t -> "LOSS".equals(t.get("outcome"))).count();
        long beStops = dayTrades.stream().filter(t -> "BE_STOP".equals(t.get("outcome"))).count();
        long open = dayTrades.stream().filter(t -> "OPEN".equals(t.get("outcome"))).count();
        long resolved = wins + losses + beStops;

        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("beStops", beStops);
        summary.put("open", open);
        summary.put("resolved", resolved);
        summary.put("winRate", resolved > 0 ? Math.round(wins * 100.0 / (wins + losses) * 10) / 10.0 : 0);

        // P&L
        double totalPnl = dayTrades.stream()
                .filter(t -> !"OPEN".equals(t.get("outcome")))
                .mapToDouble(t -> ((Number) t.getOrDefault("pnlPct", 0.0)).doubleValue())
                .sum();
        summary.put("totalPnlPct", Math.round(totalPnl * 100) / 100.0);

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
        synchronized (trades) {
            Map<String, Object> stats = new LinkedHashMap<>();
            long total = trades.size();
            long wins = trades.stream().filter(t -> "WIN".equals(t.get("outcome"))).count();
            long losses = trades.stream().filter(t -> "LOSS".equals(t.get("outcome"))).count();
            long beStops = trades.stream().filter(t -> "BE_STOP".equals(t.get("outcome"))).count();
            long open = trades.stream().filter(t -> "OPEN".equals(t.get("outcome"))).count();

            stats.put("totalAlerts", total);
            stats.put("wins", wins);
            stats.put("losses", losses);
            stats.put("beStops", beStops);
            stats.put("open", open);
            stats.put("winRate", (wins + losses) > 0
                    ? Math.round(wins * 100.0 / (wins + losses) * 10) / 10.0 : 0);

            double totalPnl = trades.stream()
                    .filter(t -> !"OPEN".equals(t.get("outcome")))
                    .mapToDouble(t -> ((Number) t.getOrDefault("pnlPct", 0.0)).doubleValue())
                    .sum();
            stats.put("totalPnlPct", Math.round(totalPnl * 100) / 100.0);

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

                    if (outcome != null) {
                        t.put("outcome", outcome);
                        t.put("pnlPct", Math.round(pnlPct * 100.0) / 100.0);
                        t.put("resolvedAt", ZonedDateTime.now(ET).toInstant().toEpochMilli());
                        t.put("exitPrice", outcome.equals("WIN") ? tp : sl);
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
            File dir = new File("data");
            if (!dir.exists()) dir.mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(FILE_PATH), trades);
        } catch (IOException e) {
            log.error("Failed to persist live trades: {}", e.getMessage());
        }
    }
}
