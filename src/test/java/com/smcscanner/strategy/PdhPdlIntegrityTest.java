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
}
