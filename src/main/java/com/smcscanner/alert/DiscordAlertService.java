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
        // Detect strategy from volatility field set by each detector
        String strategy = "normal".equals(s.getVolatility())   ? "📊 VWAP Reversion"
                        : "high".equals(s.getVolatility())     ? "🚀 ORB Breakout"
                        : "keylevel".equals(s.getVolatility()) ? "🎯 Key Level Rejection"
                        : "🔷 SMC Sweep+FVG";

        List<Map<String,Object>> fields = new java.util.ArrayList<>();

        // ── OPTIONS CONTRACT (top priority — this is what they trade) ─────────
        if (s.hasOptionsData()) {
            String contractLabel = String.format("**%s** $%.0f%s  exp %s (%dd)",
                    s.getOptionsType().toUpperCase(), s.getOptionsStrike(),
                    "call".equals(s.getOptionsType()) ? "C" : "P",
                    s.getOptionsExpiry(), (int)((java.time.LocalDate.parse(s.getOptionsExpiry()).toEpochDay()
                        - java.time.LocalDate.now().toEpochDay())));
            fields.add(f("🎯 OPTIONS CONTRACT", contractLabel, false));

            String premiumLine = String.format("Premium: **$%.2f** /contract ($%.0f total for %d)",
                    s.getOptionsPremium(), s.getOptionsPremium() * 100 * s.getOptionsSuggested(),
                    s.getOptionsSuggested());
            fields.add(f("💵 Entry Cost", premiumLine, false));

            String greeksLine = String.format("Δ %.3f  |  IV %.1f%%  %s",
                    s.getOptionsDelta(), s.getOptionsIV() * 100,
                    s.getOptionsIVPct() <= 30 ? "✅ Cheap"
                    : s.getOptionsIVPct() >= 70 ? "⚠️ Expensive (IV crush risk)"
                    : "");
            fields.add(f("📐 Greeks", greeksLine, false));

            if (s.getOptionsGreeksWarning() != null) {
                fields.add(f("⚠️ Risk Warnings", s.getOptionsGreeksWarning(), false));
            }

            // P&L per contract
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

            fields.add(f("🔓 Break-Even", String.format("$%.2f (stock must reach this)", s.getOptionsBreakEven()), true));

            // ── Bracket order prices — what to enter in your broker ───────────
            // delta model: option price ≈ premium + delta × (stockPrice - stockEntry)
            // delta is signed: +0.40 for calls, -0.40 for puts
            double delta = s.getOptionsDelta();
            double optAtTP = s.getOptionsPremium() + delta * (s.getTakeProfit() - s.getEntry());
            double optAtSL = s.getOptionsPremium() + delta * (s.getStopLoss()  - s.getEntry());
            optAtTP = Math.max(0.01, optAtTP);
            optAtSL = Math.max(0.01, optAtSL);
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

        // ── Options flow sentiment ────────────────────────────────────────────
        if (s.getOptionsFlowLabel() != null) {
            String flowConflict = s.getOptionsFlowDir() != null
                    && (("long".equals(s.getDirection()) && "BEARISH".equals(s.getOptionsFlowDir()))
                     || ("short".equals(s.getDirection()) && "BULLISH".equals(s.getOptionsFlowDir())))
                    ? " ⚠️ FLOW CONFLICTS" : "";
            fields.add(f("📊 Options Flow", s.getOptionsFlowLabel() + flowConflict, false));
        }
        if (s.getOptionsMaxPain() > 0) {
            fields.add(f("🧲 Max Pain", String.format("$%.1f (price magnet by expiry)", s.getOptionsMaxPain()), true));
        }

        // ── Conviction tier + factor attribution ─────────────────────────────
        if (s.getConvictionTier() != null) {
            fields.add(f("📐 Conviction", s.getConvictionTier(), false));
        }
        if (s.getFactorBreakdown() != null) {
            fields.add(f("🔬 Signal Factors", s.getFactorBreakdown(), false));
        }

        // ── News sentiment field ──────────────────────────────────────────────
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

        // ── Market context fields (RS + VIX) ─────────────────────────────────
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
        boolean isLong = "long".equals(s.getDirection());
        String arrow   = isLong ? "⬆️" : "⬇️";
        String grade   = s.getConfidence()>=85?"⭐":(s.getConfidence()>=75?"✅":(s.getConfidence()>=65?"🟡":"⚪"));
        double slPts   = Math.abs(s.getEntry()-s.getStopLoss()), tpPts = Math.abs(s.getTakeProfit()-s.getEntry());
        double slPct   = s.getEntry()>0?slPts/s.getEntry()*100:0, tpPct = s.getEntry()>0?tpPts/s.getEntry()*100:0;
        String ts      = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+" UTC";
        boolean isConsolidation = "swing".equals(s.getVolatility());
        String timeframe = isConsolidation ? "📅 Hourly Consolidation" : "📅 Daily SMC";
        List<Map<String,Object>> fields = new java.util.ArrayList<>(List.of(
            f("Direction",  arrow+" "+s.getDirection().toUpperCase(), true),
            f("Confidence", grade+" "+s.getConfidence()+"/100",       true),
            f("Strategy",   isConsolidation ? "📦 Consolidation Breakout" : "🔷 SMC Sweep+FVG", true),
            f("Timeframe",  timeframe,                                true),
            f("Entry Zone", String.format("$%.2f", s.getEntry()),     true),
            f("Stop Loss",  String.format("$%.2f (-%.2f%%)", s.getStopLoss(),  slPct), true),
            f("Take Profit",String.format("$%.2f (+%.2f%%)", s.getTakeProfit(), tpPct), true),
            f("R:R",        String.format("%.1f:1", s.rrRatio()),     true),
            f("ATR (Daily)",String.format("$%.2f",  s.getAtr()),      true),
            f("Hold",       "2–5 days (swing)",                       true)));
        if (isConsolidation) {
            fields.add(f("📦 Squeeze Zone", String.format("$%.2f – $%.2f", s.getFvgBottom(), s.getFvgTop()), false));
        }
        Map<String,Object> e = new HashMap<>();
        e.put("title",  "📊 "+s.getTicker()+" — SWING "+s.getDirection().toUpperCase());
        e.put("color",  isLong ? 0xFF8C00 : 0x6A5ACD); // orange long / purple short
        e.put("fields", fields);
        String source = isConsolidation ? "Hourly Consolidation" : "Daily Bars";
        e.put("footer", Map.of("text", "SD Scanner · Swing · " + source + " | "+ts));
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
