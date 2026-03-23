package com.smcscanner.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smcscanner.model.indicator.InsiderActivity;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * Fetches SEC Form 4 insider trading data from EDGAR.
 *
 * Flow per ticker:
 *  1. Load company_tickers.json (cached 24 h) → map ticker → CIK number
 *  2. GET submissions/CIK{padded}.json → recent Form 4 filings list
 *  3. For each Form 4 in the last N days (up to 3), GET and parse the XML
 *  4. Extract transaction codes (P=buy, S=sell) + shares × price = value
 *  5. Build a human-readable signal string
 *
 * SEC rate limit: 10 req/s. We sleep 150 ms between XML fetches.
 * Results are cached for 6 hours per ticker.
 */
@Service
public class SecEdgarClient {
    private static final Logger log = LoggerFactory.getLogger(SecEdgarClient.class);

    // Required by SEC — must identify the application
    private static final String USER_AGENT   = "SD-Scanner smcscanner@smcscanner.com";
    private static final String TICKERS_URL  = "https://www.sec.gov/files/company_tickers.json";
    private static final String SUBMISSIONS  = "https://data.sec.gov/submissions/CIK%s.json";
    private static final String ARCHIVES     = "https://www.sec.gov/Archives/edgar/data/%d/%s/%s";

    private static final long CIK_TTL_MS     = 24 * 3_600_000L;   // 24 h
    private static final long INSIDER_TTL_MS =  6 * 3_600_000L;   //  6 h

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http   = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    // ── CIK cache ──────────────────────────────────────────────────────────────
    private volatile Map<String, Long> cikMap;     // TICKER → cik_number (long)
    private volatile long cikFetchedAt = 0;

    // ── Per-ticker insider cache ───────────────────────────────────────────────
    private record CachedInsider(InsiderActivity activity, long fetchedAt) {
        boolean isExpired() { return System.currentTimeMillis() > fetchedAt + INSIDER_TTL_MS; }
    }
    private final Map<String, CachedInsider> insiderCache = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    public InsiderActivity getActivity(String ticker, int lookbackDays) {
        if (ticker == null || ticker.startsWith("X:") || ticker.startsWith("I:")) return null;

        CachedInsider cached = insiderCache.get(ticker);
        if (cached != null && !cached.isExpired()) return cached.activity();

        try {
            InsiderActivity a = fetchActivity(ticker, lookbackDays);
            insiderCache.put(ticker, new CachedInsider(a, System.currentTimeMillis()));
            return a;
        } catch (Exception e) {
            log.debug("Insider fetch error {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    // ── Internal fetch ─────────────────────────────────────────────────────────

    private InsiderActivity fetchActivity(String ticker, int days) throws Exception {
        Long cik = getCik(ticker);
        if (cik == null) { log.debug("No CIK for {}", ticker); return null; }

        // Fetch filing list
        String body = get(String.format(SUBMISSIONS, String.format("%010d", cik)));
        if (body == null) return null;

        JsonNode root   = mapper.readTree(body);
        JsonNode recent = root.path("filings").path("recent");
        if (recent.isMissingNode()) return null;

        JsonNode forms   = recent.get("form");
        JsonNode dates   = recent.get("filingDate");
        JsonNode accNos  = recent.get("accessionNumber");
        JsonNode priDocs = recent.get("primaryDocument");
        if (forms == null || dates == null || accNos == null || priDocs == null) return null;

        LocalDate cutoff = LocalDate.now().minusDays(days);

        int    buyCount = 0, sellCount = 0, xmlFetched = 0;
        double buyVal   = 0, sellVal   = 0;

        for (int i = 0; i < forms.size() && xmlFetched < 3; i++) {
            if (!"4".equals(forms.get(i).asText())) continue;

            // Date filter — filings are newest-first, so break when too old
            LocalDate fd;
            try { fd = LocalDate.parse(dates.get(i).asText()); }
            catch (Exception e) { continue; }
            if (fd.isBefore(cutoff)) break;

            String acc = accNos.get(i).asText().replace("-", "");
            String doc = priDocs.get(i).asText();

            // Skip non-XML primary docs (e.g. .htm index pages)
            if (doc == null || doc.isBlank() ||
                (!doc.endsWith(".xml") && !doc.endsWith(".XML"))) continue;

            String xmlUrl = String.format(ARCHIVES, cik, acc, doc);
            String xml = get(xmlUrl);
            if (xml == null) continue;

            int[]    codes  = extractTransactionCodes(xml);   // [buys, sells]
            double[] values = extractTransactionValues(xml);  // [buyVal, sellVal]

            buyCount  += codes[0];   buyVal  += values[0];
            sellCount += codes[1];   sellVal += values[1];
            xmlFetched++;

            Thread.sleep(150); // respect SEC 10 req/s limit
        }

        if (buyCount == 0 && sellCount == 0) return null;

        String signal = buildSignal(buyCount, sellCount, buyVal, sellVal);
        return new InsiderActivity(buyCount, sellCount,
                buyVal  / 1_000_000.0,
                sellVal / 1_000_000.0,
                signal);
    }

    // ── XML parsing ────────────────────────────────────────────────────────────

    /** Returns [buyCount, sellCount] from nonDerivativeTransaction blocks only. */
    private int[] extractTransactionCodes(String xml) {
        int buys = 0, sells = 0;
        Matcher m = Pattern.compile(
                "<nonDerivativeTransaction>(.*?)</nonDerivativeTransaction>",
                Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            Matcher cm = Pattern.compile("<transactionCode>([A-Z])</transactionCode>")
                    .matcher(m.group(1));
            if (cm.find()) {
                String code = cm.group(1);
                if ("P".equals(code))      buys++;
                else if ("S".equals(code)) sells++;
                // A=Award, M=Option-exercise, F=Tax-withholding → ignore
            }
        }
        return new int[]{buys, sells};
    }

    /** Returns [totalBuyValue, totalSellValue] in dollars. */
    private double[] extractTransactionValues(String xml) {
        double buyVal = 0, sellVal = 0;
        Matcher blockMatcher = Pattern.compile(
                "<nonDerivativeTransaction>(.*?)</nonDerivativeTransaction>",
                Pattern.DOTALL).matcher(xml);

        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);

            Matcher codeM   = Pattern.compile("<transactionCode>([A-Z])</transactionCode>").matcher(block);
            Matcher sharesM = Pattern.compile("<transactionShares>\\s*<value>([\\d.,]+)</value>").matcher(block);
            Matcher priceM  = Pattern.compile("<transactionPricePerShare>\\s*<value>([\\d.,]+)</value>").matcher(block);

            if (!codeM.find()) continue;
            String code = codeM.group(1);
            if (!"P".equals(code) && !"S".equals(code)) continue;

            double shares = sharesM.find() ? parseNum(sharesM.group(1)) : 0;
            double price  = priceM.find()  ? parseNum(priceM.group(1))  : 0;
            double val    = shares * price;

            if ("P".equals(code)) buyVal  += val;
            else                  sellVal += val;
        }
        return new double[]{buyVal, sellVal};
    }

    private double parseNum(String s) {
        try { return Double.parseDouble(s.replace(",", "")); }
        catch (Exception e) { return 0; }
    }

    // ── Signal builder ─────────────────────────────────────────────────────────

    private String buildSignal(int buys, int sells, double buyVal, double sellVal) {
        if (buys > 0 && buys >= sells) {
            String valStr = buyVal > 100_000 ? String.format(" $%.1fM", buyVal / 1_000_000) : "";
            return String.format("🐋 %d insider buy%s%s (30d)", buys, buys > 1 ? "s" : "", valStr);
        }
        if (sells > 0) {
            String valStr = sellVal > 100_000 ? String.format(" $%.1fM", sellVal / 1_000_000) : "";
            return String.format("⚠️ %d insider sell%s%s (30d)", sells, sells > 1 ? "s" : "", valStr);
        }
        return null;
    }

    // ── CIK lookup ─────────────────────────────────────────────────────────────

    private synchronized Long getCik(String ticker) throws Exception {
        if (cikMap == null || System.currentTimeMillis() - cikFetchedAt > CIK_TTL_MS) {
            loadCikMap();
        }
        return cikMap != null ? cikMap.get(ticker.toUpperCase()) : null;
    }

    private void loadCikMap() throws Exception {
        String body = get(TICKERS_URL);
        if (body == null) return;
        JsonNode root = mapper.readTree(body);
        Map<String, Long> map = new HashMap<>();
        for (JsonNode entry : root) {
            String tk   = entry.get("ticker").asText().toUpperCase();
            long   cik  = entry.get("cik_str").asLong();
            map.put(tk, cik);
        }
        cikMap      = map;
        cikFetchedAt = System.currentTimeMillis();
        log.info("SEC EDGAR CIK map loaded: {} companies", map.size());
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private String get(String url) {
        try {
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept",     "application/json, text/xml, */*")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                return resp.body().string();
            }
        } catch (Exception e) {
            log.debug("EDGAR request failed {}: {}", url, e.getMessage());
            return null;
        }
    }
}
