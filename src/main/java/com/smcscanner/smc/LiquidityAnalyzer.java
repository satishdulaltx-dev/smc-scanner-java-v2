package com.smcscanner.smc;

import com.smcscanner.model.*;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class LiquidityAnalyzer {
    private static final double EQUAL_TOL = 0.005, SWEEP_BUF = 0.002;

    public List<LiquiditySweep> detectLiquiditySweeps(List<OHLCV> bars, List<SwingPoint> swings) {
        List<LiquiditySweep> sweeps = new ArrayList<>();
        if (bars.isEmpty()||swings.isEmpty()) return sweeps;
        List<SwingPoint> highs = swings.stream().filter(s->s.getSwingType()==SwingType.HIGH).toList();
        List<SwingPoint> lows  = swings.stream().filter(s->s.getSwingType()==SwingType.LOW).toList();
        List<double[]> eqH = findEqualLevels(highs), eqL = findEqualLevels(lows);
        for (int i=0; i<bars.size(); i++) {
            OHLCV bar=bars.get(i);
            for (SwingPoint sh:highs) {
                if (sh.getIndex()>=i) continue;
                double lvl=sh.getPrice();
                if (bar.getHigh()>lvl*(1+SWEEP_BUF)&&bar.getClose()<lvl)
                    sweeps.add(LiquiditySweep.builder().index(i).levelType(isEq(lvl,eqH)?"equal":"single").direction("buy_side").price(lvl).sweptSwings(List.of(sh)).timestamp(bar.getTimestamp()).build());
            }
            for (SwingPoint sl:lows) {
                if (sl.getIndex()>=i) continue;
                double lvl=sl.getPrice();
                if (bar.getLow()<lvl*(1-SWEEP_BUF)&&bar.getClose()>lvl)
                    sweeps.add(LiquiditySweep.builder().index(i).levelType(isEq(lvl,eqL)?"equal":"single").direction("sell_side").price(lvl).sweptSwings(List.of(sl)).timestamp(bar.getTimestamp()).build());
            }
        }
        return sweeps;
    }

    private List<double[]> findEqualLevels(List<SwingPoint> s) {
        List<double[]> r=new ArrayList<>();
        for (int i=0;i<s.size();i++) for (int j=i+1;j<s.size();j++) {
            double a=s.get(i).getPrice(),b=s.get(j).getPrice();
            if (Math.abs(a-b)/Math.max(a,b)<=EQUAL_TOL) r.add(new double[]{Math.min(a,b),Math.max(a,b)});
        }
        return r;
    }
    private boolean isEq(double p, List<double[]> eqs) { return eqs.stream().anyMatch(r->p>=r[0]&&p<=r[1]); }
}
