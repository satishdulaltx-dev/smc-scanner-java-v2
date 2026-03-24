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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@EnableScheduling
public class ScannerScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScannerScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime NY_OPEN=LocalTime.of(9,30), NY_CLOSE=LocalTime.of(16,0);
    private static final LocalTime EOD_TRIGGER=LocalTime.of(16,5), EOD_CUTOFF=LocalTime.of(16,20);

    private volatile boolean eodSentToday=false;
    private volatile int lastEodDay=-1;

    private final ScannerConfig      config;
    private final ScannerService     scanner;
    private final EodReportService   eodReport;
    private final DiscordAlertService discord;
    private final AlertDedup         dedup;
    private final SharedState        state;
    private final ReportCache        reportCache;

    public ScannerScheduler(ScannerConfig config, ScannerService scanner, EodReportService eodReport,
                             DiscordAlertService discord, AlertDedup dedup, SharedState state,
                             ReportCache reportCache) {
        this.config=config; this.scanner=scanner; this.eodReport=eodReport;
        this.discord=discord; this.dedup=dedup; this.state=state; this.reportCache=reportCache;
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
        if (day!=lastEodDay) { eodSentToday=false; lastEodDay=day; }
        if (nowET.getDayOfWeek().getValue()>=6||eodSentToday) return;
        if (time.isBefore(EOD_TRIGGER)||time.isAfter(EOD_CUTOFF)) return;
        log.info("Generating EOD watchlist report...");
        eodSentToday=true;
        try {
            List<TickerReport> reports=eodReport.generateReport(config.loadWatchlist());
            reportCache.save(reports);
            log.info("\n{}", eodReport.formatTextReport(reports));
            discord.sendEodReport(reports);
        } catch (Exception e) { log.error("EOD report failed: {}",e.getMessage()); }
    }
}
