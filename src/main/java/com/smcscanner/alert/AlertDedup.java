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
    private static final int TICKER_COOLDOWN_MIN = 60; // 1 hour — prevents same ticker from spamming

    // How long after startup to silently seed the dedup map without firing alerts.
    // Prevents "replay on restart": same 15-min-delayed Polygon bars detected on startup
    // re-fire an alert that already went out 5–40 minutes ago.
    // After this window, normal alert flow resumes.
    private static final int STARTUP_QUIET_SEC = 180; // 3 minutes
    private final Instant startupTime = Instant.now();

    private final ConcurrentHashMap<String, Instant> last = new ConcurrentHashMap<>();
    // Ticker-level cooldown: prevents same ticker from alerting multiple times when
    // entry price shifts slightly between scans (e.g. AAPL $228.50 → $228.51 → $228.52)
    private final ConcurrentHashMap<String, Instant> tickerLast = new ConcurrentHashMap<>();

    // Key includes entry price (rounded to 2dp) so same FVG zone isn't re-fired
    private String key(String ticker, String dir, double entry) {
        return ticker + ":" + dir + ":" + String.format("%.2f", entry);
    }

    public boolean isDuplicate(String ticker, String dir, double entry) {
        return isDuplicate(ticker, dir, entry, COOLDOWN_MIN);
    }

    public boolean isDuplicate(String ticker, String dir, double entry, int cooldownMin) {
        // Check ticker-level cooldown first (prevents spam from price drift)
        Instant tickerTime = tickerLast.get(ticker);
        if (tickerTime != null && Instant.now().isBefore(tickerTime.plusSeconds(TICKER_COOLDOWN_MIN * 60L))) {
            return true;
        }
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
        tickerLast.put(ticker, Instant.now());
    }

    /**
     * Seed-only: marks the specific entry/direction as seen (prevents exact replay)
     * but does NOT stamp the ticker-level cooldown.
     * Used during startup quiet window so fresh signals can still fire after startup.
     */
    public void markSeedOnly(String ticker, String dir, double entry) {
        last.put(key(ticker, dir, entry), Instant.now());
        // intentionally NOT updating tickerLast — startup should not block fresh alerts
    }

    public void cleanup() {
        Instant cut = Instant.now().minusSeconds(COOLDOWN_MIN * 60L);
        last.entrySet().removeIf(e -> e.getValue().isBefore(cut));
        Instant tickerCut = Instant.now().minusSeconds(TICKER_COOLDOWN_MIN * 60L);
        tickerLast.entrySet().removeIf(e -> e.getValue().isBefore(tickerCut));
    }
}
