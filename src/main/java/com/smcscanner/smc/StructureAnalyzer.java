package com.smcscanner.smc;

import com.smcscanner.model.*;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class StructureAnalyzer {
    private static final int DEFAULT_LOOKBACK = 5, SCAN_BARS = 50, DEDUP_BARS = 10;

    public List<SwingPoint> detectSwings(List<OHLCV> bars, int lookback) {
        List<SwingPoint> swings = new ArrayList<>();
        int n = bars.size();
        if (n < lookback*2+1) return swings;
        for (int i = lookback; i < n-lookback; i++) {
            double curH = bars.get(i).getHigh(), curL = bars.get(i).getLow();
            boolean isH = true, isL = true;
            for (int j = i-lookback; j <= i+lookback; j++) {
                if (j==i) continue;
                if (bars.get(j).getHigh() >= curH) isH = false;
                if (bars.get(j).getLow()  <= curL) isL = false;
            }
            if (isH) swings.add(SwingPoint.builder().index(i).price(curH).swingType(SwingType.HIGH).timestamp(String.valueOf(bars.get(i).getTimestamp())).build());
            if (isL) swings.add(SwingPoint.builder().index(i).price(curL).swingType(SwingType.LOW).timestamp(String.valueOf(bars.get(i).getTimestamp())).build());
        }
        return swings;
    }

    public List<SwingPoint> detectSwings(List<OHLCV> bars) { return detectSwings(bars, DEFAULT_LOOKBACK); }

    public List<StructureBreak> detectStructureBreaks(List<OHLCV> bars, List<SwingPoint> swings) {
        List<StructureBreak> breaks = new ArrayList<>();
        int n = bars.size();
        if (swings.size() < 2) return breaks;
        for (int si = 0; si < swings.size()-1; si++) {
            SwingPoint swing = swings.get(si);
            String trend = getTrend(swings, si);
            for (int bi = swing.getIndex()+1; bi < Math.min(swing.getIndex()+SCAN_BARS, n); bi++) {
                OHLCV bar = bars.get(bi);
                StructureBreak brk = null;
                if (swing.getSwingType()==SwingType.HIGH && bar.getClose()>swing.getPrice()) {
                    StructureType t = "bullish".equals(trend) ? StructureType.BOS : StructureType.CHOCH;
                    brk = StructureBreak.builder().index(bi).breakType(t).price(swing.getPrice()).priorSwingIdx(swing.getIndex()).confirmed(true).timestamp(String.valueOf(bar.getTimestamp())).build();
                } else if (swing.getSwingType()==SwingType.LOW && bar.getClose()<swing.getPrice()) {
                    StructureType t = "bearish".equals(trend) ? StructureType.BOS : StructureType.CHOCH;
                    brk = StructureBreak.builder().index(bi).breakType(t).price(swing.getPrice()).priorSwingIdx(swing.getIndex()).confirmed(true).timestamp(String.valueOf(bar.getTimestamp())).build();
                }
                if (brk != null) {
                    final StructureBreak fb = brk;
                    boolean dup = breaks.stream().anyMatch(b -> Math.abs(b.getIndex()-fb.getIndex())<DEDUP_BARS && b.getBreakType()==fb.getBreakType());
                    if (!dup) breaks.add(brk);
                    break;
                }
            }
        }
        return breaks;
    }

    private String getTrend(List<SwingPoint> swings, int upTo) {
        int h=0, l=0, c=0;
        for (int i=upTo-1; i>=0 && c<3; i--,c++) { if (swings.get(i).getSwingType()==SwingType.HIGH) h++; else l++; }
        return h>l ? "bullish" : (l>h ? "bearish" : "neutral");
    }

    public String getCurrentBias(List<StructureBreak> breaks) {
        if (breaks.isEmpty()) return "neutral";
        StructureBreak last = breaks.get(breaks.size()-1);
        if (last.getBreakType()==StructureType.CHOCH) return "neutral";
        int start = Math.max(0, breaks.size()-3);
        long bos = breaks.subList(start, breaks.size()).stream().filter(b->b.getBreakType()==StructureType.BOS).count();
        long choch = breaks.subList(start, breaks.size()).stream().filter(b->b.getBreakType()==StructureType.CHOCH).count();
        return bos >= choch ? "bullish" : "bearish";
    }
}
