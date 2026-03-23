package com.smcscanner.smc;

import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.model.FairValueGap;
import com.smcscanner.model.OHLCV;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class FvgAnalyzer {
    private static final double MIN_SIZE_ATR = 0.3;
    private static final int    MAX_AGE      = 50;

    private final AtrCalculator atr;
    public FvgAnalyzer(AtrCalculator atr) { this.atr = atr; }

    public List<FairValueGap> detectFvg(List<OHLCV> bars) { return detectFvg(bars, MIN_SIZE_ATR); }

    public List<FairValueGap> detectFvg(List<OHLCV> bars, double minSizeAtr) {
        List<FairValueGap> fvgs = new ArrayList<>();
        int n = bars.size(); if (n < 3) return fvgs;
        double[] atrV = atr.computeAtr(bars, 14);
        int start = Math.max(0, n-MAX_AGE-2);
        for (int i = start; i < n-2; i++) {
            OHLCV b1 = bars.get(i), b3 = bars.get(i+2);
            double atrVal = atrV[i+1]>0 ? atrV[i+1] : atrV[i];
            double minSize = atrVal * minSizeAtr;
            if (b3.getLow() > b1.getHigh() && (b3.getLow()-b1.getHigh()) >= minSize) {
                FairValueGap fvg = FairValueGap.builder().index(i+1).high(b3.getLow()).low(b1.getHigh()).direction("bullish").filled(false).timestamp(bars.get(i+1).getTimestamp()).build();
                checkFill(fvg, bars, i+2); fvgs.add(fvg);
            }
            if (b1.getLow() > b3.getHigh() && (b1.getLow()-b3.getHigh()) >= minSize) {
                FairValueGap fvg = FairValueGap.builder().index(i+1).high(b1.getLow()).low(b3.getHigh()).direction("bearish").filled(false).timestamp(bars.get(i+1).getTimestamp()).build();
                checkFill(fvg, bars, i+2); fvgs.add(fvg);
            }
        }
        return fvgs;
    }

    private void checkFill(FairValueGap fvg, List<OHLCV> bars, int from) {
        for (int j=from; j<bars.size(); j++) {
            OHLCV bar = bars.get(j);
            if (bar.getClose() <= fvg.getHigh() && bar.getClose() >= fvg.getLow()) {
                fvg.setFilled(true); fvg.setFillIndex(j); fvg.setFillPrice(bar.getClose()); return;
            }
        }
    }
}
