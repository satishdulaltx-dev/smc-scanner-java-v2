package com.smcscanner.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.market.EarningsCalendar;
import com.smcscanner.market.MarketContext;
import com.smcscanner.model.TradeSetup;
import com.smcscanner.news.NewsSentiment;
import com.smcscanner.model.eod.TickerReport;
import com.smcscanner.strategy.EodReportService;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class DiscordAlertService {
    private static final Logger log = LoggerFactory.getLogger(DiscordAlertService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ScannerConfig    config;
    private final EodReportService eodReportService;
    private final ObjectMapper     mapper = new ObjectMapper();
    private final OkHttpClient     http   = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

    public DiscordAlertService(ScannerConfig config, EodReportService eodReportService) {
        this.config=config; this.eodReportService=eodReportService;
    }

    /** Overload without news or context (swing alerts, crypto, etc.). */
    public boolean sendSetupAlert(TradeSetup s) {
        return sendSetupAlert(s, NewsSentiment.NONE, MarketContext.NONE);
    }

    public boolean sendSetupAlert(TradeSetup s, NewsSentiment sentiment) {
        return sendSetupAlert(s, sentiment, MarketContext.NONE);
    }

    public boolean sendSetupAlert(TradeSetup s, NewsSentiment sentiment, MarketContext context) {
        return sendSetupAlert(s, sentiment, context, EarningsCalendar.EarningsCheck.NONE);
    }

    public boolean sendSetupAlert(TradeSetup s, NewsSentiment sentiment, MarketContext context,
                                   EarningsCalendar.EarningsCheck earningsCheck) {
        String url=config.getDiscordWebhookUrl();
        if (url==null||url.isBlank()) { log.warn("No Discord webhook URL"); return false; }
        return postEmbeds(url, List.of(buildEmbed(s, sentiment, context, earningsCheck)));
    }

    private Map<String,Object> buildEmbed(TradeSetup s) {
        return buildEmbed(s, NewsSentiment.NONE, MarketContext.NONE, EarningsCalendar.EarningsCheck.NONE);
    }

    private Map<String,Object> buildEmbed(TradeSetup s, NewsSentiment sentiment, MarketContext context,
                                              EarningsCalendar.EarningsCheck earningsCheck) {
        boolean isLong = "long".equals(s.getDirection());
        String arrow   = isLong ? "⬆️" : "⬇️";
        String grade   = s.getConfidence()>=85 ? "⭐" : (s.getConfidence()>=75 ? "✅" : (s.getConfidence()>=65 ? "🟡" : "⚪"));
        double slPct   = s.getEntry()>0 ? Math.abs(s.getEntry()-s.getStopLoss())/s.getEntry()*100 : 0;
        double tpPct   = s.getEntry()>0 ? Math.abs(s.getTakeProfit()-s.getEntry())/s.getEntry()*100 : 0;
        String ts      = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+" UTC";
        String strategy = "normal".equals(s.getVolatility())   ? "VWAP Reversion"
                        : "high".equals(s.getVolatility())     ? "ORB Breakout"
                        : "keylevel".equals(s.getVolatility()) ? "Key Level Rejection"
                        : "SMC Sweep+FVG";

        // ── Build compact description instead of 20+ fields ─────────────────
        StringBuilder desc = new StringBuilder();

        // Options trade section
        if (s.hasOptionsData()) {
            int dte = (int)((java.time.LocalDate.parse(s.getOptionsExpiry()).toEpochDay()
                    - java.time.LocalDate.now().toEpochDay()));
            String typeChar = "call".equals(s.getOptionsType()) ? "C" : "P";

            // Bracket orders — what to actually enter in broker
            double delta = s.getOptionsDelta();
            double optAtTP = Math.max(0.01, s.getOptionsPremium() + delta * (s.getTakeProfit() - s.getEntry()));
            double optAtSL = Math.max(0.01, s.getOptionsPremium() + delta * (s.getStopLoss() - s.getEntry()));

            desc.append(String.format("**BUY** $%.0f%s %s (%dd) @ **$%.2f**\n",
                    s.getOptionsStrike(), typeChar, s.getOptionsExpiry(), dte, s.getOptionsPremium()));
            desc.append(String.format("TP **$%.2f** | SL **$%.2f** | R:R **%.1f:1**\n",
                    optAtTP, optAtSL, s.getOptionsRR()));
            desc.append(String.format("Δ %.2f | IV %.0f%% | BE $%.2f\n",
                    s.getOptionsDelta(), s.getOptionsIV() * 100, s.getOptionsBreakEven()));

            // P&L one-liner
            desc.append(String.format("P&L: **+$%.0f** / **-$%.0f** per contract\n",
                    s.getOptionsProfitPer(), s.getOptionsLossPer()));
            desc.append("\n");
        }

        // Stock levels
        desc.append(String.format("**Entry** $%.2f → **TP** $%.2f (+%.1f%%) | **SL** $%.2f (-%.1f%%)\n",
                s.getEntry(), s.getTakeProfit(), tpPct, s.getStopLoss(), slPct));
        desc.append(String.format("R:R **%.1f:1** | ATR $%.2f | %s\n", s.rrRatio(), s.getAtr(), strategy));

        // Breakeven instruction
        double beTarget = isLong
                ? s.getEntry() + Math.abs(s.getEntry() - s.getStopLoss())
                : s.getEntry() - Math.abs(s.getEntry() - s.getStopLoss());
        desc.append(String.format("Move SL → BE at $%.2f (1:1)\n", beTarget));

        // ── Warnings only (conflicts, earnings, high IV) ────────────────────
        List<String> warnings = new ArrayList<>();

        if (s.getOptionsGreeksWarning() != null) {
            warnings.add(s.getOptionsGreeksWarning());
        }
        if (s.getOptionsFlowDir() != null
                && (("long".equals(s.getDirection()) && "BEARISH".equals(s.getOptionsFlowDir()))
                 || ("short".equals(s.getDirection()) && "BULLISH".equals(s.getOptionsFlowDir())))) {
            warnings.add("Options flow CONFLICTS with direction");
        }
        if (sentiment != null && sentiment.hasNews() && sentiment.isConflicting(s.getDirection())) {
            warnings.add("News sentiment CONFLICTS");
        }
        if (earningsCheck != null && earningsCheck.isNearEarnings() && earningsCheck.label() != null) {
            warnings.add(earningsCheck.label());
        }
        if (context != null && context.isRsConflicting(s.getDirection()) && context.rsLabel() != null) {
            warnings.add("RS vs SPY conflicts");
        }
        if (context != null && context.vixLabel() != null && context.isVixConflicting(s.getVolatility())) {
            warnings.add("VIX: " + context.vixLabel());
        }

        if (!warnings.isEmpty()) {
            desc.append("\n⚠️ ").append(String.join(" | ", warnings));
        }

        // ── Build embed ─────────────────────────────────────────────────────
        List<Map<String,Object>> fields = new java.util.ArrayList<>();
        // Confidence + conviction as single row
        String confLine = grade + " " + s.getConfidence() + "/100";
        if (s.getConvictionTier() != null) confLine += " · " + s.getConvictionTier();
        fields.add(f("Confidence", confLine, true));

        // Flow + Max Pain as compact context
        if (s.getOptionsFlowLabel() != null || s.getOptionsMaxPain() > 0) {
            String ctx = "";
            if (s.getOptionsFlowLabel() != null) ctx += s.getOptionsFlowLabel();
            if (s.getOptionsMaxPain() > 0) ctx += (ctx.isEmpty() ? "" : " | ") + "Max Pain $" + String.format("%.0f", s.getOptionsMaxPain());
            fields.add(f("Flow", ctx, true));
        }

        Map<String,Object> e = new HashMap<>();
        String title = s.hasOptionsData()
                ? arrow + " " + s.getTicker() + " — BUY " + s.getOptionsType().toUpperCase()
                  + " $" + String.format("%.0f", s.getOptionsStrike()) + " " + s.getOptionsExpiry()
                : arrow + " " + s.getTicker() + " — " + s.getDirection().toUpperCase() + " · " + strategy;
        e.put("title", title);
        e.put("description", desc.toString());
        e.put("color", isLong ? 0x2ECC71 : 0xE74C3C);
        e.put("fields", fields);
        e.put("footer", Map.of("text", "SD Scanner | " + ts));
        return e;
    }

    public boolean sendSwingAlert(TradeSetup s) {
        String url = config.getDiscordSwingWebhookUrl();
        if (url == null || url.isBlank()) { log.warn("No swing Discord webhook URL"); return false; }
        return postEmbeds(url, List.of(buildSwingEmbed(s)));
    }

    private Map<String,Object> buildSwingEmbed(TradeSetup s) {
        boolean isRange = "range".equals(s.getDirection());
        boolean isLong  = "long".equals(s.getDirection());
        String arrow    = isRange ? "↔️" : (isLong ? "⬆️" : "⬇️");
        String grade    = s.getConfidence()>=85 ? "⭐" : (s.getConfidence()>=75 ? "✅" : (s.getConfidence()>=65 ? "🟡" : "⚪"));
        double slPct    = s.getEntry()>0 ? Math.abs(s.getEntry()-s.getStopLoss())/s.getEntry()*100 : 0;
        double tpPct    = s.getEntry()>0 ? Math.abs(s.getTakeProfit()-s.getEntry())/s.getEntry()*100 : 0;
        String ts       = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+" UTC";
        boolean isConsolidation = "swing".equals(s.getVolatility());

        StringBuilder desc = new StringBuilder();

        if (isRange) {
            double rangePct = s.getEntry() > 0 ? (s.getFvgTop() - s.getFvgBottom()) / s.getEntry() * 100 : 0;
            desc.append(String.format("**Range** $%.2f – $%.2f (%.1f%%)\n", s.getFvgBottom(), s.getFvgTop(), rangePct));
            desc.append(String.format("Short Put $%.2f | Short Call $%.2f\n", s.getStopLoss(), s.getTakeProfit()));
            desc.append(String.format("Mid $%.2f | ATR $%.2f | Hold 5-10d", s.getEntry(), s.getAtr()));
        } else {
            String strat = isConsolidation ? "Consolidation Breakout" : "SMC Sweep+FVG";
            String tf    = isConsolidation ? "Hourly" : "Daily";
            desc.append(String.format("**Entry** $%.2f → **TP** $%.2f (+%.1f%%) | **SL** $%.2f (-%.1f%%)\n",
                    s.getEntry(), s.getTakeProfit(), tpPct, s.getStopLoss(), slPct));
            desc.append(String.format("R:R **%.1f:1** | ATR $%.2f | %s · %s\n", s.rrRatio(), s.getAtr(), strat, tf));
            desc.append("Hold 2-5 days");
            if (isConsolidation) {
                desc.append(String.format(" | Squeeze $%.2f–$%.2f", s.getFvgBottom(), s.getFvgTop()));
            }
        }

        List<Map<String,Object>> fields = new java.util.ArrayList<>();
        fields.add(f("Confidence", grade + " " + s.getConfidence() + "/100", true));

        Map<String,Object> e = new HashMap<>();
        String title = isRange
                ? "🔲 " + s.getTicker() + " — RANGE (Iron Condor / Straddle)"
                : arrow + " " + s.getTicker() + " — SWING " + s.getDirection().toUpperCase();
        e.put("title",  title);
        e.put("description", desc.toString());
        e.put("color",  isRange ? 0x3498DB : (isLong ? 0xFF8C00 : 0x6A5ACD));
        e.put("fields", fields);
        String source = isRange ? "Range" : (isConsolidation ? "Hourly" : "Daily");
        e.put("footer", Map.of("text", "SD Scanner · Swing · " + source + " | " + ts));
        return e;
    }

    public boolean sendEodReport(List<TickerReport> reports) {
        String url=config.resolveEodWebhookUrl();
        if (url==null||url.isBlank()) return false;
        List<Map<String,Object>> embeds=eodReportService.formatDiscordEmbeds(reports);
        if (embeds.isEmpty()) return true;
        // Rich embeds (VP + corr fields) can be ~700 chars each.
        // Discord hard cap is 6000 chars/message → send max 3 embeds at a time.
        boolean ok=true;
        for (int i=0;i<embeds.size();i+=3) {
            if (!postEmbeds(url,embeds.subList(i,Math.min(i+3,embeds.size())))) ok=false;
            if (i+3 < embeds.size()) { try { Thread.sleep(500); } catch (Exception ignored) {} }
        }
        return ok;
    }

    /** Send daily trade report to the dedicated daily report channel. */
    public boolean sendDailyReport(Map<String,Object> embed) {
        if (embed == null) return true; // no trades = nothing to send
        String url = config.resolveDailyReportWebhookUrl();
        if (url == null || url.isBlank()) { log.warn("No daily report webhook URL"); return false; }
        return postEmbeds(url, List.of(embed));
    }

    private boolean postEmbeds(String url, List<Map<String,Object>> embeds) {
        try {
            String json=mapper.writeValueAsString(Map.of("username","SD Scanner","embeds",embeds));
            try (Response r=http.newCall(new Request.Builder().url(url).post(RequestBody.create(json,JSON)).build()).execute()) {
                if (r.code()==200||r.code()==204) { log.info("Discord alert sent"); return true; }
                log.error("Discord failed ({})",r.code()); return false;
            }
        } catch (Exception e) { log.error("Discord error: {}",e.getMessage()); return false; }
    }

    private Map<String,Object> f(String name,String value,boolean inline) { return Map.of("name",name,"value",value,"inline",inline); }
}
