package com.smcscanner.alert;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AlertDedup {
    private static final int COOLDOWN_MIN = 240; // 4 hours — one full trading session
    private final ConcurrentHashMap<String,Instant> last = new ConcurrentHashMap<>();

    private String key(String ticker,String dir) { return ticker+":"+dir; }
    public boolean isDuplicate(String ticker,String dir) {
        Instant l=last.get(key(ticker,dir));
        return l!=null && Instant.now().isBefore(l.plusSeconds(COOLDOWN_MIN*60L));
    }
    public void markSent(String ticker,String dir) { last.put(key(ticker,dir),Instant.now()); }
    public void cleanup() { Instant cut=Instant.now().minusSeconds(COOLDOWN_MIN*60L); last.entrySet().removeIf(e->e.getValue().isBefore(cut)); }
}
