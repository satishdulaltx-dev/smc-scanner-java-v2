package com.smcscanner.backtest;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import com.smcscanner.strategy.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StrategyIsolationTest {
    @Test void emptySelectedStrategyCannotInvokeOtherDetectors() throws Exception {
        var constructor=BacktestService.class.getConstructors()[0];
        var dependencies=new HashMap<Class<?>,Object>();var args=new ArrayList<Object>();
        for(Class<?> type:constructor.getParameterTypes()) {
            Object value=type==ScannerConfig.class?new ScannerConfig():mock(type);
            dependencies.put(type,value);args.add(value);
        }
        var client=(PolygonClient)dependencies.get(PolygonClient.class);
        var days=List.of(LocalDate.of(2026,5,29),LocalDate.of(2026,6,1));
        when(client.getHistoricalBars(anyString(),anyString(),any(),any())).thenAnswer(call->{
            String tf=call.getArgument(1);int step=tf.equals("1d")?390:tf.equals("15m")?15:tf.equals("60m")?60:tf.equals("1m")?1:5;
            var rows=new ArrayList<OHLCV>();
            for(var day:days) for(int minute=0;minute<390;minute+=step)rows.add(OHLCV.builder()
                    .timestamp(day.atTime(9,30).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()+minute*60000L)
                    .open(100).high(101).low(99).close(100).volume(100).build());
            return rows;
        });
        var regimes=(MarketRegimeDetector)dependencies.get(MarketRegimeDetector.class);
        when(regimes.detectForBacktest(anyList())).thenReturn(MarketRegimeDetector.Regime.RANGING);
        var selected=(KeyLevelStrategyDetector)dependencies.get(KeyLevelStrategyDetector.class);
        when(selected.detect(anyList(),anyList(),anyString(),anyDouble(),any(),eq(true))).thenReturn(List.of());
        var service=(BacktestService)constructor.newInstance(args.toArray());
        var result=service.run("TEST",BacktestMode.INTRADAY,"keylevel",BacktestExitStyle.FIXED_R,
                new BacktestRun(days.get(1),days.get(1)));
        assertNull(result.error);assertEquals(0,result.total);
        verify(selected,atLeastOnce()).detect(anyList(),anyList(),eq("TEST"),anyDouble(),any(),eq(true));
        verifyNoInteractions(dependencies.get(CapitulationReversalDetector.class),
                dependencies.get(LiquiditySweepFlipDetector.class),dependencies.get(PdhPdlDetector.class),
                dependencies.get(ScalpMomentumDetector.class));
        verify(regimes,never()).suggestStrategy(any(),anyString());
    }
}
