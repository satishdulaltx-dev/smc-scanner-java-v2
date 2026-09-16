package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScalpSetupRulesTest {
    private static OHLCV b(double o,double h,double l,double c) {
        return OHLCV.builder().open(o).high(h).low(l).close(c).volume(100).build();
    }
    private static OHLCV at(String time) {
        return OHLCV.builder().timestamp(LocalDateTime.parse(time).atZone(ZoneId.of("America/New_York"))
                .toInstant().toEpochMilli()).open(100).high(101).low(99).close(100).volume(100).build();
    }
    @Test void whollyBelowLevelDoesNotCountAsPriorTouch() {
        var bars=List.of(b(101,102,100.5,101), b(98,99,97,98.5), b(101,102,100.8,101.8));
        assertNull(ScalpSetupRules.rejection(bars,100,100,.2,true));
    }
    @Test void approachMustPrecedePullbackSoBreakoutIsNotMislabeledRejection() {
        var bars=List.of(b(98,99,97,98),b(98,99,97,98.5),b(99,101,98.8,100.8));
        assertNull(ScalpSetupRules.rejection(bars,100,100,.2,true));
    }
    @Test void priorTouchNeedsFollowThroughAndStopKeepsItsWick() {
        var bars=List.of(b(101,102,100.5,101),b(101,101.3,99.5,100.1),b(100.5,102,100.4,101.8));
        var r=ScalpSetupRules.rejection(bars,100,100,.2,true);
        assertNotNull(r);assertEquals(1,r.touchIndex());assertEquals(99.5,r.extreme());
        var stalled=List.of(bars.get(0),bars.get(1),b(100.5,101.1,100.4,100.9));
        assertNull(ScalpSetupRules.rejection(stalled,100,100,.2,true));
    }
    @Test void touchingBarCannotMoveItsOwnAnchor() {
        var bars=List.of(b(101,102,100.5,101),b(101,101.3,99.5,100.1),b(100.5,102,100.4,101.8));
        var r=ScalpSetupRules.rejection(bars,105,100,.2,true);
        assertNotNull(r);assertEquals(100,r.level());
    }
    @Test void shortRulesMirrorLongRules() {
        var bars=List.of(b(99,99.5,98,99),b(99,100.5,98.7,99.9),b(99.5,99.6,98,98.2));
        var r=ScalpSetupRules.rejection(bars,100,100,.2,false);
        assertNotNull(r);assertEquals(100.5,r.extreme());assertEquals(1,r.touchIndex());
    }
    @Test void nearbyResistanceCannotBeMovedToManufactureReward() {
        var bars=List.of(b(100,101,99,100),b(100,102,99,101),b(101,101.5,100,101));
        assertEquals(102,ScalpSetupRules.target(bars,101,104,true));
        assertFalse(ScalpSetupRules.validRoom(101,100,102,true,1.5));
        assertFalse(ScalpSetupRules.validRoom(101,100,100.5,true,1.5));
        assertFalse(ScalpSetupRules.validRoom(101,102,103,true,1.5));
        assertTrue(ScalpSetupRules.validRoom(101,100,103,true,1.5));
    }
    @Test void brokenPivotIsNotUntouchedResistanceAndShortTargetMirrors() {
        var bars=List.of(b(100,101,99,100),b(100,102,99,101),b(101,103,100,101));
        assertEquals(104,ScalpSetupRules.target(bars,101,104,true));
        var shortBars=List.of(b(100,101,99,100),b(100,101,98,99),b(99,100,98.5,99));
        assertEquals(98,ScalpSetupRules.target(shortBars,99,96,false));
    }
    @Test void spyContextExcludesPriorDayPremarketAndUnfinishedCandle() {
        var bars=List.of(at("2026-06-01T15:55"),at("2026-06-02T09:25"),at("2026-06-02T09:30"),
                at("2026-06-02T09:35"),at("2026-06-02T09:40"));
        long decision=at("2026-06-02T09:40").getTimestamp();
        var aligned=ScalpSetupRules.sessionAsOf(bars,decision);
        assertEquals(List.of(bars.get(2),bars.get(3)),aligned);
        assertTrue(ScalpSetupRules.contiguousFromOpen(aligned,decision));
        assertFalse(ScalpSetupRules.contiguousFromOpen(List.of(bars.get(3)),decision));
        assertFalse(ScalpSetupRules.contiguousFromOpen(List.of(bars.get(2)),decision));
    }
    @Test void completedHistoricalRejectionSurvivesWithoutFabricatedTarget() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var root=mapper.readTree(getClass().getResourceAsStream("/scalp/amd-completed-rejection.json"));
        var bars=new ArrayList<OHLCV>();
        for(var row:root.get("bars")) bars.add(OHLCV.builder().timestamp(row.get("timestamp").asLong())
                .open(row.get("open").asDouble()).high(row.get("high").asDouble()).low(row.get("low").asDouble())
                .close(row.get("close").asDouble()).volume(row.get("volume").asDouble()).build());
        var detector=new ScalpMomentumDetector(null,new com.smcscanner.indicator.VolumeProfileCalculator(),null);
        var decision=detector.inspect(bars,List.of(),"AMD",0,true);
        assertEquals(1,decision.setups().size(),decision.reason());
        var setup=decision.setups().get(0);
        assertEquals("long",setup.getDirection());
        assertEquals(146.2943,setup.getStopLoss(),.0001);
        assertEquals(147.1702,setup.getTakeProfit(),.0001);
        assertTrue(setup.getFactorBreakdown().contains("SPY=unavailable"));
        assertTrue(setup.getFactorBreakdown().contains("structural-stop="));
        assertTrue(setup.rrRatio()>=1.5);
        // Prefix replay and future context exclusion must not depend on wall-clock date.
        var futureSpy=List.of(at("2026-06-02T09:30"),at("2026-06-02T09:35"));
        var withFuture=detector.detect(bars,futureSpy,"AMD",0,true).get(0);
        assertEquals(setup.getConfidence(),withFuture.getConfidence());
        assertEquals(setup.getStopLoss(),withFuture.getStopLoss());
    }

    @Test void detectorReportsWarmupInsteadOfObsoleteBollingerMessage() {
        var detector=new ScalpMomentumDetector(null,null,null);
        var result=detector.inspect(List.of(),List.of(),"TEST",1,true);
        assertTrue(result.setups().isEmpty());assertTrue(result.reason().startsWith("Warming up"));
    }
}
