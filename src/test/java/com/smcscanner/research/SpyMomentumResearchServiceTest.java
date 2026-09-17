package com.smcscanner.research;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpyMomentumResearchServiceTest {
    private static final ZoneId ET=ZoneId.of("America/New_York");

    @Test
    void entersAtTheNextMinuteAfterTheFirstHalfHourDecision() {
        LocalDate date=LocalDate.of(2026,7,6);
        List<List<OHLCV>> prior=new ArrayList<>();
        for(int i=1;i<=14;i++)prior.add(session(date.minusDays(i),index->100.10));
        List<OHLCV> trend=session(date,index->100+index*.01);

        var trades=new SpyMomentumResearchService(null).replayDay(date,trend,prior,100,new ArrayList<>());

        assertEquals(1,trades.size());
        assertEquals("long",trades.get(0).direction());
        assertEquals(trend.get(30).getTimestamp(),trades.get(0).entryEpochMs());
        assertEquals(trend.get(389).getTimestamp()+60_000,trades.get(0).exitEpochMs());
        assertTrue(trades.get(0).pnlPct()>0);
    }

    @Test
    void ignoresAnIntraperiodSpikeThatHasFadedByTheDecisionTime() {
        LocalDate date=LocalDate.of(2026,7,6);
        List<List<OHLCV>> prior=new ArrayList<>();
        for(int i=1;i<=14;i++)prior.add(session(date.minusDays(i),index->100.10));
        List<OHLCV> faded=session(date,index->index==10?102:100);

        var trades=new SpyMomentumResearchService(null).replayDay(date,faded,prior,100,new ArrayList<Map<String,Object>>());

        assertTrue(trades.isEmpty());
    }

    private static List<OHLCV> session(LocalDate date,Price price) {
        long start=date.atTime(9,30).atZone(ET).toInstant().toEpochMilli();
        List<OHLCV> bars=new ArrayList<>();
        for(int i=0;i<390;i++){
            double close=price.at(i);
            bars.add(OHLCV.builder().timestamp(start+i*60_000L).open(close).high(close+.02)
                    .low(close-.02).close(close).volume(1_000).build());
        }
        return bars;
    }

    private interface Price { double at(int index); }
}
