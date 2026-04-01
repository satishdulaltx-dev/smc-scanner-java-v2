package com.smcscanner.tracking;

import com.smcscanner.model.TradeSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class PerformanceTracker {
    private static final Logger log = LoggerFactory.getLogger(PerformanceTracker.class);
    private final List<Map<String,Object>> history = Collections.synchronizedList(new ArrayList<>());

    // ── Strategy signal counters (Phase 10) ──────────────────────────────────
    // Tracks how many signals each strategy type has produced in this session.
    // Keys: "smc", "vwap", "breakout", "keylevel", "swing", "range"
    private final ConcurrentHashMap<String, AtomicInteger> strategySignals = new ConcurrentHashMap<>();
    // Per-strategy confidence accumulator for avg confidence
    private final ConcurrentHashMap<String, List<Integer>> strategyConfidences = new ConcurrentHashMap<>();

    public void recordSetup(TradeSetup s) {
        Map<String,Object> e=new LinkedHashMap<>(s.toMap());
        e.put("recorded_at",Instant.now().toString()); e.put("status","detected");
        history.add(e); log.debug("Recorded: {} {}",s.getTicker(),s.getDirection());
    }

    /** Record a strategy signal for per-layer performance tracking. */
    public void recordStrategySignal(String strategyType, int confidence) {
        if (strategyType == null) strategyType = "unknown";
        strategySignals.computeIfAbsent(strategyType, k -> new AtomicInteger()).incrementAndGet();
        strategyConfidences.computeIfAbsent(strategyType, k -> Collections.synchronizedList(new ArrayList<>())).add(confidence);
    }

    /** Get per-strategy signal counts and avg confidence. */
    public Map<String, Map<String, Object>> getStrategyStats() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : strategySignals.entrySet()) {
            String strat = entry.getKey();
            int count = entry.getValue().get();
            List<Integer> confs = strategyConfidences.getOrDefault(strat, List.of());
            double avgConf = confs.stream().mapToInt(Integer::intValue).average().orElse(0);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("signals", count);
            stats.put("avg_confidence", Math.round(avgConf * 10) / 10.0);
            result.put(strat, stats);
        }
        return result;
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
            Map<String,Object> stats = new LinkedHashMap<>();
            stats.put("total_setups", history.size());
            stats.put("by_ticker", byTicker);
            stats.put("by_direction", byDir);
            stats.put("avg_confidence", Math.round(avgC*10)/10.0);
            stats.put("by_strategy", getStrategyStats());
            return stats;
        }
    }
}
