package com.smcscanner;

import com.smcscanner.config.ScannerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class SmcScannerApplication {
    private static final Logger log = LoggerFactory.getLogger(SmcScannerApplication.class);

    @Autowired private ScannerConfig config;

    public static void main(String[] args) { SpringApplication.run(SmcScannerApplication.class, args); }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("=".repeat(60));
        log.info("  SD SCANNER — Smart Money Concepts  (Spring Boot)");
        log.info("=".repeat(60));
        log.info("  Watchlist     : {} tickers", config.loadWatchlist().size());
        log.info("  Polygon key   : {}", config.getPolygonApiKey().isBlank() ? "NOT SET" : "configured");
        log.info("  Discord       : {}", config.getDiscordWebhookUrl().isBlank() ? "NOT SET" : "configured");
        log.info("  Dashboard     : http://localhost:{}", config.getDashboardPort());
        log.info("  NY session    : 9:30 AM – 4:00 PM ET  (8:30 AM – 3:00 PM CST)");
        log.info("  EOD report    : fires at 9:00 PM ET   (8:00 PM CST) — includes post-market");
        log.info("=".repeat(60));
    }
}
