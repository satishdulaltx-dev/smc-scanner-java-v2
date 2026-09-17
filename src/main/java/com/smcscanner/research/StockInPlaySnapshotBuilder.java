package com.smcscanner.research;

import com.smcscanner.model.OHLCV;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds one no-look-ahead universe row from bars completed at {@code asOfEpochMs}. */
public final class StockInPlaySnapshotBuilder {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final long FIVE_MINUTES_MS = 5L * 60_000L;

    private StockInPlaySnapshotBuilder() {}

    public static StockInPlayRanker.Snapshot build(String ticker, long asOfEpochMs,
                                                    List<OHLCV> fiveMinuteBars,
                                                    List<OHLCV> dailyBars,
                                                    boolean catalystKnownAtTime) {
        if (fiveMinuteBars == null || dailyBars == null)
            return incomplete(ticker, asOfEpochMs, catalystKnownAtTime);
        LocalDate date = Instant.ofEpochMilli(asOfEpochMs).atZone(ET).toLocalDate();
        Map<LocalDate,List<OHLCV>> sessions = regularSessions(fiveMinuteBars, asOfEpochMs);
        List<OHLCV> today = sessions.getOrDefault(date, List.of());
        if (!completeThroughDecision(today, date, asOfEpochMs) || today.get(today.size()-1).getClose() < 5)
            return incomplete(ticker, asOfEpochMs, catalystKnownAtTime);

        List<LocalDate> earlierDates = sessions.keySet().stream().filter(d -> d.isBefore(date)).sorted().toList();
        if (earlierDates.isEmpty()) return incomplete(ticker, asOfEpochMs, catalystKnownAtTime);
        List<OHLCV> previous = sessions.get(earlierDates.get(earlierDates.size()-1));
        if (previous.size() != 78) return incomplete(ticker, asOfEpochMs, catalystKnownAtTime);

        List<List<OHLCV>> priorComplete = new ArrayList<>();
        int from = Math.max(0, earlierDates.size()-14);
        for (int i=from;i<earlierDates.size();i++) {
            List<OHLCV> session = sessions.get(earlierDates.get(i));
            if (session.size()==78) priorComplete.add(session);
        }
        if (priorComplete.size()<10) return incomplete(ticker, asOfEpochMs, catalystKnownAtTime);
        double averageDailyVolume=priorComplete.stream().mapToDouble(session ->
                session.stream().mapToDouble(OHLCV::getVolume).sum()).average().orElse(0);
        if (averageDailyVolume<1_000_000) return incomplete(ticker, asOfEpochMs, catalystKnownAtTime);

        long cutoff=date.atStartOfDay(ET).toInstant().toEpochMilli();
        List<OHLCV> completedDaily=dailyBars.stream().filter(bar->bar.getTimestamp()<cutoff)
                .sorted(Comparator.comparingLong(OHLCV::getTimestamp)).toList();
        double dailyAtr=lastAtr(completedDaily,14);
        if (!Double.isFinite(dailyAtr)||dailyAtr<=0)
            return incomplete(ticker,asOfEpochMs,catalystKnownAtTime);

        int elapsed=today.size();
        double expected=priorComplete.stream().mapToDouble(session->session.subList(0,elapsed).stream()
                .mapToDouble(OHLCV::getVolume).sum()).average().orElse(0);
        double observed=today.stream().mapToDouble(OHLCV::getVolume).sum();
        if (expected<=0) return incomplete(ticker,asOfEpochMs,catalystKnownAtTime);
        double previousClose=previous.get(previous.size()-1).getClose();
        double gapAtr=Math.abs(today.get(0).getOpen()-previousClose)/dailyAtr;
        double openingRangeAtr=(today.stream().mapToDouble(OHLCV::getHigh).max().orElse(0)
                -today.stream().mapToDouble(OHLCV::getLow).min().orElse(0))/dailyAtr;
        double dollarVolume=today.stream().mapToDouble(bar->
                ((bar.getHigh()+bar.getLow()+bar.getClose())/3.0)*bar.getVolume()).sum();
        return new StockInPlayRanker.Snapshot(ticker,asOfEpochMs,gapAtr,observed/expected,
                openingRangeAtr,dollarVolume,catalystKnownAtTime,true);
    }

    private static Map<LocalDate,List<OHLCV>> regularSessions(List<OHLCV> bars,long asOfEpochMs) {
        Map<LocalDate,List<OHLCV>> byDate=new TreeMap<>();
        for (OHLCV bar:bars) {
            if (bar.getTimestamp()+FIVE_MINUTES_MS>asOfEpochMs) continue;
            var time=Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (time.toLocalTime().isBefore(LocalTime.of(9,30))
                    || !time.toLocalTime().isBefore(LocalTime.of(16,0))) continue;
            byDate.computeIfAbsent(time.toLocalDate(),ignored->new ArrayList<>()).add(bar);
        }
        byDate.values().forEach(rows->rows.sort(Comparator.comparingLong(OHLCV::getTimestamp)));
        return byDate;
    }

    private static boolean completeThroughDecision(List<OHLCV> bars,LocalDate date,long asOfEpochMs) {
        if (bars.isEmpty() || bars.get(0).getTimestamp()!=date.atTime(9,30).atZone(ET).toInstant().toEpochMilli())
            return false;
        // A one-minute signal can arrive between five-minute boundaries. Rank it from
        // the latest five-minute bar that was already complete, never from the forming bar.
        long latestCompletion=bars.get(bars.size()-1).getTimestamp()+FIVE_MINUTES_MS;
        if (latestCompletion>asOfEpochMs || asOfEpochMs-latestCompletion>=FIVE_MINUTES_MS) return false;
        for (int i=1;i<bars.size();i++)
            if (bars.get(i).getTimestamp()-bars.get(i-1).getTimestamp()!=FIVE_MINUTES_MS) return false;
        return true;
    }

    private static double lastAtr(List<OHLCV> bars,int period) {
        if (bars.size()<period) return 0;
        double atr=0;
        for (int i=0;i<period;i++) atr+=trueRange(bars,i);
        atr/=period;
        for (int i=period;i<bars.size();i++) atr=(atr*(period-1)+trueRange(bars,i))/period;
        return atr;
    }

    private static double trueRange(List<OHLCV> bars,int index) {
        OHLCV current=bars.get(index);
        if (index==0) return current.getHigh()-current.getLow();
        double previousClose=bars.get(index-1).getClose();
        return Math.max(current.getHigh()-current.getLow(),Math.max(
                Math.abs(current.getHigh()-previousClose),Math.abs(current.getLow()-previousClose)));
    }

    private static StockInPlayRanker.Snapshot incomplete(String ticker,long asOf,boolean catalyst) {
        return new StockInPlayRanker.Snapshot(ticker,asOf,0,0,0,0,catalyst,false);
    }
}
