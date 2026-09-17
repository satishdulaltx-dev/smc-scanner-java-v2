package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PdhPdlIntegrityTest {
    static final ZoneId ET=ZoneId.of("America/New_York");
    static OHLCV b(long t,double o,double h,double l,double c) {
        return OHLCV.builder().timestamp(t).open(o).high(h).low(l).close(c).volume(100).build();
    }
    static OHLCV bv(long t,double o,double h,double l,double c,double v) {
        return OHLCV.builder().timestamp(t).open(o).high(h).low(l).close(c).volume(v).build();
    }
    static List<OHLCV> bars() {
        long prev=LocalDate.of(2026,5,29).atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        long now=LocalDate.of(2026,6,1).atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        var rows=new ArrayList<OHLCV>();
        for(int i=0;i<78;i++)rows.add(b(prev+i*300000,95,100,90,95));
        rows.add(b(now,99.5,100,99,99.8));rows.add(b(now+300000,99.8,101,99.8,100.8));
        rows.add(b(now+600000,100.8,100.85,99.95,100.3));rows.add(b(now+900000,100.3,101.2,100.2,101));
        return rows;
    }
    @Test void fullPriorSessionAndCleanSequenceProduceOneConfirmedRetest() {
        var result=new PdhPdlDetector().detect(bars(),"TEST",2,true);
        assertEquals(1,result.size());var s=result.get(0);
        assertTrue(s.getFactorBreakdown().startsWith("pdhpdl-breakout-retest-long"));
        assertTrue(s.getStopLoss()<99.95);
    }
    @Test void truncatedPriorSessionAndBrokenSequenceCannotProduceRetest() {
        var rows=bars();rows.remove(0);assertTrue(new PdhPdlDetector().detect(rows,"TEST",2,true).isEmpty());
        rows=bars();long t=rows.get(80).getTimestamp();rows.set(80,b(t,100.8,100.85,99.2,99.5));
        assertTrue(new PdhPdlDetector().detect(rows,"TEST",2,true).stream()
                .noneMatch(s->s.getFactorBreakdown().contains("breakout-retest")));
    }
    @Test void anotherCloseDoesNotRepeatTheSameRetestSignal() {
        var rows=bars();rows.add(b(rows.get(rows.size()-1).getTimestamp()+300000,101,101.5,100.8,101.3));
        assertTrue(new PdhPdlDetector().detect(rows,"TEST",2,true).stream()
                .noneMatch(s->s.getFactorBreakdown().contains("breakout-retest")));
    }
    @Test void isolatedRetestCannotSilentlyReturnARejection() {
        var detector=new PdhPdlDetector();assertEquals(1,detector.detectRetests(bars(),"TEST",2,true).size());
        var rows=new ArrayList<>(bars().subList(0,78));long t=bars().get(78).getTimestamp();
        for(int i=0;i<4;i++)rows.add(b(t+i*300000,98,98.5,97.5,98));
        rows.add(b(t+1200000,100.1,100.2,99.7,99.8));
        assertFalse(detector.detect(rows,"TEST",2,true).isEmpty());
        assertTrue(detector.detectRetests(rows,"TEST",2,true).isEmpty());
    }
    @Test void fillCannotMoveTheTargetPastKnownResistance() {
        var rows=bars();var setup=new PdhPdlDetector().detectRetests(rows,"TEST",2,true).get(0);
        long open=rows.get(78).getTimestamp();
        // An earlier confirmed high remains above the entry but below the requested target.
        rows.set(79,b(open+300000,99.8,101.5,99.8,100.8));
        assertTrue(PdhPdlDetector.targetHasRoom(rows,setup,101.0,101.4));
        assertFalse(PdhPdlDetector.targetHasRoom(rows,setup,101.0,102.0));
    }

    @Test void qualifiedRetestEmitsTheStructureBeforeOptionalActivityFilters() {
        var rows=qualifiedBars(20_000);
        var result=new PdhPdlDetector().detectQualifiedRetest(rows,"TEST",2);
        assertEquals(1,result.size());
        var setup=result.get(0);
        assertEquals("long",setup.getDirection());
        assertTrue(setup.getStopLoss()<100.95);
        assertTrue(setup.getFactorBreakdown().contains("level_type=PDH"));
        assertTrue(setup.getFactorBreakdown().contains("opening_rvol=1.000"));
        assertTrue(setup.getFactorBreakdown().contains("vwap_aligned=1"));
    }

    @Test void qualifiedRetestRejectsIncompleteReferenceHistory() {
        var rows=qualifiedBars(20_000);
        rows.remove(13*78);
        assertTrue(new PdhPdlDetector().detectQualifiedRetest(rows,"TEST",2).isEmpty());
    }

    private static List<OHLCV> qualifiedBars(double currentVolume) {
        var rows=new ArrayList<OHLCV>();
        LocalDate first=LocalDate.of(2026,5,13);
        for (int d=0;d<14;d++) {
            LocalDate day=first.plusDays(d);
            long open=day.atTime(9,30).atZone(ET).toInstant().toEpochMilli();
            for (int i=0;i<78;i++) rows.add(bv(open+i*300_000L,100,101,99,100,20_000));
        }
        LocalDate today=first.plusDays(14);
        long pre=today.atTime(8,0).atZone(ET).toInstant().toEpochMilli();
        rows.add(bv(pre,100,100.7,99.6,100.2,5_000));
        long open=today.atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        rows.add(bv(open,100.2,100.6,99.9,100.3,currentVolume));
        rows.add(bv(open+300_000L,100.3,101.5,100.2,101.3,currentVolume));
        rows.add(bv(open+600_000L,101.25,101.35,100.95,101.1,currentVolume));
        rows.add(bv(open+900_000L,101.1,101.6,101.05,101.45,currentVolume));
        return rows;
    }
}
