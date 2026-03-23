package com.smcscanner.tracking;

import com.smcscanner.model.TradeSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PerformanceTracker {
    private static final Logger log = LoggerFactory.getLogger(PerformanceTracker.class);
    private final List<Map<String,Object>> history = Collections.synchronizedList(new ArrayList<>());

    public void recordSetup(TradeSetup s) {
        Map<String,Object> e=new LinkedHashMap<>(s.toMap());
        e.put("recorded_at",Instant.now().toString()); e.put("status","detected");
        history.add(e); log.debug("Recorded: {} {}",s.getTicker(),s.getDirection());
    }

    public List<Map<String,Object>> getRecent(int limit) {
        synchronized(history) {
            int sz=history.size(), start=Math.max(0,sz-limit);
            List<Map<String,Object>> r=new ArrayList<>(history.subList(start,sz));
            Collections.reverse(r); return r;
        }
    }

    public Map<String,Object> getStats() {
        synchronized(history) {
            Map<String,Long> byTicker=history.stream().collect(Collectors.groupingBy(e->(String)e.getOrDefault("ticker","unknown"),Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).collect(Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue,(a,b)->a,LinkedHashMap::new));
            Map<String,Long> byDir=history.stream().collect(Collectors.groupingBy(e->(String)e.getOrDefault("direction","unknown"),Collectors.counting()));
            double avgC=history.stream().mapToInt(e->((Number)e.getOrDefault("confidence",0)).intValue()).average().orElse(0);
            return Map.of("total_setups",history.size(),"by_ticker",byTicker,"by_direction",byDir,"avg_confidence",Math.round(avgC*10)/10.0);
        }
    }
}
