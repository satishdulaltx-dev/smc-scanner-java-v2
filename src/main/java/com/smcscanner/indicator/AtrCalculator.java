package com.smcscanner.indicator;

import com.smcscanner.model.OHLCV;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class AtrCalculator {

    public double[] computeAtr(List<OHLCV> bars, int period) {
        int n = bars.size();
        double[] atr = new double[n];
        if (n == 0 || period < 1) return atr;
        double[] tr = new double[n];
        for (int i = 0; i < n; i++) {
            OHLCV cur = bars.get(i);
            if (i == 0) { tr[i] = cur.getHigh() - cur.getLow(); }
            else {
                double pc = bars.get(i-1).getClose();
                tr[i] = Math.max(cur.getHigh()-cur.getLow(), Math.max(Math.abs(cur.getHigh()-pc), Math.abs(cur.getLow()-pc)));
            }
        }
        if (n >= period) {
            double sum = 0; for (int i = 0; i < period; i++) sum += tr[i];
            atr[period-1] = sum / period;
            for (int i = period; i < n; i++) atr[i] = (atr[i-1]*(period-1)+tr[i])/period;
        }
        return atr;
    }

    public double lastAtr(List<OHLCV> bars) {
        if (bars == null || bars.isEmpty()) return 0.0;
        double[] atr = computeAtr(bars, 14);
        for (int i = atr.length-1; i >= 0; i--) if (atr[i] > 0) return atr[i];
        return 0.0;
    }

    public double atrPercentile(double[] atrValues, int currentIdx, int lookback) {
        if (atrValues == null || atrValues.length == 0) return 0.0;
        int idx = currentIdx < 0 ? atrValues.length + currentIdx : currentIdx;
        if (idx < 0 || idx >= atrValues.length) return 0.0;
        double current = atrValues[idx]; if (current == 0) return 0.0;
        int start = Math.max(0, idx-lookback+1);
        int below = 0, total = 0;
        for (int i = start; i <= idx; i++) { if (atrValues[i] <= current) below++; total++; }
        return total > 0 ? (double) below / total * 100.0 : 0.0;
    }
}
