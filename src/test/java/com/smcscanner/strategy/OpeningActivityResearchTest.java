package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpeningActivityResearchTest {
    private static final ZoneId ET=ZoneId.of("America/New_York");

    @Test
    void publishedOpeningBreakoutUsesOnlyPriorSessionsForOpeningRvol() {
        var detector=new BreakoutStrategyDetector();
        List<OHLCV> bars=history(300_000);

        var setups=detector.detectOpeningActivityResearch(bars,"TEST",5.0,1.5);

        assertEquals(1,setups.size());
        var setup=setups.get(0);
        assertEquals("long",setup.getDirection());
        assertEquals(100.7,setup.getStopLoss(),0.0001);
        assertTrue(setup.getFactorBreakdown().contains("opening_rvol=1.500"));
        assertTrue(setup.getFactorBreakdown().contains("prior14_avg_daily_volume=1200000"));
    }

    @Test
    void activityGateAndEligibilityAreIndependentFromTheBreakout() {
        var detector=new BreakoutStrategyDetector();
        List<OHLCV> ordinaryOpening=history(200_000);

        assertEquals(1,detector.detectOpeningActivityResearch(ordinaryOpening,"TEST",5.0,0).size());
        assertTrue(detector.detectOpeningActivityResearch(ordinaryOpening,"TEST",5.0,1.5).isEmpty());
        assertTrue(detector.detectOpeningActivityResearch(history(300_000),"TEST",0.50,0).isEmpty());
    }

    @Test
    void requiresFourteenCompletedSessionsAndOnlyFirstFreshBreak() {
        var detector=new BreakoutStrategyDetector();
        List<OHLCV> full=history(300_000);
        assertTrue(detector.detectOpeningActivityResearch(full.subList(6,full.size()),"TEST",5,0).isEmpty());

        List<OHLCV> alreadyBroken=new ArrayList<>(full);
        OHLCV currentSixth=alreadyBroken.get(alreadyBroken.size()-1);
        alreadyBroken.add(bar(LocalDate.of(2026,5,15),LocalTime.of(9,36),101.3,101.5,101.2,101.4,50_000));
        assertTrue(detector.detectOpeningActivityResearch(alreadyBroken,"TEST",5,0).isEmpty());
        assertNotNull(currentSixth);
    }

    private static List<OHLCV> history(double currentOpeningMinuteVolume) {
        List<OHLCV> bars=new ArrayList<>();
        LocalDate start=LocalDate.of(2026,5,1);
        for (int day=0;day<14;day++) {
            LocalDate date=start.plusDays(day);
            for (int minute=0;minute<6;minute++)
                bars.add(bar(date,LocalTime.of(9,30).plusMinutes(minute),100,100.2,99.8,100,200_000));
        }
        LocalDate current=LocalDate.of(2026,5,15);
        for (int minute=0;minute<5;minute++) {
            double close=100.2+minute*.2;
            bars.add(bar(current,LocalTime.of(9,30).plusMinutes(minute),
                    minute==0?100:close-.2,close+.2,99.8,close,currentOpeningMinuteVolume));
        }
        bars.add(bar(current,LocalTime.of(9,35),101.0,101.3,100.9,101.2,50_000));
        return bars;
    }

    private static OHLCV bar(LocalDate date,LocalTime time,double open,double high,double low,double close,double volume) {
        long timestamp=LocalDateTime.of(date,time).atZone(ET).toInstant().toEpochMilli();
        return OHLCV.builder().timestamp(timestamp).open(open).high(high).low(low).close(close).volume(volume).build();
    }
}
