package com.smcscanner.backtest;

import com.smcscanner.data.*;
import com.smcscanner.model.OHLCV;
import java.time.*;
import java.util.*;

/** Request-local dates, immutable fetched series and coverage. Never shared with the live scanner. */
public final class BacktestRun {
    public final LocalDate start, end;
    public final Map<String, List<OHLCV>> snapshots = new LinkedHashMap<>();
    public final Map<String, Object> coverage = new LinkedHashMap<>();
    public final Map<String, Long> rejected = new TreeMap<>();
    public final List<String> warnings = new ArrayList<>();
    public final String pattern;
    public final Set<String> filters;
    public final boolean research;
    public static final Set<String> PATTERNS = Set.of("scalp", "sweep-flip", "choch-primary", "pdh-pdl");
    public static final Set<String> FILTERS = Set.of("spy", "15m", "volume", "regime", "time");

    public BacktestRun(LocalDate start, LocalDate end) { this(start,end,null,Set.of()); }
    public BacktestRun(LocalDate start, LocalDate end, String pattern, Set<String> filters) {
        if (start == null || end == null || start.isAfter(end) || !end.isBefore(LocalDate.now(ZoneId.of("America/New_York"))))
            throw new IllegalArgumentException("Use an ordered range ending before today (completed sessions only)");
        if (pattern != null && !PATTERNS.contains(pattern)) throw new IllegalArgumentException("Unsupported research pattern");
        if (!FILTERS.containsAll(filters)) throw new IllegalArgumentException("Unsupported research filter");
        this.start=start;this.end=end;this.pattern=pattern;this.filters=Set.copyOf(filters);this.research=pattern!=null;
    }
    public void reject(String reason) { rejected.merge(reason,1L,Long::sum); }
    public List<OHLCV> bars(PolygonClient client, String ticker, String timeframe, int warmupDays) {
        LocalDate from=start.minusDays(warmupDays);
        String key=ticker+"/"+timeframe+"/"+from+"/"+end;
        return snapshots.computeIfAbsent(key,k -> {
            List<OHLCV> bars=client.getHistoricalBars(ticker,timeframe,from,end);
            coverage.put(k,Map.of("bars",bars.size(),"first_ts",bars.get(0).getTimestamp(),
                    "last_ts",bars.get(bars.size()-1).getTimestamp(),"pagination_complete",true));
            return List.copyOf(bars);
        });
    }
    /** Compare required intraday slots to a liquid benchmark, with no silent fallback. */
    public static void requireSlots(String ticker, List<OHLCV> bars, List<OHLCV> benchmark, LocalDate start, LocalDate end) {
        Set<Long> available=new HashSet<>();
        for (OHLCV b:bars) available.add(b.getTimestamp());
        int expected=0,missing=0;
        for (OHLCV b:benchmark) {
            ZonedDateTime t=Instant.ofEpochMilli(b.getTimestamp()).atZone(ZoneId.of("America/New_York"));
            if (t.toLocalDate().isBefore(start)||t.toLocalDate().isAfter(end)||t.toLocalTime().isBefore(LocalTime.of(9,30))||!t.toLocalTime().isBefore(LocalTime.of(16,0))) continue;
            expected++;
            if (!available.contains(b.getTimestamp())) missing++;
        }
        if (expected==0 || missing>0) throw new HistoricalDataException(ticker+": incomplete regular-session coverage ("+missing+" missing of "+expected+" benchmark slots)");
    }

    public static void requireDailySessions(List<OHLCV> dailyBars, List<OHLCV> benchmark,
                                            LocalDate start, LocalDate end) {
        ZoneId et = ZoneId.of("America/New_York");
        Set<LocalDate> dailyDates = new HashSet<>();
        for (OHLCV bar : dailyBars) {
            dailyDates.add(Instant.ofEpochMilli(bar.getTimestamp()).atZone(et).toLocalDate());
        }
        Set<LocalDate> tradingDates = new HashSet<>();
        for (OHLCV bar : benchmark) {
            ZonedDateTime time = Instant.ofEpochMilli(bar.getTimestamp()).atZone(et);
            if (!time.toLocalDate().isBefore(start) && !time.toLocalDate().isAfter(end)
                    && !time.toLocalTime().isBefore(LocalTime.of(9, 30))
                    && time.toLocalTime().isBefore(LocalTime.of(16, 0))) {
                tradingDates.add(time.toLocalDate());
            }
        }
        tradingDates.removeAll(dailyDates);
        if (!tradingDates.isEmpty()) {
            throw new HistoricalDataException("Daily history is missing " + tradingDates.size()
                    + " trading sessions; first missing session " + Collections.min(tradingDates));
        }
    }

    public static void requireMinuteExpansion(List<OHLCV> fiveMinuteBars, List<OHLCV> oneMinuteBars,
                                              LocalDate start, LocalDate end) {
        ZoneId et = ZoneId.of("America/New_York");
        Set<Long> minutes = new HashSet<>();
        for (OHLCV bar : oneMinuteBars) minutes.add(bar.getTimestamp());
        int missing = 0;
        for (OHLCV bar : fiveMinuteBars) {
            ZonedDateTime time = Instant.ofEpochMilli(bar.getTimestamp()).atZone(et);
            if (time.toLocalDate().isBefore(start) || time.toLocalDate().isAfter(end)
                    || time.toLocalTime().isBefore(LocalTime.of(9, 30))
                    || !time.toLocalTime().isBefore(LocalTime.of(16, 0))) continue;
            for (int minute = 0; minute < 5; minute++) {
                if (!minutes.contains(bar.getTimestamp() + minute * 60_000L)) missing++;
            }
        }
        if (missing > 0) {
            throw new HistoricalDataException("One-minute history is incomplete (" + missing
                    + " benchmark minute bars missing)");
        }
    }
}
