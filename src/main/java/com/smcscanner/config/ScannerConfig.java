package com.smcscanner.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.model.TickerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@ConfigurationProperties(prefix = "scanner")
public class ScannerConfig {
    private static final Logger log = LoggerFactory.getLogger(ScannerConfig.class);

    private String polygonApiKey          = "";
    private String discordWebhookUrl      = "";
    private String discordEodWebhookUrl   = "";  // dedicated EOD report channel
    private String discordSwingWebhookUrl = "";  // swing trade alerts channel
    private String discordScalpWebhookUrl = "";  // intraday scalp alerts channel
    private String discordDailyReportWebhookUrl = "";  // daily trade report channel
    private int    scanInterval       = 15;
    private int    minConfidence      = 70;
    private int    dashboardPort      = 8080;
    private String watchlistPath      = "watchlist.json";
    private int    swingLookback      = 5;
    private double displacementAtrMult= 1.8;
    private int    obMaxAgeBars       = 50;
    private double fvgMinSizeAtr      = 0.3;
    private double minRvol            = 1.5;
    private double minAtrPercentile   = 30.0;
    private double maxSpreadPct       = 0.15;

    // ── Dashboard security token ────────────────────────────────────────────
    // Set SCANNER_DASHBOARD_TOKEN env var in Railway to protect sensitive POST endpoints.
    // If blank/unset, protection is disabled (backwards compatible for local dev).
    private String dashboardToken = "";

    public String getDashboardToken() { return dashboardToken; }
    public void setDashboardToken(String v) { this.dashboardToken = v; }

    // ── Alpaca trading config ───────────────────────────────────────────────
    private String  alpacaApiKey        = "";
    private String  alpacaSecretKey     = "";
    private String  alpacaBaseUrl       = "";   // blank = auto (paper/live based on flag)
    private boolean alpacaEnabled       = false; // master switch — must explicitly enable
    private boolean alpacaPaper         = true;  // default to paper trading
    private double  alpacaMaxPosition   = 500;   // max $ per trade
    private double  alpacaDailyLossLimit= -200;  // stop trading after this loss
    private int     alpacaMaxDailyOrders= 10;
    private int     alpacaMinConfidence = 75;    // only auto-trade this confidence+

    // getters
    public String getPolygonApiKey()         { return polygonApiKey; }
    public String getDiscordWebhookUrl()     { return discordWebhookUrl; }
    public String getDiscordEodWebhookUrl()  { return discordEodWebhookUrl; }
    public String getDiscordSwingWebhookUrl() { return discordSwingWebhookUrl; }
    public String getDiscordScalpWebhookUrl() {
        // Fall back to main webhook if no dedicated scalp channel configured
        return (discordScalpWebhookUrl != null && !discordScalpWebhookUrl.isBlank())
               ? discordScalpWebhookUrl : discordWebhookUrl;
    }
    public String getDiscordDailyReportWebhookUrl(){ return discordDailyReportWebhookUrl; }
    public String resolveDailyReportWebhookUrl() {
        return (discordDailyReportWebhookUrl != null && !discordDailyReportWebhookUrl.isBlank())
               ? discordDailyReportWebhookUrl : resolveEodWebhookUrl();
    }
    /** Returns the EOD webhook if set, otherwise falls back to the main webhook. */
    public String resolveEodWebhookUrl()    {
        return (discordEodWebhookUrl != null && !discordEodWebhookUrl.isBlank())
               ? discordEodWebhookUrl : discordWebhookUrl;
    }
    public int    getScanInterval()        { return scanInterval; }
    public int    getMinConfidence()       { return minConfidence; }
    public int    getDashboardPort()       { return dashboardPort; }
    public String getWatchlistPath()       { return watchlistPath; }
    public int    getSwingLookback()       { return swingLookback; }
    public double getDisplacementAtrMult() { return displacementAtrMult; }
    public int    getObMaxAgeBars()        { return obMaxAgeBars; }
    public double getFvgMinSizeAtr()       { return fvgMinSizeAtr; }
    public double getMinRvol()             { return minRvol; }
    public double getMinAtrPercentile()    { return minAtrPercentile; }
    public double getMaxSpreadPct()        { return maxSpreadPct; }

    // Alpaca getters
    public String  getAlpacaApiKey()         { return alpacaApiKey; }
    public String  getAlpacaSecretKey()      { return alpacaSecretKey; }
    public String  getAlpacaBaseUrl()        { return alpacaBaseUrl; }
    public boolean isAlpacaEnabled()         { return alpacaEnabled; }
    public boolean isAlpacaPaper()           { return alpacaPaper; }
    public double  getAlpacaMaxPosition()    { return alpacaMaxPosition; }
    public double  getAlpacaDailyLossLimit() { return alpacaDailyLossLimit; }
    public int     getAlpacaMaxDailyOrders() { return alpacaMaxDailyOrders; }
    public int     getAlpacaMinConfidence()  { return alpacaMinConfidence; }

    // setters (required by @ConfigurationProperties)
    public void setPolygonApiKey(String v)          { this.polygonApiKey = v; }
    public void setDiscordWebhookUrl(String v)      { this.discordWebhookUrl = v; }
    public void setDiscordEodWebhookUrl(String v)   { this.discordEodWebhookUrl = v; }
    public void setDiscordSwingWebhookUrl(String v)  { this.discordSwingWebhookUrl = v; }
    public void setDiscordScalpWebhookUrl(String v)  { this.discordScalpWebhookUrl = v; }
    public void setDiscordDailyReportWebhookUrl(String v) { this.discordDailyReportWebhookUrl = v; }
    public void setScanInterval(int v)           { this.scanInterval = v; }
    public void setMinConfidence(int v)          { this.minConfidence = v; }
    public void setDashboardPort(int v)          { this.dashboardPort = v; }
    public void setWatchlistPath(String v)       { this.watchlistPath = v; }
    public void setSwingLookback(int v)          { this.swingLookback = v; }
    public void setDisplacementAtrMult(double v) { this.displacementAtrMult = v; }
    public void setObMaxAgeBars(int v)           { this.obMaxAgeBars = v; }
    public void setFvgMinSizeAtr(double v)       { this.fvgMinSizeAtr = v; }
    public void setMinRvol(double v)             { this.minRvol = v; }
    public void setMinAtrPercentile(double v)    { this.minAtrPercentile = v; }
    public void setMaxSpreadPct(double v)        { this.maxSpreadPct = v; }

    // Alpaca setters
    public void setAlpacaApiKey(String v)         { this.alpacaApiKey = v; }
    public void setAlpacaSecretKey(String v)      { this.alpacaSecretKey = v; }
    public void setAlpacaBaseUrl(String v)        { this.alpacaBaseUrl = v; }
    public void setAlpacaEnabled(boolean v)       { this.alpacaEnabled = v; }
    public void setAlpacaPaper(boolean v)         { this.alpacaPaper = v; }
    public void setAlpacaMaxPosition(double v)    { this.alpacaMaxPosition = v; }
    public void setAlpacaDailyLossLimit(double v) { this.alpacaDailyLossLimit = v; }
    public void setAlpacaMaxDailyOrders(int v)    { this.alpacaMaxDailyOrders = v; }
    public void setAlpacaMinConfidence(int v)     { this.alpacaMinConfidence = v; }

    // ── Per-ticker profiles ───────────────────────────────────────────────────
    private final Map<String, TickerProfile> profileCache = new ConcurrentHashMap<>();
    private volatile boolean profilesLoaded = false;

    public TickerProfile getTickerProfile(String ticker) {
        if (!profilesLoaded) loadTickerProfiles();
        return profileCache.getOrDefault(ticker, TickerProfile.DEFAULT);
    }

    /** Temporarily override a profile (for optimizer backtests). Returns the previous profile. */
    public TickerProfile setProfileOverride(String ticker, TickerProfile override) {
        if (!profilesLoaded) loadTickerProfiles();
        TickerProfile prev = profileCache.get(ticker);
        profileCache.put(ticker, override);
        return prev;
    }

    /** Restore a previously saved profile after optimizer run. */
    public void restoreProfile(String ticker, TickerProfile original) {
        if (original != null) profileCache.put(ticker, original);
        else profileCache.remove(ticker);
    }

    private synchronized void loadTickerProfiles() {
        if (profilesLoaded) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream in = new ClassPathResource("ticker-profiles.json").getInputStream();
            JsonNode root = mapper.readTree(in);
            JsonNode profiles = root.get("profiles");
            if (profiles != null && profiles.isArray()) {
                for (JsonNode node : profiles) {
                    TickerProfile p = mapper.treeToValue(node, TickerProfile.class);
                    if (p.getTicker() != null) profileCache.put(p.getTicker(), p);
                }
            }
            log.info("Loaded {} ticker profiles", profileCache.size());
        } catch (Exception e) {
            log.warn("Could not load ticker-profiles.json: {}", e.getMessage());
        }
        profilesLoaded = true;
    }

    public List<String> loadWatchlist() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream in = new ClassPathResource(watchlistPath).getInputStream();
            JsonNode root = mapper.readTree(in);
            JsonNode tickers = root.get("tickers");
            List<String> result = new ArrayList<>();
            if (tickers != null && tickers.isArray()) {
                for (JsonNode t : tickers) {
                    String ticker = t.asText().trim();
                    if (!ticker.isEmpty() && !result.contains(ticker)) result.add(ticker);
                }
            }
            log.info("Watchlist loaded: {} tickers", result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to load watchlist: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
