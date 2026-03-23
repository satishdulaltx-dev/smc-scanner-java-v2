package com.smcscanner.state;

import com.smcscanner.model.TickerStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SharedState {

    private final ConcurrentHashMap<String, TickerStatus> tickerStatus = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> setups = Collections.synchronizedList(new ArrayList<>());
    private volatile String status   = "starting";
    private volatile String lastScan = null;

    public ConcurrentHashMap<String, TickerStatus> getTickerStatus() { return tickerStatus; }
    public String getStatus()   { return status; }
    public String getLastScan() { return lastScan; }

    public List<Map<String, Object>> getSetups() {
        synchronized (setups) { return new ArrayList<>(setups); }
    }
    public void setSetups(List<Map<String, Object>> s) {
        synchronized (setups) { setups.clear(); setups.addAll(s); }
    }
    public void setStatus(String s)   { this.status = s; }
    public void setLastScan(String s) { this.lastScan = s; }
    public void updateTicker(TickerStatus ts) { tickerStatus.put(ts.getTicker(), ts); }
    public void markLastScan() { this.lastScan = Instant.now().toString(); }
}
