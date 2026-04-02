package com.smcscanner.dashboard;

import com.smcscanner.analysis.AnalysisService;
import com.smcscanner.backtest.BacktestService;
import com.smcscanner.backtest.ProfileOptimizer;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.filter.AdaptiveSuppressor;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TickerStatus;
import com.smcscanner.model.eod.Level;
import com.smcscanner.model.eod.TickerReport;
import com.smcscanner.state.ReportCache;
import com.smcscanner.state.SharedState;
import com.smcscanner.strategy.EodReportService;
import com.smcscanner.strategy.SessionFilter;
import com.smcscanner.tracking.LiveTradeLog;
import com.smcscanner.tracking.PerformanceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import com.smcscanner.alert.DiscordAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.view.RedirectView;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class DashboardController {
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final SharedState        state;
    private final ScannerConfig      config;
    private final SessionFilter      sessionFilter;
    private final PerformanceTracker tracker;
    private final EodReportService   eodReport;
    private final DiscordAlertService discord;
    private final ReportCache        reportCache;
    private final BacktestService    backtestService;
    private final AdaptiveSuppressor adaptive;
    private final AnalysisService    analysisService;
    private final LiveTradeLog       liveLog;
    private final PolygonClient      polygon;
    private final ProfileOptimizer   optimizer;

    private static final Map<String,String> TV_MAP = Map.of(
        "X:BTCUSD", "BINANCE:BTCUSDT",
        "X:ETHUSD", "BINANCE:ETHUSDT",
        "X:SOLUSD", "BINANCE:SOLUSDT",
        "X:XRPUSD", "BINANCE:XRPUSDT"
    );

    private String tvUrl(String ticker) {
        String sym = TV_MAP.getOrDefault(ticker, ticker);
        return "https://www.tradingview.com/chart/?symbol=" + sym;
    }

    public DashboardController(SharedState state, ScannerConfig config, SessionFilter sessionFilter,
                                PerformanceTracker tracker, EodReportService eodReport,
                                DiscordAlertService discord, ReportCache reportCache,
                                BacktestService backtestService, AdaptiveSuppressor adaptive,
                                AnalysisService analysisService, LiveTradeLog liveLog,
                                PolygonClient polygon, ProfileOptimizer optimizer) {
        this.state=state; this.config=config; this.sessionFilter=sessionFilter;
        this.tracker=tracker; this.eodReport=eodReport; this.discord=discord;
        this.reportCache=reportCache; this.backtestService=backtestService; this.adaptive=adaptive;
        this.analysisService=analysisService; this.liveLog=liveLog; this.polygon=polygon;
        this.optimizer=optimizer;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<String> tickers = config.loadWatchlist();
        Map<String,TickerStatus> statusMap = state.getTickerStatus();
        List<Map<String,Object>> watchlist = new ArrayList<>();
        for (String t : tickers) {
            TickerStatus ts = statusMap.get(t);
            Map<String,Object> info = new LinkedHashMap<>();
            info.put("ticker",    t);
            info.put("status",    ts!=null ? ts.getStatus()    : "idle");
            info.put("direction", ts!=null ? ts.getDirection() : null);
            info.put("phaseMsg",  ts!=null ? ts.getPhaseMsg()  : "Waiting...");
            info.put("confidence",ts!=null ? ts.getConfidence(): 0);
            info.put("isCrypto",  t.startsWith("X:"));
            info.put("tvUrl",     tvUrl(t));
            watchlist.add(info);
        }
        List<Map<String,Object>> setups = state.getSetups();
        String now = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("h:mm:ss a"));
        model.addAttribute("title",         "SD SCANNER");
        model.addAttribute("subtitle",      "Smart Money Concepts — Real-time Intraday Scanner");
        model.addAttribute("runStatus",     state.getStatus().toUpperCase());
        model.addAttribute("watchlistSize", tickers.size());
        model.addAttribute("activeSetups",  setups.size());
        model.addAttribute("currentSession", sessionFilter.currentSession());
        model.addAttribute("lastScan",      state.getLastScan()!=null ? state.getLastScan() : now);
        model.addAttribute("watchlist",     watchlist);
        model.addAttribute("setups",        setups);
        return "dashboard";
    }

    @GetMapping("/api/status")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> apiStatus() {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("status",            state.getStatus());
        resp.put("last_scan",         state.getLastScan());
        resp.put("active_setups",     state.getSetups().size());
        resp.put("session",           sessionFilter.currentSession());
        resp.put("is_active_session", sessionFilter.isActiveSession());
        resp.put("active_sessions",   sessionFilter.getActiveSessions());
        resp.put("timestamp", ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("h:mm:ss a")));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/setups")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> apiSetups() {
        List<Map<String,Object>> setups = new ArrayList<>(state.getSetups());
        setups.sort((a, b) -> {
            int ca = ((Number) a.getOrDefault("confidence", 0)).intValue();
            int cb = ((Number) b.getOrDefault("confidence", 0)).intValue();
            return Integer.compare(cb, ca);
        });
        return ResponseEntity.ok(setups);
    }

    @GetMapping("/api/watchlist")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> apiWatchlist() {
        List<String> tickers = config.loadWatchlist();
        Map<String,TickerStatus> sm = state.getTickerStatus();
        List<Map<String,Object>> result = tickers.stream().map(t -> {
            TickerStatus ts = sm.get(t);
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("ticker",     t);
            m.put("status",     ts!=null ? ts.getStatus()    : "idle");
            m.put("direction",  ts!=null ? ts.getDirection() : null);
            m.put("confidence", ts!=null ? ts.getConfidence(): 0);
            m.put("phase_msg",  ts!=null ? ts.getPhaseMsg()  : "Waiting...");
            m.put("is_crypto",  t.startsWith("X:"));
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** GET /api/chart?ticker=AAPL&tf=5m — real-time OHLCV candles from Polygon */
    @GetMapping("/api/chart")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> apiChart(
            @org.springframework.web.bind.annotation.RequestParam String ticker,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "5m") String tf) {
        // Map timeframe to lookback: daily=365d, hourly=30d, minute=2d
        int limit = tf.endsWith("d") || tf.equals("1d") || tf.equals("D") ? 365 : (tf.contains("h") || tf.equals("60m") ? 200 : 200);
        String normalizedTf = tf.equals("D") ? "1d" : tf;
        List<OHLCV> bars = polygon.getBars(ticker, normalizedTf, limit);
        List<Map<String,Object>> candles = new ArrayList<>();
        for (OHLCV b : bars) {
            Map<String,Object> c = new LinkedHashMap<>();
            // Lightweight Charts expects time in seconds (Unix epoch)
            long ts = Long.parseLong(b.getTimestamp());
            c.put("time", ts > 9999999999L ? ts / 1000 : ts); // ms→sec if needed
            c.put("open",   b.getOpen());
            c.put("high",   b.getHigh());
            c.put("low",    b.getLow());
            c.put("close",  b.getClose());
            c.put("volume", b.getVolume());
            candles.add(c);
        }
        return ResponseEntity.ok(candles);
    }

    @GetMapping("/api/performance")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> apiPerformance() {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("stats",  tracker.getStats());
        resp.put("recent", tracker.getRecent(20));
        return ResponseEntity.ok(resp);
    }

    /** GET /api/optimize?ticker=AAPL&days=90 — find best strategy+params for a ticker */
    @GetMapping("/api/optimize")
    @ResponseBody
    public ResponseEntity<ProfileOptimizer.OptimizeResult> apiOptimize(
            @org.springframework.web.bind.annotation.RequestParam String ticker,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "90") int days) {
        ProfileOptimizer.OptimizeResult result = optimizer.optimize(ticker, days);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/eod-report")
    @ResponseBody
    public ResponseEntity<List<Map<String,Object>>> apiEodReport() {
        try {
            List<TickerReport> reports = eodReport.generateReport(config.loadWatchlist());
            List<Map<String,Object>> result = reports.stream().map(r -> {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("ticker",             r.getTicker());
                m.put("bias",               r.getBias());
                m.put("watch_for",          r.getWatchFor());
                m.put("trend_summary",      r.getTrendSummary());
                m.put("action_note",        r.getActionNote());
                m.put("current_price",      r.getCurrentPrice());
                m.put("atr",                r.getAtr());
                m.put("nearest_support",    r.getNearestSupport());
                m.put("nearest_resistance", r.getNearestResistance());
                m.put("support_levels",     r.getSupportLevels());
                m.put("resistance_levels",  r.getResistanceLevels());
                // Volume Profile
                if (r.getVolumeProfile()!=null) {
                    var vp = r.getVolumeProfile();
                    m.put("vpoc", vp.getVpoc());
                    m.put("vah",  vp.getVah());
                    m.put("val",  vp.getVal());
                }
                // Correlation
                if (r.getCorrelation()!=null) {
                    var c = r.getCorrelation();
                    m.put("beta_spy", c.getBetaSpy()); m.put("corr_spy", c.getCorrSpy());
                    m.put("beta_qqq", c.getBetaQqq()); m.put("corr_qqq", c.getCorrQqq());
                }
                // Insider
                if (r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null)
                    m.put("insider_signal", r.getInsiderActivity().getSignal());
                if (r.getError()!=null) m.put("error", r.getError());
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("EOD report API error: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** POST /api/send-eod-discord  — manually trigger the EOD Discord report */
    @PostMapping("/api/send-eod-discord")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> sendEodDiscord() {
        try {
            List<TickerReport> reports = eodReport.generateReport(config.loadWatchlist());
            reportCache.save(reports);
            boolean ok = discord.sendEodReport(reports);
            return ResponseEntity.ok(Map.of("sent", ok, "tickers", reports.size()));
        } catch (Exception e) {
            log.error("Manual EOD Discord send failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── /eod — Full HTML report page ──────────────────────────────────────────

    @GetMapping("/eod")
    public String eodPage(Model model) {
        List<TickerReport> reports = reportCache.hasData()
                ? reportCache.getReports()
                : List.of();

        List<Map<String,Object>> rows = buildEodRows(reports);

        long bullCount   = reports.stream().filter(r->"bullish".equals(r.getBias())).count();
        long bearCount   = reports.stream().filter(r->"bearish".equals(r.getBias())).count();
        long neutCount   = reports.stream().filter(r->"neutral".equals(r.getBias())).count();
        long actionCount = reports.stream().filter(r->r.getActionNote()!=null && !r.getActionNote().startsWith("⏳")).count();
        long insiderCount= reports.stream().filter(r->r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null).count();

        model.addAttribute("generatedAt",  reportCache.getGeneratedAtStr());
        model.addAttribute("totalCount",   reports.size());
        model.addAttribute("bullCount",    bullCount);
        model.addAttribute("bearCount",    bearCount);
        model.addAttribute("neutCount",    neutCount);
        model.addAttribute("actionCount",  actionCount);
        model.addAttribute("insiderCount", insiderCount);
        model.addAttribute("rows",         rows);
        return "eod-report";
    }

    /** GET /eod/refresh — regenerate report then redirect to /eod */
    @GetMapping("/eod/refresh")
    public RedirectView eodRefresh() {
        try {
            List<TickerReport> reports = eodReport.generateReport(config.loadWatchlist());
            reportCache.save(reports);
            log.info("EOD report refreshed via UI: {} tickers", reports.size());
        } catch (Exception e) {
            log.error("EOD refresh failed: {}", e.getMessage());
        }
        return new RedirectView("/eod");
    }

    /** GET /eod/csv — download CSV of last cached report */
    @GetMapping("/eod/csv")
    @ResponseBody
    public ResponseEntity<byte[]> eodCsv() {
        List<TickerReport> reports = reportCache.hasData()
                ? reportCache.getReports()
                : List.of();

        StringBuilder csv = new StringBuilder();
        csv.append("Ticker,Price,Bias,ATR,VPOC,VAH,VAL,Beta_SPY,Corr_SPY,Beta_QQQ,Corr_QQQ,")
           .append("Nearest_Support,Nearest_Resistance,Watch_For,Action_Note,Insider_Signal\n");

        for (TickerReport r : reports) {
            String vpoc="", vah="", val_="", bSpy="", cSpy="", bQqq="", cQqq="", ins="";
            if (r.getVolumeProfile()!=null) {
                vpoc  = String.format("%.2f", r.getVolumeProfile().getVpoc());
                vah   = String.format("%.2f", r.getVolumeProfile().getVah());
                val_  = String.format("%.2f", r.getVolumeProfile().getVal());
            }
            if (r.getCorrelation()!=null) {
                bSpy  = String.format("%.3f", r.getCorrelation().getBetaSpy());
                cSpy  = String.format("%.3f", r.getCorrelation().getCorrSpy());
                bQqq  = String.format("%.3f", r.getCorrelation().getBetaQqq());
                cQqq  = String.format("%.3f", r.getCorrelation().getCorrQqq());
            }
            if (r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null)
                ins = r.getInsiderActivity().getSignal().replace(",", ";");
            String nearSup = r.getNearestSupport()!=null  ? String.format("%.2f",r.getNearestSupport())  : "";
            String nearRes = r.getNearestResistance()!=null? String.format("%.2f",r.getNearestResistance()): "";
            String watchFor    = r.getWatchFor()   !=null ? csvEsc(r.getWatchFor())    : "";
            String actionNote  = r.getActionNote() !=null ? csvEsc(r.getActionNote())  : "";
            csv.append(String.join(",",
                    r.getTicker(),
                    String.format("%.4f", r.getCurrentPrice()),
                    r.getBias()!=null?r.getBias():"",
                    String.format("%.4f", r.getAtr()),
                    vpoc, vah, val_,
                    bSpy, cSpy, bQqq, cQqq,
                    nearSup, nearRes,
                    watchFor, actionNote,
                    ins
            )).append("\n");
        }

        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "eod-report-" + ZonedDateTime.now(ET)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private String csvEsc(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"","\"\"") + "\"";
        return v;
    }

    /** Converts TickerReport list into the row maps the eod-report.html template expects. */
    private List<Map<String,Object>> buildEodRows(List<TickerReport> reports) {
        return reports.stream().map(r -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("ticker",      r.getTicker());
            row.put("price",       r.getCurrentPrice());
            row.put("bias",        r.getBias()!=null ? r.getBias() : "neutral");
            row.put("trendSummary",r.getTrendSummary());
            row.put("watchFor",    r.getWatchFor());
            row.put("actionNote",  r.getActionNote());
            row.put("tvUrl",       tvUrl(r.getTicker()));

            // Volume Profile — always set keys so Thymeleaf doesn't get SpEL "property not found"
            if (r.getVolumeProfile()!=null) {
                row.put("vpoc", r.getVolumeProfile().getVpoc());
                row.put("vah",  r.getVolumeProfile().getVah());
                row.put("val",  r.getVolumeProfile().getVal());
            } else {
                row.put("vpoc", null); row.put("vah", null); row.put("val", null);
            }

            // Correlation — always set keys
            if (r.getCorrelation()!=null) {
                row.put("betaSpy",  Math.round(r.getCorrelation().getBetaSpy()*100.0)/100.0);
                row.put("corrSpy",  Math.round(r.getCorrelation().getCorrSpy()*100.0)/100.0);
                row.put("betaQqq",  Math.round(r.getCorrelation().getBetaQqq()*100.0)/100.0);
                row.put("corrQqq",  Math.round(r.getCorrelation().getCorrQqq()*100.0)/100.0);
            } else {
                row.put("betaSpy", null); row.put("corrSpy", null);
                row.put("betaQqq", null); row.put("corrQqq", null);
            }

            // Support / Resistance levels (pass Level objects directly — Thymeleaf reads getters)
            row.put("supportLevels",    r.getSupportLevels()!=null    ? r.getSupportLevels()    : List.of());
            row.put("resistanceLevels", r.getResistanceLevels()!=null ? r.getResistanceLevels() : List.of());

            // Insider
            boolean hasIns = r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null;
            row.put("hasInsider",   hasIns);
            row.put("insiderSignal",hasIns ? r.getInsiderActivity().getSignal() : null);

            return row;
        }).collect(Collectors.toList());
    }

    /** GET /backtest - serve backtest UI page (no-cache to prevent stale dropdown state) */
    @GetMapping("/backtest")
    public String backtestPage(Model model, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        model.addAttribute("tickers", config.loadWatchlist());
        return "backtest";
    }

    /** GET /analyze - serve ticker analysis page */
    @GetMapping("/analyze")
    public String analyzePage(Model model) {
        model.addAttribute("watchlist", config.loadWatchlist());
        return "analyze";
    }

    /** GET /api/analyze?ticker=JPM - deep analysis of a single ticker */
    @GetMapping("/api/analyze")
    @ResponseBody
    public Map<String, Object> analyzeApi(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "AAPL") String ticker) {
        return analysisService.analyze(ticker);
    }

    /** GET /api/backtest?ticker=AAPL&days=90&mode=INTRADAY */
    @GetMapping("/api/backtest")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> apiBacktest(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="AAPL") String ticker,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="90")  int days,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="ALL") String mode) {
        try {
            var btMode = com.smcscanner.backtest.BacktestMode.fromString(mode);
            var result = backtestService.run(ticker.toUpperCase(), days, btMode);
            Map<String,Object> resp = new LinkedHashMap<>();
            resp.put("ticker",        result.ticker);
            resp.put("lookback_days", result.lookbackDays);
            resp.put("mode",          result.mode.name());
            resp.put("total_trades",  result.total);
            resp.put("wins",          result.wins);
            resp.put("losses",        result.losses);
            resp.put("timeouts",      result.timeouts);
            resp.put("be_stops",      result.beStops);
            resp.put("news_filtered", result.newsFiltered);
            resp.put("ctx_filtered",     result.ctxFiltered);
            resp.put("quality_filtered", result.qualityFiltered);
            resp.put("win_rate",      result.winRate);
            resp.put("avg_win_pct",   Math.round(result.avgWinPct*100)/100.0);
            resp.put("avg_loss_pct",  Math.round(result.avgLossPct*100)/100.0);
            resp.put("expectancy",    Math.round(result.expectancy*100)/100.0);
            // Options aggregate P&L
            resp.put("opt_total_pnl",      Math.round(result.totalOptPnl*100)/100.0);
            resp.put("opt_avg_win_pnl",    Math.round(result.avgOptWinPnl*100)/100.0);
            resp.put("opt_avg_loss_pnl",   Math.round(result.avgOptLossPnl*100)/100.0);
            resp.put("opt_expectancy",     Math.round(result.optExpectancy*100)/100.0);
            resp.put("opt_total_return",   result.optTotalReturn);
            // Confidence bucket analysis
            resp.put("bucket_85plus_total",  result.bucket85PlusTotal);
            resp.put("bucket_85plus_wins",   result.bucket85PlusWins);
            resp.put("bucket_85plus_wr",     result.bucket85PlusWR);
            resp.put("bucket_85plus_exp",    result.bucket85PlusExp);
            resp.put("bucket_75to84_total",  result.bucket75to84Total);
            resp.put("bucket_75to84_wins",   result.bucket75to84Wins);
            resp.put("bucket_75to84_wr",     result.bucket75to84WR);
            resp.put("bucket_75to84_exp",    result.bucket75to84Exp);
            resp.put("bucket_below75_total", result.bucketBelow75Total);
            resp.put("bucket_below75_wins",  result.bucketBelow75Wins);
            resp.put("bucket_below75_wr",    result.bucketBelow75WR);
            resp.put("bucket_below75_exp",   result.bucketBelow75Exp);
            if (result.error != null) resp.put("error", result.error);
            List<Map<String,Object>> tradeList = result.trades.stream().map(t -> {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("entry_time", t.entryTime()); m.put("exit_time", t.exitTime());
                m.put("dir", t.direction()); m.put("entry", t.entry());
                m.put("sl", t.sl()); m.put("tp", t.tp());
                m.put("outcome", t.outcome()); m.put("pnl_pct", t.pnlPct());
                m.put("confidence", t.confidence()); m.put("atr", t.atr());
                if (t.newsAdjustment() != 0) m.put("news_adj",   t.newsAdjustment());
                if (t.newsLabel() != null)   m.put("news_label", t.newsLabel());
                if (t.ctxAdjustment() != 0)      m.put("ctx_adj",     t.ctxAdjustment());
                if (t.ctxLabel() != null)        m.put("ctx_label",   t.ctxLabel());
                if (t.qualityAdjustment() != 0)  m.put("qual_adj",    t.qualityAdjustment());
                if (t.qualityLabel() != null)     m.put("qual_label",  t.qualityLabel());
                // Options P&L per trade
                if (t.optEntryPremium() > 0) {
                    m.put("opt_entry_premium",   t.optEntryPremium());
                    m.put("opt_exit_premium",    t.optExitPremium());
                    m.put("opt_pnl_per_contract", t.optPnlPerContract());
                    m.put("opt_pnl_pct",         t.optPnlPct());
                }
                return m;
            }).collect(java.util.stream.Collectors.toList());
            resp.put("trades", tradeList);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Backtest error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Adaptive suppressor endpoints ──────────────────────────────────────────

    /** GET /api/adaptive — current streak state for all tickers. */
    @GetMapping("/api/adaptive")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> adaptiveState() {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("active_streaks", adaptive.getActiveStreaks());
        resp.put("snapshot",       adaptive.getSnapshot());
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/adaptive/outcome?ticker=SNAP&outcome=loss
     * Records a live trade outcome. outcome param: "win" or "loss".
     * Called manually after reviewing a trade, or by EOD webhook.
     */
    @PostMapping("/api/adaptive/outcome")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> recordOutcome(
            @org.springframework.web.bind.annotation.RequestParam String ticker,
            @org.springframework.web.bind.annotation.RequestParam String outcome) {
        ticker = ticker.toUpperCase();
        if ("win".equalsIgnoreCase(outcome))       adaptive.recordWin(ticker);
        else if ("loss".equalsIgnoreCase(outcome)) adaptive.recordLoss(ticker);
        else return ResponseEntity.badRequest().body(Map.of("error","outcome must be 'win' or 'loss'"));
        return ResponseEntity.ok(Map.of(
                "ticker",   ticker,
                "outcome",  outcome,
                "streak",   adaptive.getConsecutiveLosses(ticker),
                "streaks",  adaptive.getActiveStreaks()));
    }

    /** POST /api/adaptive/reset?ticker=SNAP — clears history for a ticker. */
    @PostMapping("/api/adaptive/reset")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> resetAdaptive(
            @org.springframework.web.bind.annotation.RequestParam String ticker) {
        adaptive.reset(ticker.toUpperCase());
        return ResponseEntity.ok(Map.of("reset", ticker.toUpperCase()));
    }

    // ── Live trade log endpoints ─────────────────────────────────────────────

    /** GET /api/trades/today — today's live trade alerts. */
    @GetMapping("/api/trades/today")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> todayTrades() {
        String today = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return ResponseEntity.ok(liveLog.getDailySummary(today));
    }

    /** GET /api/trades/history — all live trades ever recorded. */
    @GetMapping("/api/trades/history")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> tradeHistory() {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("cumulative", liveLog.getCumulativeStats());
        resp.put("trades", liveLog.getAllTrades());
        return ResponseEntity.ok(resp);
    }

    /** GET /api/trades/daily?date=2026-03-27 — summary for a specific date. */
    @GetMapping("/api/trades/daily")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> dailySummary(
            @org.springframework.web.bind.annotation.RequestParam(required=false) String date) {
        if (date == null || date.isBlank()) {
            date = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        return ResponseEntity.ok(liveLog.getDailySummary(date));
    }

    /**
     * POST /api/trades/resolve?ticker=AAPL&outcome=WIN&pnl=1.25
     * Resolves an open trade. outcome: WIN, LOSS, BE_STOP, TIMEOUT.
     */
    @PostMapping("/api/trades/resolve")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> resolveTrade(
            @org.springframework.web.bind.annotation.RequestParam String ticker,
            @org.springframework.web.bind.annotation.RequestParam String outcome,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") double pnl) {
        boolean resolved = liveLog.resolveTrade(ticker.toUpperCase(), outcome.toUpperCase(), pnl);
        if (!resolved) {
            return ResponseEntity.badRequest().body(Map.of("error", "No open trade found for " + ticker));
        }
        // Also record in adaptive suppressor for streak tracking
        if ("WIN".equalsIgnoreCase(outcome)) adaptive.recordWin(ticker.toUpperCase());
        else if ("LOSS".equalsIgnoreCase(outcome)) adaptive.recordLoss(ticker.toUpperCase());
        return ResponseEntity.ok(Map.of("ticker", ticker.toUpperCase(), "outcome", outcome, "pnl", pnl));
    }

    /**
     * POST /api/trades/daily-report — Trigger daily trade report on demand.
     * Auto-resolves open trades (checks current price vs SL/TP) then sends to Discord.
     */
    @PostMapping("/api/trades/daily-report")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> triggerDailyReport() {
        try {
            liveLog.resolveOpenTrades();
            String today = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Map<String,Object> embed = liveLog.buildDailyDiscordEmbed(today);
            if (embed == null) {
                return ResponseEntity.ok(Map.of("status", "no_trades", "message", "No trades today"));
            }
            discord.sendDailyReport(embed);
            return ResponseEntity.ok(Map.of("status", "sent", "date", today));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /trades — Live trade log page. */
    @GetMapping("/trades")
    public String tradesPage() { return "trades"; }

    @GetMapping("/health")
    @ResponseBody
    public ResponseEntity<Map<String,String>> health() { return ResponseEntity.ok(Map.of("status","UP")); }
}
