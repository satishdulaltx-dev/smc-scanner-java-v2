package com.smcscanner.scheduler;

import com.smcscanner.alert.AlertDedup;
import com.smcscanner.alert.DiscordAlertService;
import com.smcscanner.broker.AlpacaOrderService;
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
import java.util.Set;

@Component
@EnableScheduling
public class ScannerScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScannerScheduler.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime NY_OPEN=LocalTime.of(9,30), NY_CLOSE=LocalTime.of(16,0);

    /** NYSE market holidays — market is fully closed on these dates. */
    private static final Set<LocalDate> NYSE_HOLIDAYS = Set.of(
        // 2025
        LocalDate.of(2025, 1,  1),  // New Year's Day
        LocalDate.of(2025, 1, 20),  // MLK Day
        LocalDate.of(2025, 2, 17),  // Presidents Day
        LocalDate.of(2025, 4, 18),  // Good Friday
        LocalDate.of(2025, 5, 26),  // Memorial Day
        LocalDate.of(2025, 6, 19),  // Juneteenth
        LocalDate.of(2025, 7,  4),  // Independence Day
        LocalDate.of(2025, 9,  1),  // Labor Day
        LocalDate.of(2025,11, 27),  // Thanksgiving
        LocalDate.of(2025,12, 25),  // Christmas
        // 2026
        LocalDate.of(2026, 1,  1),  // New Year's Day
        LocalDate.of(2026, 1, 19),  // MLK Day
        LocalDate.of(2026, 2, 16),  // Presidents Day
        LocalDate.of(2026, 4,  3),  // Good Friday
        LocalDate.of(2026, 5, 25),  // Memorial Day
        LocalDate.of(2026, 6, 19),  // Juneteenth
        LocalDate.of(2026, 7,  3),  // Independence Day (observed)
        LocalDate.of(2026, 9,  7),  // Labor Day
        LocalDate.of(2026,11, 26),  // Thanksgiving
        LocalDate.of(2026,12, 25)   // Christmas
    );

    private static boolean isNyseHoliday(LocalDate date) {
        return NYSE_HOLIDAYS.contains(date);
    }
    private static final LocalTime EOD_TRIGGER=LocalTime.of(21,0), EOD_CUTOFF=LocalTime.of(22,0); // 9-10 PM ET (8-9 PM CST) — includes post-market data
    private static final LocalTime DAILY_REPORT_TRIGGER=LocalTime.of(16,30), DAILY_REPORT_CUTOFF=LocalTime.of(16,45);
    private static final LocalTime FORCE_CLOSE_TRIGGER=LocalTime.of(15,55), FORCE_CLOSE_CUTOFF=LocalTime.of(16,0); // 3:55–4:00 PM ET

    private volatile boolean eodSentToday=false;
    private volatile boolean dailyReportSentToday=false;
    private volatile boolean forceCloseDoneToday=false;
    private volatile int lastEodDay=-1;

    private final ScannerConfig      config;
    private final ScannerService     scanner;
    private final EodReportService   eodReport;
    private final DiscordAlertService discord;
    private final AlertDedup         dedup;
    private final SharedState        state;
    private final ReportCache        reportCache;
    private final LiveTradeLog       liveLog;
    private final AlpacaOrderService alpaca;

    public ScannerScheduler(ScannerConfig config, ScannerService scanner, EodReportService eodReport,
                             DiscordAlertService discord, AlertDedup dedup, SharedState state,
                             ReportCache reportCache, LiveTradeLog liveLog, AlpacaOrderService alpaca) {
        this.config=config; this.scanner=scanner; this.eodReport=eodReport;
        this.discord=discord; this.dedup=dedup; this.state=state; this.reportCache=reportCache;
        this.liveLog=liveLog; this.alpaca=alpaca;
    }

    @Scheduled(fixedRateString="${scanner.scan-interval:15}000")
    public void runScan() {
        List<String> tickers=config.loadWatchlist();
        if (tickers.isEmpty()) return;
        ZonedDateTime nowZdt=ZonedDateTime.now(ET);
        if (isNyseHoliday(nowZdt.toLocalDate())) return; // NYSE holiday — skip all equity scanning
        LocalTime nowET=nowZdt.toLocalTime();
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
        if (day!=lastEodDay) { eodSentToday=false; dailyReportSentToday=false; forceCloseDoneToday=false; lastEodDay=day; }
        if (nowET.getDayOfWeek().getValue()>=6||eodSentToday||isNyseHoliday(nowET.toLocalDate())) return;
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
        if (nowET.getDayOfWeek().getValue()>=6||dailyReportSentToday||isNyseHoliday(nowET.toLocalDate())) return;
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

    /** Force-close all open positions at 3:55 PM ET to prevent unintended overnight holds. */
    @Scheduled(fixedRate=30_000)
    public void forceEodClose() {
        if (!alpaca.isEnabled()) return;
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        LocalTime time = nowET.toLocalTime();
        if (nowET.getDayOfWeek().getValue() >= 6 || forceCloseDoneToday || isNyseHoliday(nowET.toLocalDate())) return;
        if (time.isBefore(FORCE_CLOSE_TRIGGER) || time.isAfter(FORCE_CLOSE_CUTOFF)) return;
        log.info("EOD FORCE-CLOSE: 3:55 PM ET — liquidating all open positions");
        forceCloseDoneToday = true;
        try {
            int count = alpaca.closeLosingPositions();
            if (count > 0) {
                discord.sendAlert(":bell: **EOD Smart-Close** — closed **" + count + " position(s)** at 3:55 PM ET (losers closed, winners kept running).");
            }
        } catch (Exception e) {
            log.error("EOD force-close failed: {}", e.getMessage());
        }
    }

    /** Smart trailing stop monitor — checks confirmed 5m candle closes during market hours. */
    @Scheduled(fixedRate=300_000)
    public void checkTrailingStops() {
        if (!alpaca.isEnabled()) return;
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        LocalTime time = nowET.toLocalTime();
        if (nowET.getDayOfWeek().getValue() >= 6 || isNyseHoliday(nowET.toLocalDate())) return;
        // Only run during market hours (9:30 AM - 4:00 PM ET)
        if (time.isBefore(NY_OPEN) || time.isAfter(NY_CLOSE)) return;
        try {
            alpaca.checkTrailingStops();
        } catch (Exception e) {
            log.error("Trailing stop check failed: {}", e.getMessage());
        }
    }
}
