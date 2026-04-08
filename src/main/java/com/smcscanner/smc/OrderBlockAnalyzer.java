package com.smcscanner.smc;

import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.OHLCV;
import com.smcscanner.model.OrderBlock;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderBlockAnalyzer {
    private static final double DISP_ATR_MULT = 1.8;
    private static final int    MAX_AGE       = 50;

    private final AtrCalculator atr;
    public OrderBlockAnalyzer(AtrCalculator atr) { this.atr = atr; }

    public List<OrderBlock> detectOrderBlocks(List<OHLCV> bars) {
        List<OrderBlock> obs = new ArrayList<>();
        int n = bars.size(); if (n < 3) return obs;
        double[] atrV = atr.computeAtr(bars, 14);
        for (int i = 1; i < n-1; i++) {
            OHLCV prev = bars.get(i-1), cur = bars.get(i);
            double atrVal = atrV[i]>0 ? atrV[i] : (cur.getHigh()-cur.getLow());
            if ((cur.getHigh()-cur.getLow()) < atrVal*DISP_ATR_MULT) continue;
            boolean bullDisp = cur.getClose()>cur.getOpen() && cur.getClose()>prev.getHigh();
            boolean bearDisp = cur.getClose()<cur.getOpen() && cur.getClose()<prev.getLow();
            if (bullDisp) {
                for (int j=i-1; j>=Math.max(0,i-MAX_AGE); j--) {
                    OHLCV c=bars.get(j);
                    if (c.getClose()<c.getOpen()) {
                        OrderBlock ob=OrderBlock.builder().index(j).high(c.getHigh()).low(c.getLow()).openBarIdx(j).direction("bullish").mitigated(false).touchCount(0).timestamp(String.valueOf(c.getTimestamp())).build();
                        checkMitigation(ob,bars,i+1); obs.add(ob); break;
                    }
                }
            }
            if (bearDisp) {
                for (int j=i-1; j>=Math.max(0,i-MAX_AGE); j--) {
                    OHLCV c=bars.get(j);
                    if (c.getClose()>c.getOpen()) {
                        OrderBlock ob=OrderBlock.builder().index(j).high(c.getHigh()).low(c.getLow()).openBarIdx(j).direction("bearish").mitigated(false).touchCount(0).timestamp(String.valueOf(c.getTimestamp())).build();
                        checkMitigation(ob,bars,i+1); obs.add(ob); break;
                    }
                }
            }
        }
        return deduplicate(obs);
    }

    private void checkMitigation(OrderBlock ob, List<OHLCV> bars, int from) {
        for (int j=from; j<bars.size(); j++) {
            OHLCV bar=bars.get(j);
            if (bar.getLow()<=ob.getHigh()&&bar.getHigh()>=ob.getLow()) ob.setTouchCount(ob.getTouchCount()+1);
            if ("bullish".equals(ob.getDirection())&&bar.getClose()<ob.getLow()) { ob.setMitigated(true); ob.setMitigationIndex(j); return; }
            if ("bearish".equals(ob.getDirection())&&bar.getClose()>ob.getHigh()) { ob.setMitigated(true); ob.setMitigationIndex(j); return; }
        }
    }

    private List<OrderBlock> deduplicate(List<OrderBlock> obs) {
        List<OrderBlock> result=new ArrayList<>();
        for (OrderBlock ob:obs) {
            boolean dup=result.stream().anyMatch(r->r.getDirection().equals(ob.getDirection())&&Math.abs(r.midpoint()-ob.midpoint())/ob.midpoint()<0.002);
            if (!dup) result.add(ob);
        }
        return result;
    }
}
