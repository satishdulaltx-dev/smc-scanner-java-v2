package com.smcscanner.scheduler;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.TickerStatus;
import com.smcscanner.model.eod.TickerReport;
import com.smcscanner.state.ReportCache;
import com.smcscanner.state.SharedState;
import com.smcscanner.strategy.EodReportService;
import com.smcscanner.strategy.ScannerService;
import com.smcscanner.tracking.LiveTradeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
@EnableScheduling
public class ScannerScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScannerScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime NY_OPEN=LocalTime.of(9,30), NY_CLOSE=LocalTime.of(16,0);
    private static final LocalTime EOD_TRIGGER=LocalTime.of(21,0), EOD_CUTOFF=LocalTime.of(22,0); // 9-10 PM ET (8-9 PM CST) — includes post-market data
    private static final LocalTime DAILY_REPORT_TRIGGER=LocalTime.of(16,30), DAILY_REPORT_CUTOFF=LocalTime.of(16,45);

    private volatile boolean eodSentToday=false;
    private volatile boolean dailyReportSentToday=false;
    private volatile int lastEodDay=-1;

    private final ScannerConfig      config;
    private final ScannerService     scanner;
    private final EodReportService   eodReport;
    private final DiscordAlertService discord;
    private final AlertDedup         dedup;
    private final SharedState        state;
    private final ReportCache        reportCache;
    private final LiveTradeLog       liveLog;

    public ScannerScheduler(ScannerConfig config, ScannerService scanner, EodReportService eodReport,
                             DiscordAlertService discord, AlertDedup dedup, SharedState state,
                             ReportCache reportCache, LiveTradeLog liveLog) {
        this.config=config; this.scanner=scanner; this.eodReport=eodReport;
        this.discord=discord; this.dedup=dedup; this.state=state; this.reportCache=reportCache;
        this.liveLog=liveLog;
    }

    @Scheduled(fixedRateString="${scanner.scan-interval:15}000")
    public void runScan() {
        List<String> tickers=config.loadWatchlist();
        if (tickers.isEmpty()) return;
        LocalTime nowET=ZonedDateTime.now(ET).toLocalTime();
        boolean inNy=!nowET.isBefore(NY_OPEN)&&!nowET.isAfter(NY_CLOSE);
        state.setStatus("running");
        state.setLastScan(ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("h:mm:ss a")));
        for (String ticker:tickers) {
            boolean isC=ticker.startsWith("X:");
            if (!isC&&!inNy) {
                state.updateTicker(TickerStatus.builder().ticker(ticker).status("idle").direction(null).confidence(0).phaseMsg("Outside session — skipped").build());
                continue;
            }
            scanner.scanTicker(ticker);
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
        dedup.cleanup();
    }

    @Scheduled(fixedRate=60_000)
    public void checkEodReport() {
        ZonedDateTime nowET=ZonedDateTime.now(ET);
        LocalTime time=nowET.toLocalTime(); int day=nowET.getDayOfYear();
        if (day!=lastEodDay) { eodSentToday=false; dailyReportSentToday=false; lastEodDay=day; }
        if (nowET.getDayOfWeek().getValue()>=6||eodSentToday) return;
        if (time.isBefore(EOD_TRIGGER)||time.isAfter(EOD_CUTOFF)) return;
        log.info("Generating overnight watchlist report (post-market + next morning prep)...");
        eodSentToday=true;
        try {
            List<TickerReport> reports=eodReport.generateReport(config.loadWatchlist());
            reportCache.save(reports);
            log.info("\n{}", eodReport.formatTextReport(reports));
            discord.sendEodReport(reports);
        } catch (Exception e) { log.error("EOD report failed: {}",e.getMessage()); }
    }

    /** Daily trade report — sent at 4:30 PM ET with today's live trade summary + cumulative stats. */
    @Scheduled(fixedRate=60_000)
    public void checkDailyTradeReport() {
        ZonedDateTime nowET=ZonedDateTime.now(ET);
        LocalTime time=nowET.toLocalTime();
        if (nowET.getDayOfWeek().getValue()>=6||dailyReportSentToday) return;
        if (time.isBefore(DAILY_REPORT_TRIGGER)||time.isAfter(DAILY_REPORT_CUTOFF)) return;
        log.info("Generating daily trade report...");
        dailyReportSentToday=true;
        try {
            // Auto-resolve OPEN trades by checking current price vs SL/TP
            liveLog.resolveOpenTrades();
            String today = nowET.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Map<String,Object> embed = liveLog.buildDailyDiscordEmbed(today);
            if (embed != null) {
                discord.sendDailyReport(embed);
                log.info("Daily trade report sent for {}", today);
            } else {
                log.info("No trades today — daily report skipped");
            }
        } catch (Exception e) { log.error("Daily trade report failed: {}", e.getMessage()); }
    }
}
