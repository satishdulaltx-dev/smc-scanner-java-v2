package com.smcscanner.alert;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AlertDedup {
    private static final int COOLDOWN_MIN = 240; // 4 hours — one full trading session
    private final ConcurrentHashMap<String,Instant> last = new ConcurrentHashMap<>();

    // Key includes entry price (rounded to 2dp) so same FVG zone isn't re-fired after restart
    private String key(String ticker, String dir, double entry) {
        return ticker + ":" + dir + ":" + String.format("%.2f", entry);
    }
    public boolean isDuplicate(String ticker, String dir, double entry) {
        return isDuplicate(ticker, dir, entry, COOLDOWN_MIN);
    }
    public boolean isDuplicate(String ticker, String dir, double entry, int cooldownMin) {
        Instant l = last.get(key(ticker, dir, entry));
        return l != null && Instant.now().isBefore(l.plusSeconds(cooldownMin * 60L));
    }
    public void markSent(String ticker, String dir, double entry) { last.put(key(ticker, dir, entry), Instant.now()); }
    public void cleanup() { Instant cut=Instant.now().minusSeconds(COOLDOWN_MIN*60L); last.entrySet().removeIf(e->e.getValue().isBefore(cut)); }
}
