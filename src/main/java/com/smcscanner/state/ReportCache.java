package com.smcscanner.state;

import com.smcscanner.model.eod.TickerReport;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Holds the most recently generated EOD report in memory.
 * Populated by the scheduler at 4:05 PM ET and by manual triggers.
 */
@Component
public class ReportCache {
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private volatile List<TickerReport> reports;
    private volatile ZonedDateTime      generatedAt;

    public void save(List<TickerReport> reports) {
        this.reports     = reports;
        this.generatedAt = ZonedDateTime.now(ET);
    }

    public List<TickerReport> getReports()   { return reports; }
    public ZonedDateTime      getGeneratedAt() { return generatedAt; }
    public boolean            hasData()       { return reports != null && !reports.isEmpty(); }

    public String getGeneratedAtStr() {
        if (generatedAt == null) return "—";
        return generatedAt.format(DateTimeFormatter.ofPattern("EEEE MMM dd, yyyy  HH:mm 'ET'"));
    }
}
