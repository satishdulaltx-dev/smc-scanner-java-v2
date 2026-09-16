package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import java.time.*;
import java.util.List;

/** Price-structure invariants shared by live scalp detection and historical replay. */
public final class ScalpSetupRules {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    public static final long BAR_MS = 300_000L;
    private ScalpSetupRules() {}

    /** Same regular session, observed no later than the decision's completed bar. */
    public static List<OHLCV> sessionAsOf(List<OHLCV> bars, long decisionMs) {
        if (bars == null) return List.of();
        LocalDate day = Instant.ofEpochMilli(decisionMs).atZone(ET).toLocalDate();
        return bars.stream().filter(b -> {
            ZonedDateTime t = Instant.ofEpochMilli(b.getTimestamp()).atZone(ET);
            return t.toLocalDate().equals(day) && !t.toLocalTime().isBefore(LocalTime.of(9,30))
                    && t.toLocalTime().isBefore(LocalTime.of(16,0))
                    && b.getTimestamp() + BAR_MS <= decisionMs;
        }).toList();
    }

    public static boolean contiguousFromOpen(List<OHLCV> bars, long decisionMs) {
        if (bars.isEmpty()) return false;
        long expected = Instant.ofEpochMilli(decisionMs).atZone(ET).toLocalDate()
                .atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        for (OHLCV b : bars) {
            if (b.getTimestamp() != expected) return false;
            expected += BAR_MS;
        }
        return expected == decisionMs;
    }

    public record Rejection(double level, double extreme, int touchIndex) {}

    /** Freeze each level before its touch bar; require approach, contact and reclaim in order. */
    public static Rejection rejection(List<OHLCV> bars, double currentLevel, double previousLevel,
                                       double tolerance, boolean bullish) {
        int n = bars.size();
        if (n < 3 || tolerance < 0) return null;
        OHLCV last=bars.get(n-1), prev=bars.get(n-2), before=bars.get(n-3);
        if (bullish) {
            if (prev.getClose() >= currentLevel && last.getLow() <= currentLevel+tolerance
                    && last.getHigh() >= currentLevel && last.getClose() > currentLevel)
                return new Rejection(currentLevel, Math.min(currentLevel,last.getLow()), n-1);
            if (before.getClose() >= previousLevel && prev.getLow() <= previousLevel+tolerance
                    && prev.getHigh() >= previousLevel && prev.getClose() >= previousLevel-tolerance
                    && last.getClose() > Math.max(previousLevel,prev.getHigh()))
                return new Rejection(previousLevel, Math.min(previousLevel,Math.min(prev.getLow(),last.getLow())), n-2);
        } else {
            if (prev.getClose() <= currentLevel && last.getHigh() >= currentLevel-tolerance
                    && last.getLow() <= currentLevel && last.getClose() < currentLevel)
                return new Rejection(currentLevel, Math.max(currentLevel,last.getHigh()), n-1);
            if (before.getClose() <= previousLevel && prev.getHigh() >= previousLevel-tolerance
                    && prev.getLow() <= previousLevel && prev.getClose() <= previousLevel+tolerance
                    && last.getClose() < Math.min(previousLevel,prev.getLow()))
                return new Rejection(previousLevel, Math.max(previousLevel,Math.max(prev.getHigh(),last.getHigh())), n-2);
        }
        return null;
    }

    /** Preserve the actual next band and cap at an intervening confirmed, unbroken pivot. */
    public static double target(List<OHLCV> bars, double entry, double band, boolean bullish) {
        double target=band;
        for (int i=1;i<bars.size()-1;i++) {
            double level=bullish?bars.get(i).getHigh():bars.get(i).getLow();
            boolean pivot=bullish
                    ? level>bars.get(i-1).getHigh() && level>=bars.get(i+1).getHigh()
                    : level<bars.get(i-1).getLow() && level<=bars.get(i+1).getLow();
            if (!pivot || (bullish?level<=entry:level>=entry)) continue;
            boolean broken=false;
            for (int j=i+1;j<bars.size();j++) {
                if (bullish?bars.get(j).getHigh()>level:bars.get(j).getLow()<level) {broken=true;break;}
            }
            if (!broken) target=bullish?Math.min(target,level):Math.max(target,level);
        }
        return target;
    }

    public static boolean validRoom(double entry, double stop, double target, boolean bullish, double minR) {
        double sign=bullish?1:-1, risk=sign*(entry-stop), reward=sign*(target-entry);
        return Double.isFinite(risk) && Double.isFinite(reward) && risk>0 && reward>=risk*minR;
    }
}
