package com.smcscanner.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.config.ScannerConfig;
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

    /** Overload without news (swing alerts, crypto, etc.). */
    public boolean sendSetupAlert(TradeSetup s) {
        return sendSetupAlert(s, NewsSentiment.NONE);
    }

    public boolean sendSetupAlert(TradeSetup s, NewsSentiment sentiment) {
        String url=config.getDiscordWebhookUrl();
        if (url==null||url.isBlank()) { log.warn("No Discord webhook URL"); return false; }
        return postEmbeds(url, List.of(buildEmbed(s, sentiment)));
    }

    private Map<String,Object> buildEmbed(TradeSetup s) {
        return buildEmbed(s, NewsSentiment.NONE);
    }

    private Map<String,Object> buildEmbed(TradeSetup s, NewsSentiment sentiment) {
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
        // Build field list — append news row only when there's recent sentiment
        List<Map<String,Object>> fields = new java.util.ArrayList<>(List.of(
            f("Direction",  arrow+" "+s.getDirection().toUpperCase(), true),
            f("Confidence", grade+" "+s.getConfidence()+"/100",       true),
            f("Strategy",   strategy,                                  true),
            f("Entry",      String.format("$%.4f",s.getEntry()),       true),
            f("Stop Loss",  String.format("$%.4f (-%.2f%%)",s.getStopLoss(),slPct),  true),
            f("Take Profit",String.format("$%.4f (+%.2f%%)",s.getTakeProfit(),tpPct),true),
            f("R:R",        String.format("%.1f:1",s.rrRatio()),       true),
            f("Session",    s.getSession()!=null?s.getSession():"—",   true),
            f("ATR",        String.format("$%.4f",s.getAtr()),         true)));

        // Add news sentiment field when Polygon returned recent articles
        if (sentiment != null && sentiment.hasNews() && sentiment.label() != null) {
            String newsConflict = sentiment.isConflicting(s.getDirection()) ? " ⚠️ CONFLICTS WITH TRADE" : "";
            String headline = sentiment.headline() != null
                    ? (sentiment.headline().length() > 100
                       ? sentiment.headline().substring(0, 97) + "…"
                       : sentiment.headline())
                    : "";
            fields.add(f("News (48h)", sentiment.label() + newsConflict + "\n" + headline, false));
        }
        Map<String,Object> e=new HashMap<>();
        e.put("title",arrow+" "+s.getTicker()+" — "+s.getDirection().toUpperCase()+" Setup");
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
        List<Map<String,Object>> fields = List.of(
            f("Direction",  arrow+" "+s.getDirection().toUpperCase(), true),
            f("Confidence", grade+" "+s.getConfidence()+"/100",       true),
            f("Timeframe",  "📅 Daily",                               true),
            f("Entry Zone", String.format("$%.4f", s.getEntry()),     true),
            f("Stop Loss",  String.format("$%.4f (-%.2f%%)", s.getStopLoss(),  slPct), true),
            f("Take Profit",String.format("$%.4f (+%.2f%%)", s.getTakeProfit(), tpPct), true),
            f("R:R",        String.format("%.1f:1", s.rrRatio()),     true),
            f("ATR (Daily)",String.format("$%.4f",  s.getAtr()),      true),
            f("Hold",       "1–5 days (swing)",                       true));
        Map<String,Object> e = new HashMap<>();
        e.put("title",  "📊 "+s.getTicker()+" — SWING "+s.getDirection().toUpperCase());
        e.put("color",  isLong ? 0xFF8C00 : 0x6A5ACD); // orange long / purple short
        e.put("fields", fields);
        e.put("footer", Map.of("text", "SD Scanner · Swing · Daily Bars | "+ts));
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
