package com.smcscanner.research;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockInPlaySnapshotBuilderTest {
    private static final ZoneId ET=ZoneId.of("America/New_York");

    @Test
    void usesOnlyBarsCompletedAtTheDecisionTime() {
        var history=history(false);
        long asOf=LocalDate.of(2026,7,17).atTime(10,0).atZone(ET).toInstant().toEpochMilli();
        var before=StockInPlaySnapshotBuilder.build("TEST",asOf,history.fiveMinute(),history.daily(),false);
        assertTrue(before.historyComplete());
        assertEquals(2.0,before.openingRvol(),1e-9);
        assertEquals(1.0,before.gapAtr(),1e-9);

        var withFuture=new ArrayList<>(history.fiveMinute());
        withFuture.add(bar(LocalDate.of(2026,7,17),10,0,102,150,50,149,50_000_000));
        var after=StockInPlaySnapshotBuilder.build("TEST",asOf,withFuture,history.daily(),false);
        assertEquals(before.openingRvol(),after.openingRvol(),1e-9);
        assertEquals(before.openingRangeAtr(),after.openingRangeAtr(),1e-9);
        assertEquals(before.cumulativeDollarVolume(),after.cumulativeDollarVolume(),1e-9);
    }

    @Test
    void incompleteCurrentSessionCannotEnterTheRankedUniverse() {
        var history=history(true);
        long asOf=LocalDate.of(2026,7,17).atTime(10,0).atZone(ET).toInstant().toEpochMilli();
        var snapshot=StockInPlaySnapshotBuilder.build("TEST",asOf,history.fiveMinute(),history.daily(),false);
        assertFalse(snapshot.historyComplete());
    }

    private static History history(boolean omitCurrentMiddleBar) {
        List<OHLCV> five=new ArrayList<>();
        LocalDate cursor=LocalDate.of(2026,6,25);
        int sessions=0;
        while(sessions<15) {
            if(cursor.getDayOfWeek()!=DayOfWeek.SATURDAY&&cursor.getDayOfWeek()!=DayOfWeek.SUNDAY) {
                for(int i=0;i<78;i++)five.add(bar(cursor,9,30+i*5,100,101,99,100,20_000));
                sessions++;
            }
            cursor=cursor.plusDays(1);
        }
        LocalDate today=LocalDate.of(2026,7,17);
        for(int i=0;i<6;i++) {
            if(omitCurrentMiddleBar&&i==2)continue;
            five.add(bar(today,9,30+i*5,102,102.5+i*.1,101.5,102,40_000));
        }
        List<OHLCV> daily=new ArrayList<>();
        for(int i=40;i>=1;i--)daily.add(OHLCV.builder()
                .timestamp(today.minusDays(i).atTime(16,0).atZone(ET).toInstant().toEpochMilli())
                .open(100).high(101).low(99).close(100).volume(2_000_000).build());
        return new History(five,daily);
    }

    private static OHLCV bar(LocalDate date,int hour,int minuteFromHour,double open,double high,double low,double close,double volume) {
        int actualHour=hour+minuteFromHour/60;
        int minute=minuteFromHour%60;
        return OHLCV.builder().timestamp(date.atTime(actualHour,minute).atZone(ET).toInstant().toEpochMilli())
                .open(open).high(high).low(low).close(close).volume(volume).build();
    }

    private record History(List<OHLCV> fiveMinute,List<OHLCV> daily) {}
}
