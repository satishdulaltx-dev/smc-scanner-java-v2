package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyLevelIntegrityTest {
    static final ZoneId ET=ZoneId.of("America/New_York");
    static OHLCV b(long t,double o,double h,double l,double c,double v) {
        return OHLCV.builder().timestamp(t).open(o).high(h).low(l).close(c).volume(v).build();
    }
    static List<OHLCV> daily() {
        var rows=new ArrayList<OHLCV>();
        for(int i=0;i<12;i++) rows.add(b(LocalDate.of(2026,5,1).plusDays(i).atStartOfDay(ET).toInstant().toEpochMilli(),104,108,100,105,1000));
        return rows;
    }
    static List<OHLCV> session() {
        long open=LocalDate.of(2026,6,1).atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        return new ArrayList<>(List.of(b(open,104,105,103,104,100),b(open+300000,103,104,102,103,100),
                b(open+600000,102,103,101,102,100),b(open+900000,100.2,100.7,99.5,100.5,300),
                b(open+1200000,100.5,101.2,100.4,101.0,100)));
    }
    @SuppressWarnings("unchecked")
    static List<double[]> levels(List<OHLCV> bars) throws Exception {
        var m=KeyLevelStrategyDetector.class.getDeclaredMethod("findKeyLevels",List.class,double.class);
        m.setAccessible(true);return (List<double[]>)m.invoke(new KeyLevelStrategyDetector(),bars,101.0);
    }
    @Test void validRejectionKeepsTheWickAndTargetsAnObservedOpposingLevel() {
        var result=new KeyLevelStrategyDetector().detect(session(),daily(),"TEST",2,null,true);
        assertEquals(1,result.size());var s=result.get(0);
        assertEquals("long",s.getDirection());assertTrue(s.getStopLoss()<99.5);
        assertEquals(108,s.getTakeProfit());
    }
    @Test void futureAndCurrentDailyPricesCannotChangeDetection() {
        var detector=new KeyLevelStrategyDetector();var daily=daily();
        var before=detector.detect(session(),daily,"TEST",2,null,true).get(0);
        daily.add(b(LocalDate.of(2026,6,1).atStartOfDay(ET).toInstant().toEpochMilli(),105,999,1,2,99999));
        daily.add(b(LocalDate.of(2026,6,2).atStartOfDay(ET).toInstant().toEpochMilli(),105,999,1,2,99999));
        var after=detector.detect(session(),daily,"TEST",2,null,true).get(0);
        assertEquals(before.getStopLoss(),after.getStopLoss());assertEquals(before.getTakeProfit(),after.getTakeProfit());
        assertEquals(before.getConfidence(),after.getConfidence());
    }
    @Test void newestCompletedDaySuppliesTheSecondTouch() throws Exception {
        var rows=daily();for(int i=0;i<10;i++) {
            var old=rows.get(i);rows.set(i,b(old.getTimestamp(),104,110,95,105,1000));
        }
        assertTrue(levels(rows).stream().anyMatch(x->x[2]<0 && Math.abs(x[0]-100)<.01 && x[1]==2));
    }
    @Test void brokenSupportMustBuildTwoNewDefendingSessions() throws Exception {
        var rows=daily();long t=rows.get(rows.size()-1).getTimestamp();
        rows.add(b(t+86400000,101,102,98,99,1000));
        assertFalse(levels(rows).stream().anyMatch(x->x[2]<0 && Math.abs(x[0]-100)<.01));
        rows.add(b(t+2*86400000,104,108,100,105,1000));
        assertFalse(levels(rows).stream().anyMatch(x->x[2]<0 && Math.abs(x[0]-100)<.01));
        rows.add(b(t+3*86400000,104,108,100,105,1000));
        assertTrue(levels(rows).stream().anyMatch(x->x[2]<0 && Math.abs(x[0]-100)<.01 && x[1]==2));
    }
    @Test void duplicateBarsOnOneDayDoNotCreateMultipleTouches() throws Exception {
        var row=daily().get(0);assertTrue(levels(Collections.nCopies(12,row)).isEmpty());
    }
    @Test void wrongApproachAndWeakFollowThroughAreRejected() {
        var bars=session();var old=bars.get(2);bars.set(2,b(old.getTimestamp(),99,99.5,98,99,100));
        assertTrue(new KeyLevelStrategyDetector().detect(bars,daily(),"TEST",2,null,true).isEmpty());
        bars=session();old=bars.get(4);bars.set(4,b(old.getTimestamp(),100.5,101.2,100.4,100.6,100));
        assertTrue(new KeyLevelStrategyDetector().detect(bars,daily(),"TEST",2,null,true).isEmpty());
    }
    @Test void anEarlierIntradayBreakCannotBeRelabeledAsIntactSupport() {
        var bars=session();var old=bars.get(0);bars.set(0,b(old.getTimestamp(),99,99.5,98,99,100));
        assertTrue(new KeyLevelStrategyDetector().detect(bars,daily(),"TEST",2,null,true).isEmpty());
    }
    @Test void resistanceRejectionMirrorsSupportAndKeepsTheWick() {
        var bars=session().stream().map(x->b(x.getTimestamp(),208-x.getOpen(),208-x.getLow(),
                208-x.getHigh(),208-x.getClose(),x.getVolume())).toList();
        var history=daily().stream().map(x->b(x.getTimestamp(),208-x.getOpen(),208-x.getLow(),
                208-x.getHigh(),208-x.getClose(),x.getVolume())).toList();
        var result=new KeyLevelStrategyDetector().detect(bars,history,"TEST",2,null,true);
        assertEquals(1,result.size());assertEquals("short",result.get(0).getDirection());
        assertTrue(result.get(0).getStopLoss()>108.5);assertEquals(100,result.get(0).getTakeProfit());
    }
}
