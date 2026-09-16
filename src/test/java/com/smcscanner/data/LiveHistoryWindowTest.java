package com.smcscanner.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveHistoryWindowTest {
    @Test void minuteLookbackIncludesFridayWhenScanningMonday() {
        assertTrue(PolygonClient.liveLookbackDays(new String[]{"5","minute"},100)>=5);
        assertTrue(PolygonClient.liveLookbackDays(new String[]{"1","minute"},100)>=5);
    }
    @Test void largerMinuteHistoryRequestExpandsBeyondOneSession() {
        assertTrue(PolygonClient.liveLookbackDays(new String[]{"5","minute"},400)>=12);
        assertEquals(7,PolygonClient.liveLookbackDays(new String[]{"1","hour"},100));
        assertEquals(500,PolygonClient.liveLookbackDays(new String[]{"1","day"},250));
    }
}
