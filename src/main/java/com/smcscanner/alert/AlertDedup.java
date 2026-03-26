package com.smcscanner.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AlertDedup {
    private static final Logger log = LoggerFactory.getLogger(AlertDedup.class);
    private static final int COOLDOWN_MIN = 240; // 4 hours — one full trading session

    // How long after startup to silently seed the dedup map without firing alerts.
    // Prevents "replay on restart": same 15-min-delayed Polygon bars detected on startup
    // re-fire an alert that already went out 5–40 minutes ago.
    // After this window, normal alert flow resumes.
    private static final int STARTUP_QUIET_SEC = 180; // 3 minutes
    private final Instant startupTime = Instant.now();

    private final ConcurrentHashMap<String, Instant> last = new ConcurrentHashMap<>();

    // Key includes entry price (rounded to 2dp) so same FVG zone isn't re-fired
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

    /**
     * True during the startup quiet window.
     * Callers should seed (markSent without firing) during this window
     * to prevent replaying alerts that already fired before the restart.
     */
    public boolean isStartupQuiet() {
        return Instant.now().isBefore(startupTime.plusSeconds(STARTUP_QUIET_SEC));
    }

    public void markSent(String ticker, String dir, double entry) {
        last.put(key(ticker, dir, entry), Instant.now());
    }

    public void cleanup() {
        Instant cut = Instant.now().minusSeconds(COOLDOWN_MIN * 60L);
        last.entrySet().removeIf(e -> e.getValue().isBefore(cut));
    }
}
