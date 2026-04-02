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
        boolean isLong="long".equals(s.getDirection());
        String arrow=isLong?"⬆️":"⬇️";
        String grade=s.getConfidence()>=85?"⭐":(s.getConfidence()>=75?"✅":(s.getConfidence()>=65?"🟡":"⚪"));
        double slPts=Math.abs(s.getEntry()-s.getStopLoss()), tpPts=Math.abs(s.getTakeProfit()-s.getEntry());
        double slPct=s.getEntry()>0?slPts/s.getEntry()*100:0, tpPct=s.getEntry()>0?tpPts/s.getEntry()*100:0;
        String ts=ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+" UTC";
        String strategy = "normal".equals(s.getVolatility())   ? "📊 VWAP Reversion"
                        : "high".equals(s.getVolatility())     ? "🚀 ORB Breakout"
                        : "keylevel".equals(s.getVolatility()) ? "🎯 Key Level Rejection"
                        : "🔷 SMC Sweep+FVG";

        List<Map<String,Object>> fields = new java.util.ArrayList<>();

        // ══════════════════════════════════════════════════════════════════════
        // TOP SECTION — Actionable trade info
        // ══════════════════════════════════════════════════════════════════════

        // ── OPTIONS CONTRACT ─────────────────────────────────────────────────
        if (s.hasOptionsData()) {
            int dte = (int)((java.time.LocalDate.parse(s.getOptionsExpiry()).toEpochDay()
                    - java.time.LocalDate.now().toEpochDay()));
            String contractLabel = String.format("**%s** $%.0f%s  exp %s (%dd)",
                    s.getOptionsType().toUpperCase(), s.getOptionsStrike(),
                    "call".equals(s.getOptionsType()) ? "C" : "P",
                    s.getOptionsExpiry(), dte);
            fields.add(f("🎯 OPTIONS CONTRACT", contractLabel, false));

            String premiumLine = String.format("Premium: **$%.2f** /contract ($%.0f total for %d)",
                    s.getOptionsPremium(), s.getOptionsPremium() * 100 * s.getOptionsSuggested(),
                    s.getOptionsSuggested());
            fields.add(f("💵 Entry Cost", premiumLine, false));

            String greeksLine = String.format("Δ %.3f  |  IV %.1f%%",
                    s.getOptionsDelta(), s.getOptionsIV() * 100);
            fields.add(f("📐 Greeks", greeksLine, false));

            // P&L estimate
            double totalProfit = s.getOptionsProfitPer() * s.getOptionsSuggested();
            double totalLoss   = s.getOptionsLossPer() * s.getOptionsSuggested();
            String pnlLine = String.format(
                    "**If TP hit:** +$%.0f per contract → **+$%.0f** (%d contracts)\n" +
                    "**If SL hit:** -$%.0f per contract → **-$%.0f**\n" +
                    "**Options R:R:** %.1f:1",
                    s.getOptionsProfitPer(), totalProfit, s.getOptionsSuggested(),
                    s.getOptionsLossPer(), totalLoss,
                    s.getOptionsRR());
            fields.add(f("💰 P&L Estimate", pnlLine, false));

            fields.add(f("🔓 Break-Even", String.format("$%.2f (stock must reach this)", s.getOptionsBreakEven()), false));

            // Bracket order prices — what to enter in broker
            double delta = s.getOptionsDelta();
            double optAtTP = Math.max(0.01, s.getOptionsPremium() + delta * (s.getTakeProfit() - s.getEntry()));
            double optAtSL = Math.max(0.01, s.getOptionsPremium() + delta * (s.getStopLoss()  - s.getEntry()));
            String bracketLine = String.format(
                    "**BUY** option @ **$%.2f**\n" +
                    "✅ **SELL TP** @ **$%.2f** (stock $%.2f)\n" +
                    "🛑 **SELL SL** @ **$%.2f** (stock $%.2f)",
                    s.getOptionsPremium(),
                    optAtTP, s.getTakeProfit(),
                    optAtSL, s.getStopLoss());
            fields.add(f("📋 Broker Bracket Orders", bracketLine, false));
        }

        // ── Core setup fields ─────────────────────────────────────────────────
        fields.addAll(List.of(
            f("Direction",  arrow+" "+s.getDirection().toUpperCase(), true),
            f("Confidence", grade+" "+s.getConfidence()+"/100",       true),
            f("Strategy",   strategy,                                  true),
            f("Entry",      String.format("$%.2f (stock)",s.getEntry()),       true),
            f("Stop Loss",  String.format("$%.2f (-%.2f%%)",s.getStopLoss(),slPct),  true),
            f("Take Profit",String.format("$%.2f (+%.2f%%)",s.getTakeProfit(),tpPct),true),
            f("R:R",        String.format("%.1f:1",s.rrRatio()),       true),
            f("ATR",        String.format("$%.2f",s.getAtr()),         true)));

        // ── Breakeven stop instruction ────────────────────────────────────────
        fields.add(f("🔒 Breakeven", String.format("Move SL → $%.2f once price hits $%.2f (1:1)",
                    s.getEntry(),
                    isLong ? s.getEntry() + Math.abs(s.getEntry()-s.getStopLoss())
                           : s.getEntry() - Math.abs(s.getEntry()-s.getStopLoss())), false));

        // ══════════════════════════════════════════════════════════════════════
        // SEPARATOR
        // ══════════════════════════════════════════════════════════════════════
        fields.add(f("\u200B", "══════════════════════════════", false));

        // ══════════════════════════════════════════════════════════════════════
        // BOTTOM SECTION — Context & secondary info
        // ══════════════════════════════════════════════════════════════════════

        // ── Options flow sentiment ────────────────────────────────────────
        if (s.getOptionsFlowLabel() != null) {
            String flowConflict = s.getOptionsFlowDir() != null
                    && (("long".equals(s.getDirection()) && "BEARISH".equals(s.getOptionsFlowDir()))
                     || ("short".equals(s.getDirection()) && "BULLISH".equals(s.getOptionsFlowDir())))
                    ? " ⚠️ FLOW CONFLICTS" : "";
            fields.add(f("📊 Options Flow", s.getOptionsFlowLabel() + flowConflict, true));
        }
        if (s.getOptionsMaxPain() > 0) {
            fields.add(f("🧲 Max Pain", String.format("$%.1f (price magnet by expiry)", s.getOptionsMaxPain()), true));
        }

        // ── Conviction tier ─────────────────────────────────────────────────
        if (s.getConvictionTier() != null) {
            fields.add(f("📐 Conviction", s.getConvictionTier(), false));
        }
        if (s.getRiskTier() != null) {
            fields.add(f("📊 Risk Tier", s.getRiskTier(), false));
        }
        if (s.getFactorBreakdown() != null) {
            fields.add(f("🔬 Signal Factors", s.getFactorBreakdown(), false));
        }

        // ── News sentiment ──────────────────────────────────────────────────
        if (sentiment != null && sentiment.hasNews() && sentiment.label() != null) {
            String newsConflict = sentiment.isConflicting(s.getDirection()) ? " ⚠️ CONFLICTS" : "";
            String headline = sentiment.headline() != null
                    ? (sentiment.headline().length() > 100
                       ? sentiment.headline().substring(0, 97) + "…"
                       : sentiment.headline())
                    : "";
            fields.add(f("News (48h)", sentiment.label() + newsConflict + "\n" + headline, false));
        }

        // ── Earnings proximity warning ──────────────────────────────────────
        if (earningsCheck != null && earningsCheck.isNearEarnings() && earningsCheck.label() != null) {
            fields.add(f("📅 Earnings", earningsCheck.label(), false));
        }

        // ── Market context (RS + VIX) ───────────────────────────────────────
        if (context != null && context.rsLabel() != null) {
            String rsConflict = context.isRsConflicting(s.getDirection()) ? " ⚠️ CONFLICTS" : "";
            fields.add(f("RS vs SPY (5d)", context.rsLabel() + rsConflict, true));
        }
        if (context != null && context.vixLabel() != null && context.isVixConflicting(s.getVolatility())) {
            fields.add(f("VIX Regime", context.vixLabel() + " ⚠️ WEAK ENV", true));
        } else if (context != null && context.vixLabel() != null && !"normal".equals(context.vixRegime())) {
            fields.add(f("VIX Regime", context.vixLabel(), true));
        }

        Map<String,Object> e=new HashMap<>();
        String title = s.hasOptionsData()
                ? arrow + " " + s.getTicker() + " — BUY " + s.getOptionsType().toUpperCase()
                  + " $" + String.format("%.0f", s.getOptionsStrike())
                  + " " + s.getOptionsExpiry()
                : arrow + " " + s.getTicker() + " — " + s.getDirection().toUpperCase() + " Setup";
        e.put("title", title);
        e.put("color",isLong?0x2ECC71:0xE74C3C); e.put("fields",fields); e.put("footer",Map.of("text","SD Scanner | "+ts));
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
