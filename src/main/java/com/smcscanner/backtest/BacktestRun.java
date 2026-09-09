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
    public final List<Map<String,Object>> candidates = new ArrayList<>();
    public final String pattern;
    public final Set<String> filters;
    public final int maxHoldMinutes;
    public final boolean research;
    public static final Set<String> PATTERNS = Set.of(
            "scalp", "scalp-early", "scalp-core", "scalp-rvol", "scalp-tod-rvol", "scalp-structure", "scalp-spy", "scalp-chase",
            "vwap", "vwap-cont-long", "vwap-cont-short",
            "vwap-reversion-long", "vwap-reversion-short",
            "breakout", "keylevel", "vsqueeze", "or-vwap", "idiv",
            "sweep-flip", "choch-primary", "pdh-pdl");
    public static final Set<String> FILTERS = Set.of("spy", "15m", "volume", "regime", "time", "cost");
    public static final Set<Integer> HOLD_MINUTES = Set.of(15, 30, 60, 120, 390);
    /** 5 BPS adverse entry fill plus 5 BPS adverse exit fill in controlled research. */
    public static final double RESEARCH_ROUND_TRIP_COST_BPS = 10.0;

    public BacktestRun(LocalDate start, LocalDate end) { this(start,end,null,Set.of(),390); }
    public BacktestRun(LocalDate start, LocalDate end, String pattern, Set<String> filters) {
        this(start,end,pattern,filters,390);
    }
    public BacktestRun(LocalDate start, LocalDate end, String pattern, Set<String> filters, int maxHoldMinutes) {
        if (start == null || end == null || start.isAfter(end) || !end.isBefore(LocalDate.now(ZoneId.of("America/New_York"))))
            throw new IllegalArgumentException("Use an ordered range ending before today (completed sessions only)");
        if (pattern != null && !PATTERNS.contains(pattern)) throw new IllegalArgumentException("Unsupported research pattern");
        if (!FILTERS.containsAll(filters)) throw new IllegalArgumentException("Unsupported research filter");
        if (pattern != null && !HOLD_MINUTES.contains(maxHoldMinutes))
            throw new IllegalArgumentException("Research hold must be 15, 30, 60, 120, or 390 minutes");
        this.start=start;this.end=end;this.pattern=pattern;this.filters=Set.copyOf(filters);
        this.maxHoldMinutes=maxHoldMinutes;this.research=pattern!=null;
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
    /** Load nonessential context without converting its absence into fake market data. */
    public List<OHLCV> optionalBars(PolygonClient client, String ticker, String timeframe,
                                    int warmupDays, String omittedBehavior) {
        try {
            return bars(client, ticker, timeframe, warmupDays);
        } catch (HistoricalDataException e) {
            String key = ticker + "/" + timeframe + "/" + start.minusDays(warmupDays) + "/" + end;
            coverage.put(key, Map.of("available", false, "error", e.getMessage()));
            String warning = ticker + " " + timeframe + " unavailable; " + omittedBehavior
                    + " (" + e.getMessage() + ")";
            if (!warnings.contains(warning)) warnings.add(warning);
            return List.of();
        }
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

    /** Compatibility entry point: reject every unresolved benchmark gap. */
    public void requireSlotsAllowSparse(String label, List<OHLCV> bars, List<OHLCV> benchmark) {
        requireSlots(label, bars, benchmark, start, end);
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

    /**
     * Polygon can omit isolated one-minute aggregates when no trade is reported while
     * still returning the containing five-minute aggregate. Represent those isolated
     * slots as flat, zero-volume bars. Reject complete parent gaps and material outages.
     */
    public List<OHLCV> normalizeSparseMinuteBars(String label,List<OHLCV> fiveMinuteBars,
                                                  List<OHLCV> oneMinuteBars) {
        ZoneId et=ZoneId.of("America/New_York");
        TreeMap<Long,OHLCV> normalized=new TreeMap<>();
        for (OHLCV bar:oneMinuteBars) normalized.put(bar.getTimestamp(),bar);
        int expected=0,missing=0,emptyParents=0;
        for (OHLCV parent:fiveMinuteBars) {
            ZonedDateTime time=Instant.ofEpochMilli(parent.getTimestamp()).atZone(et);
            if (time.toLocalDate().isBefore(start)||time.toLocalDate().isAfter(end)
                    ||time.toLocalTime().isBefore(LocalTime.of(9,30))
                    ||!time.toLocalTime().isBefore(LocalTime.of(16,0))) continue;
            int present=0;
            for (int minute=0;minute<5;minute++) if (normalized.containsKey(parent.getTimestamp()+minute*60_000L)) present++;
            if (present==0) {emptyParents++;continue;}
            double carry=parent.getOpen();
            for (int minute=0;minute<5;minute++) {
                long timestamp=parent.getTimestamp()+minute*60_000L;
                expected++;
                OHLCV existing=normalized.get(timestamp);
                if (existing!=null) {carry=existing.getClose();continue;}
                missing++;
                normalized.put(timestamp,OHLCV.builder().timestamp(timestamp).open(carry).high(carry)
                        .low(carry).close(carry).volume(0).build());
            }
        }
        int allowed=Math.max(20,(int)Math.ceil(expected*0.001));
        if (emptyParents>0||missing>allowed) {
            throw new HistoricalDataException(label+": material one-minute outage ("+missing
                    +" isolated slots, "+emptyParents+" complete five-minute blocks missing)");
        }
        if (missing>0) {
            warnings.add(label+": filled "+missing+" isolated zero-trade one-minute slots from complete five-minute parents");
            coverage.put(label+"/minute_normalization",Map.of("expected",expected,"filled_zero_trade_slots",missing,
                    "complete_parent_gaps",emptyParents));
        }
        return List.copyOf(normalized.values());
    }
}
