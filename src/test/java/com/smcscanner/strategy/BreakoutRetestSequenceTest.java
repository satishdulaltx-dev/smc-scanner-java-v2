package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BreakoutRetestSequenceTest {
    private static OHLCV b(double o,double h,double l,double c) {
        return OHLCV.builder().open(o).high(h).low(l).close(c).volume(100).build();
    }
    private static List<OHLCV> clean() {
        return new ArrayList<>(List.of(b(99.5,100,99,99.8),b(99.8,101,99.8,100.8),
                b(100.8,100.85,99.95,100.3),b(100.3,101.2,100.2,101)));
    }
    private static BreakoutRetestSequence.Confirmation detect(List<OHLCV> bars) {
        return BreakoutRetestSequence.atLastBar(bars,100,true,.2,.2,6);
    }
    @Test void confirmsOnlyAfterCrossThenTouchThenLaterCandle() {
        var result=detect(clean());assertNotNull(result);
        assertEquals(1,result.breakoutIndex());assertEquals(2,result.retestIndex());
        assertEquals(99.95,result.invalidationExtreme());
        assertNull(detect(clean().subList(0,3)));
    }
    @Test void anEarlierFailedBreakoutDoesNotAuthorizeLaterRebound() {
        var bars=clean();bars.set(2,b(100.8,100.85,99.2,99.5));
        assertNull(detect(bars)); // last candle is at most a new breakout, not its retest
    }
    @Test void newBreakoutCanQualifyAfterTheOldOneFailed() {
        var bars=clean();bars.set(2,b(100.8,100.85,99.2,99.5));
        bars.add(b(101,101.1,100,100.4));bars.add(b(100.4,101.4,100.3,101.3));
        var result=detect(bars);assertNotNull(result);assertEquals(3,result.breakoutIndex());
    }
    @Test void repeatedClosesCannotReuseAConsumedConfirmation() {
        var bars=clean();bars.add(b(101,101.5,100.8,101.3));assertNull(detect(bars));
    }
    @Test void oldBreakoutExpiresBeforeALateRetest() {
        var bars=new ArrayList<>(clean().subList(0,2));
        for(int i=0;i<7;i++)bars.add(b(101,101.3,100.7,101));
        bars.add(b(101,101.1,99.95,100.3));bars.add(b(100.3,101.3,100.2,101.2));
        assertNull(detect(bars));
    }
    @Test void gapAlreadyAboveTheLevelIsNotAnIntradayCrossing() {
        var bars=clean();bars.set(0,b(100.5,101,100.4,100.6));assertNull(detect(bars));
    }
    @Test void bearishSequenceIsSymmetricAndKeepsRetestHigh() {
        var bars=clean().stream().map(x->b(200-x.getOpen(),200-x.getLow(),200-x.getHigh(),200-x.getClose())).toList();
        var result=BreakoutRetestSequence.atLastBar(bars,100,false,.2,.2,6);
        assertNotNull(result);assertEquals(100.05,result.invalidationExtreme(),1e-8);
    }
    @Test void aFreshCrossingAtExpiryStartsANewSequence() {
        var bars=new ArrayList<>(clean().subList(0,2));
        for(int i=0;i<5;i++)bars.add(b(100.1,100.15,99.95,100));
        bars.add(b(100,101,100,100.8)); // old break expired, this is a fresh crossing
        bars.add(b(100.8,100.85,99.95,100.3));bars.add(b(100.3,101.2,100.2,101));
        var result=BreakoutRetestSequence.atLastBar(bars,100,true,.2,.2,5);
        assertNotNull(result);assertEquals(7,result.breakoutIndex());
    }
}
