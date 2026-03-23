package com.smcscanner.strategy;

import com.smcscanner.model.*;
import com.smcscanner.smc.StructureAnalyzer;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class MultiTimeframeAnalyzer {
    private final StructureAnalyzer sa;
    public MultiTimeframeAnalyzer(StructureAnalyzer sa) { this.sa=sa; }

    public String getHtfBias(List<OHLCV> htfBars) {
        if (htfBars==null||htfBars.size()<10) return "neutral";
        try {
            List<SwingPoint> sw=sa.detectSwings(htfBars,3);
            if (sw.size()<2) return "neutral";
            return sa.getCurrentBias(sa.detectStructureBreaks(htfBars,sw));
        } catch (Exception e) { return "neutral"; }
    }
}
