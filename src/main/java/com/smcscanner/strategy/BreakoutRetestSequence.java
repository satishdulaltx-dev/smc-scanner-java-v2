package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import java.util.List;

/** Reconstruct a level's lifecycle from completed bars; never reuse an old confirmation. */
public final class BreakoutRetestSequence {
    private BreakoutRetestSequence() {}
    public record Confirmation(int breakoutIndex, int retestIndex, double invalidationExtreme) {}

    public static Confirmation atLastBar(List<OHLCV> bars, double level, boolean bullish,
                                         double breakBuffer, double tolerance, int expiryBars) {
        if (bars.size()<4 || level<=0 || breakBuffer<0 || tolerance<0 || expiryBars<2) return null;
        int breakout=-1, touch=-1;
        double extreme=level, confirmationEdge=level;
        for (int i=1;i<bars.size();i++) {
            OHLCV bar=bars.get(i),previous=bars.get(i-1);
            boolean freshBreak=bullish
                    ? previous.getClose()<=level && bar.getClose()>level+breakBuffer && bar.getClose()>bar.getOpen()
                    : previous.getClose()>=level && bar.getClose()<level-breakBuffer && bar.getClose()<bar.getOpen();
            if (breakout<0) {
                if (freshBreak) {breakout=i;touch=-1;extreme=level;}
                continue;
            }
            boolean failed=bullish?bar.getClose()<level-tolerance:bar.getClose()>level+tolerance;
            if (failed || i-breakout>expiryBars) {
                breakout=freshBreak?i:-1;touch=-1;extreme=level;continue;
            }
            if (touch<0) {
                boolean intersects=bar.getLow()<=level+tolerance && bar.getHigh()>=level-tolerance;
                if (intersects) {
                    touch=i;extreme=bullish?Math.min(level,bar.getLow()):Math.max(level,bar.getHigh());
                    confirmationEdge=bullish?bar.getHigh():bar.getLow();
                }
                continue; // a touch cannot be its own subsequent confirmation
            }
            extreme=bullish?Math.min(extreme,bar.getLow()):Math.max(extreme,bar.getHigh());
            boolean confirms=bullish
                    ? bar.getClose()>Math.max(level,confirmationEdge) && bar.getClose()>bar.getOpen()
                    : bar.getClose()<Math.min(level,confirmationEdge) && bar.getClose()<bar.getOpen();
            if (confirms) {
                if (i==bars.size()-1) return new Confirmation(breakout,touch,extreme);
                breakout=-1;touch=-1; // already emitted on an earlier completed bar
            }
        }
        return null;
    }
}
