package com.smcscanner.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent trade outcomes per ticker to detect consecutive-loss regimes.
 *
 * How it works:
 *   - Maintains a per-ticker deque of the last 6 outcomes (true=win, false=loss).
 *   - SignalQualityFilter reads the consecutive-loss count and applies a confidence
 *     penalty (−8 / −15 / −25) so signals fire but at lower confidence.
 *   - State is persisted to data/adaptive-outcomes.json so it survives restarts.
 *
 * Live scanner usage:
 *   - Outcomes are recorded via the /api/adaptive/outcome endpoint after a trade
 *     is manually reviewed or resolved (or via EOD report integration).
 *   - Call recordWin(ticker) / recordLoss(ticker) from EodReportService or manually.
 *
 * Backtest usage:
 *   - BacktestService maintains its OWN local streak map within the run() method
 *     (so it doesn't pollute the live adaptive state file).
 *   - The live AdaptiveSuppressor is NOT called during backtests.
 */
@Service
public class AdaptiveSuppressor {
    private static final Logger log    = LoggerFactory.getLogger(AdaptiveSuppressor.class);
    private static final String FILE   = "data/adaptive-outcomes.json";
    private static final int    WINDOW = 6;  // keep last 6 outcomes per ticker

    private final ObjectMapper mapper = new ObjectMapper();

    /** Per-ticker deque; most recent outcome = tail. true=win, false=loss. */
    private final Map<String, Deque<Boolean>> outcomes = new ConcurrentHashMap<>();

    public AdaptiveSuppressor() { loadFromFile(); }

    // ── Record outcomes ───────────────────────────────────────────────────────

    public void recordWin(String ticker) {
        record(ticker, true);
        persist();
        log.info("AdaptiveSuppressor: {} WIN recorded → streak reset", ticker);
    }

    public void recordLoss(String ticker) {
        record(ticker, false);
        persist();
        int streak = getConsecutiveLosses(ticker);
        log.warn("AdaptiveSuppressor: {} LOSS recorded → {} consecutive", ticker, streak);
    }

    /** Wipes the outcome history for a ticker (e.g. after ticker profile changes). */
    public void reset(String ticker) {
        outcomes.remove(ticker);
        persist();
        log.info("AdaptiveSuppressor: {} reset", ticker);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Returns the number of consecutive losses at the tail of the outcome queue. */
    public int getConsecutiveLosses(String ticker) {
        Deque<Boolean> q = outcomes.get(ticker);
        if (q == null || q.isEmpty()) return 0;
        int count = 0;
        Boolean[] arr = q.toArray(new Boolean[0]);
        for (int i = arr.length - 1; i >= 0; i--) {
            if (Boolean.FALSE.equals(arr[i])) count++;
            else break;
        }
        return count;
    }

    /** Returns a summary of all non-zero streak tickers (for the dashboard). */
    public Map<String, Integer> getActiveStreaks() {
        Map<String, Integer> result = new LinkedHashMap<>();
        outcomes.forEach((ticker, q) -> {
            int s = getConsecutiveLosses(ticker);
            if (s >= 2) result.put(ticker, s);
        });
        return result;
    }

    /** Full outcome snapshot for the adaptive state API endpoint. */
    public Map<String, List<Boolean>> getSnapshot() {
        Map<String, List<Boolean>> snap = new LinkedHashMap<>();
        outcomes.forEach((k, q) -> snap.put(k, new ArrayList<>(q)));
        return snap;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadFromFile() {
        File f = new File(FILE);
        if (!f.exists()) return;
        try {
            Map<String, List<Boolean>> data = mapper.readValue(f,
                    new TypeReference<Map<String, List<Boolean>>>() {});
            data.forEach((ticker, list) -> {
                Deque<Boolean> q = new ArrayDeque<>();
                list.forEach(b -> { if (q.size() >= WINDOW) q.pollFirst(); q.addLast(b); });
                outcomes.put(ticker, q);
            });
            log.info("AdaptiveSuppressor: loaded {} ticker states from {}", outcomes.size(), FILE);
        } catch (Exception e) {
            log.warn("AdaptiveSuppressor: could not load state file ({}) — starting fresh", e.getMessage());
        }
    }

    private void persist() {
        try {
            File f = new File(FILE);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, getSnapshot());
        } catch (Exception e) {
            log.warn("AdaptiveSuppressor: persist error: {}", e.getMessage());
        }
    }

    private void record(String ticker, boolean isWin) {
        Deque<Boolean> q = outcomes.computeIfAbsent(ticker, k -> new ArrayDeque<>());
        if (q.size() >= WINDOW) q.pollFirst();
        q.addLast(isWin);
    }
}
