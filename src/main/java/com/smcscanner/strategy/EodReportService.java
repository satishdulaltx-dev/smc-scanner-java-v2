package com.smcscanner.strategy;

import com.smcscanner.data.BenchmarkCache;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.data.SecEdgarClient;
import com.smcscanner.indicator.AtrCalculator;
import com.smcscanner.indicator.CorrelationCalculator;
import com.smcscanner.indicator.VolumeProfileCalculator;
import com.smcscanner.model.*;
import com.smcscanner.model.eod.Level;
import com.smcscanner.model.eod.TickerReport;
import com.smcscanner.model.indicator.CorrelationResult;
import com.smcscanner.model.indicator.InsiderActivity;
import com.smcscanner.model.indicator.VolumeProfile;
import com.smcscanner.smc.FvgAnalyzer;
import com.smcscanner.smc.OrderBlockAnalyzer;
import com.smcscanner.smc.StructureAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class EodReportService {
    private static final Logger log = LoggerFactory.getLogger(EodReportService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final double IMMEDIATE_ATR = 1.0;
    private static final double NEAR_ATR      = 2.5;

    private final PolygonClient          client;
    private final AtrCalculator          atrCalc;
    private final StructureAnalyzer      sa;
    private final FvgAnalyzer            fvgAnalyzer;
    private final OrderBlockAnalyzer     obAnalyzer;
    private final VolumeProfileCalculator vpCalc;
    private final CorrelationCalculator  corrCalc;
    private final BenchmarkCache         benchCache;
    private final SecEdgarClient         edgarClient;

    public EodReportService(PolygonClient client, AtrCalculator atrCalc,
                             StructureAnalyzer sa, FvgAnalyzer fvgAnalyzer,
                             OrderBlockAnalyzer obAnalyzer,
                             VolumeProfileCalculator vpCalc,
                             CorrelationCalculator corrCalc,
                             BenchmarkCache benchCache,
                             SecEdgarClient edgarClient) {
        this.client = client; this.atrCalc = atrCalc; this.sa = sa;
        this.fvgAnalyzer = fvgAnalyzer; this.obAnalyzer = obAnalyzer;
        this.vpCalc = vpCalc; this.corrCalc = corrCalc;
        this.benchCache = benchCache; this.edgarClient = edgarClient;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public List<TickerReport> generateReport(List<String> tickers) {
        log.info("EOD report: analysing {} tickers...", tickers.size());

        // Pre-fetch benchmarks once (cached 4 h, reused across all tickers)
        List<OHLCV> spyBars = benchCache.getOrFetch("SPY", () -> client.getBars("SPY", "1d", 90));
        List<OHLCV> qqqBars = benchCache.getOrFetch("QQQ", () -> client.getBars("QQQ", "1d", 90));

        List<TickerReport> reports = new ArrayList<>();
        for (String ticker : tickers) {
            try {
                // 15m bars with 10-day lookback for SMC structure (FVG/OB/swing detection).
                // getBarsWithLookback bypasses cache so we always get fresh data at EOD.
                List<OHLCV> bars = client.getBarsWithLookback(ticker, "15m", 500, 10);
                if (bars == null || bars.size() < 30) bars = client.getBars(ticker, "1d", 90);

                TickerReport r = analyseTicker(ticker, bars, spyBars, qqqBars);
                log.info("EOD  {}  bias={}  price=${}  watch={}",
                        String.format("%-8s", ticker), r.getBias(),
                        String.format("%.2f", r.getCurrentPrice()), r.getWatchFor());
                reports.add(r);
                Thread.sleep(300);
            } catch (Exception e) {
                log.warn("EOD error {}: {}", ticker, e.getMessage());
                reports.add(TickerReport.builder().ticker(ticker).bias("neutral")
                        .watchFor("WAIT").currentPrice(0.0).error(e.getMessage()).build());
            }
        }
        log.info("EOD report complete: {} tickers", reports.size());
        return reports;
    }

    // ── Per-ticker Deep Analysis ───────────────────────────────────────────────

    private TickerReport analyseTicker(String ticker, List<OHLCV> bars,
                                        List<OHLCV> spyBars, List<OHLCV> qqqBars) {
        if (bars == null || bars.size() < 20) {
            return TickerReport.builder().ticker(ticker).bias("neutral")
                    .watchFor("WAIT").currentPrice(0.0).error("Insufficient data").build();
        }

        // Always fetch daily bars first — use their last close as the authoritative
        // EOD price. The 15m bars passed in are used only for structure analysis.
        // Using 15m last bar for price is unreliable (1-bar latency, delayed feed).
        List<OHLCV> dailyBars = client.getBars(ticker, "1d", 90);
        if (dailyBars == null || dailyBars.size() < 10) dailyBars = bars;

        double price    = dailyBars.get(dailyBars.size()-1).getClose();
        double[] atrArr = atrCalc.computeAtr(dailyBars, 14);
        double   atrVal = lastNz(atrArr);
        if (atrVal == 0) atrVal = price * 0.01;

        // ── Collect raw levels ─────────────────────────────────────────────────
        List<double[]> rawSup = new ArrayList<>();   // [price, weight]
        List<double[]> rawRes = new ArrayList<>();

        collectSwingLevels(bars, price, rawSup, rawRes);
        collectFvgLevels(bars, price, rawSup, rawRes);
        collectObLevels(bars, price, rawSup, rawRes);
        collectVpLevels(dailyBars, price, rawSup, rawRes);  // ← NEW

        // ── Merge, sort, build Level objects ──────────────────────────────────
        List<double[]> mergedSup = merge(rawSup, price, 0.003);
        List<double[]> mergedRes = merge(rawRes, price, 0.003);
        mergedSup.sort((a, b) -> Double.compare(price-a[0], price-b[0]));
        mergedRes.sort((a, b) -> Double.compare(a[0]-price, b[0]-price));

        List<Level> supLevels = buildLevels(mergedSup, price, atrVal, false);
        List<Level> resLevels = buildLevels(mergedRes, price, atrVal, true);
        if (supLevels.size() > 5) supLevels = supLevels.subList(0, 5);
        if (resLevels.size() > 5) resLevels = resLevels.subList(0, 5);

        Double nearSup = supLevels.isEmpty() ? null : supLevels.get(0).getPrice();
        Double nearRes = resLevels.isEmpty() ? null : resLevels.get(0).getPrice();

        // ── Bias + trend ───────────────────────────────────────────────────────
        String bias         = detectBias(bars);
        String trendSummary = buildTrendSummary(bars, bias, atrVal, price);

        boolean atSup = nearSup != null && (price - nearSup) <= IMMEDIATE_ATR * atrVal;
        boolean atRes = nearRes != null && (nearRes - price) <= IMMEDIATE_ATR * atrVal;

        String watchFor   = buildWatchFor(bias, atSup, atRes, nearSup, nearRes, price);
        String actionNote = buildActionNote(bias, atSup, atRes, nearSup, nearRes,
                                            supLevels, resLevels, price, atrVal);

        // ── Volume Profile ─────────────────────────────────────────────────────
        VolumeProfile vp = vpCalc.calculate(dailyBars);

        // ── Correlation vs SPY / QQQ (skip crypto) ────────────────────────────
        CorrelationResult corr = null;
        if (!ticker.startsWith("X:")) {
            corr = corrCalc.calculate(dailyBars, spyBars, qqqBars);
        }

        // ── Insider Activity (SEC EDGAR, skip crypto / indices) ───────────────
        InsiderActivity insider = null;
        if (!ticker.startsWith("X:") && !ticker.startsWith("I:")) {
            insider = edgarClient.getActivity(ticker, 30);
        }

        return TickerReport.builder()
                .ticker(ticker).bias(bias).watchFor(watchFor).actionNote(actionNote)
                .trendSummary(trendSummary).currentPrice(price).atr(atrVal)
                .supportLevels(supLevels).resistanceLevels(resLevels)
                .nearestSupport(nearSup).nearestResistance(nearRes)
                .volumeProfile(vp)
                .correlation(corr)
                .insiderActivity(insider)
                .build();
    }

    // ── Level Collection ───────────────────────────────────────────────────────

    private void collectSwingLevels(List<OHLCV> bars, double price,
                                     List<double[]> sup, List<double[]> res) {
        try {
            List<SwingPoint> swings = sa.detectSwings(bars, 5);
            int start = Math.max(0, swings.size()-40);
            for (SwingPoint s : swings.subList(start, swings.size())) {
                if (s.getSwingType() == SwingType.LOW  && s.getPrice() < price * 0.995)
                    sup.add(new double[]{s.getPrice(), 1.0});
                if (s.getSwingType() == SwingType.HIGH && s.getPrice() > price * 1.005)
                    res.add(new double[]{s.getPrice(), 1.0});
            }
        } catch (Exception e) { log.debug("Swing error: {}", e.getMessage()); }
    }

    private void collectFvgLevels(List<OHLCV> bars, double price,
                                   List<double[]> sup, List<double[]> res) {
        try {
            for (FairValueGap fvg : fvgAnalyzer.detectFvg(bars)) {
                if (fvg.isFilled()) continue;
                double mid = fvg.midpoint();
                if ("bullish".equals(fvg.getDirection()) && mid < price) sup.add(new double[]{mid, 2.0});
                if ("bearish".equals(fvg.getDirection()) && mid > price) res.add(new double[]{mid, 2.0});
            }
        } catch (Exception e) { log.debug("FVG error: {}", e.getMessage()); }
    }

    private void collectObLevels(List<OHLCV> bars, double price,
                                  List<double[]> sup, List<double[]> res) {
        try {
            for (OrderBlock ob : obAnalyzer.detectOrderBlocks(bars)) {
                if (ob.isMitigated()) continue;
                double mid = ob.midpoint();
                if ("bullish".equals(ob.getDirection()) && mid < price) sup.add(new double[]{mid, 2.5});
                if ("bearish".equals(ob.getDirection()) && mid > price) res.add(new double[]{mid, 2.5});
            }
        } catch (Exception e) { log.debug("OB error: {}", e.getMessage()); }
    }

    /** Add VPOC, VAH, VAL as high-weight levels (weight 3.0 = highest priority). */
    private void collectVpLevels(List<OHLCV> dailyBars, double price,
                                  List<double[]> sup, List<double[]> res) {
        try {
            VolumeProfile vp = vpCalc.calculate(dailyBars);
            if (vp == null) return;
            // VPOC: acts as magnet — put it on the side it's on
            if (vp.getVpoc() < price * 0.998) sup.add(new double[]{vp.getVpoc(), 3.0});
            else if (vp.getVpoc() > price * 1.002) res.add(new double[]{vp.getVpoc(), 3.0});
            // VAH = high-volume resistance above price
            if (vp.getVah() > price * 1.002) res.add(new double[]{vp.getVah(), 2.8});
            // VAL = high-volume support below price
            if (vp.getVal() < price * 0.998) sup.add(new double[]{vp.getVal(), 2.8});
        } catch (Exception e) { log.debug("VP level error: {}", e.getMessage()); }
    }

    // ── Level Merging + Building ───────────────────────────────────────────────

    private List<double[]> merge(List<double[]> raw, double price, double tolPct) {
        List<double[]> merged = new ArrayList<>();
        for (double[] lvl : raw) {
            boolean added = false;
            for (double[] m : merged) {
                if (Math.abs(m[0]-lvl[0]) / price <= tolPct) {
                    m[0] = (m[0]*m[1] + lvl[0]*lvl[1]) / (m[1]+lvl[1]);
                    m[1] = m[1]+lvl[1];
                    added = true; break;
                }
            }
            if (!added) merged.add(new double[]{lvl[0], lvl[1]});
        }
        return merged;
    }

    private List<Level> buildLevels(List<double[]> raw, double price, double atr, boolean isRes) {
        List<Level> levels = new ArrayList<>();
        for (double[] r : raw) {
            double lvlPrice = r[0];
            double weight   = r[1];
            double distPts  = isRes ? lvlPrice-price : price-lvlPrice;
            double distPct  = price > 0 ? distPts/price*100 : 0;
            double distAtr  = atr > 0 ? distPts/atr : 0;

            String strength = distAtr <= IMMEDIATE_ATR ? "⚡ IMMEDIATE"
                            : distAtr <= NEAR_ATR      ? "🔶 NEAR"
                            :                            "👁 WATCH";

            String label = weight >= 3.0 ? (isRes ? "VP Level"  : "VP Level")
                         : weight >= 2.5 ? (isRes ? "OB Supply" : "OB Demand")
                         : weight >= 2.0 ? (isRes ? "FVG Supply": "FVG Demand")
                         :                 (isRes ? "Swing High": "Swing Low");

            levels.add(Level.builder()
                    .price(Math.round(lvlPrice*100.0)/100.0)
                    .label(label).strength(strength)
                    .distancePts(Math.round(distPts*100.0)/100.0)
                    .distancePct(Math.round(distPct*100.0)/100.0)
                    .build());
        }
        return levels;
    }

    // ── Bias + Trend Summary ───────────────────────────────────────────────────

    private String detectBias(List<OHLCV> bars) {
        try {
            List<SwingPoint> sw = sa.detectSwings(bars, 5);
            List<StructureBreak> br = sa.detectStructureBreaks(bars, sw);
            int n = bars.size();
            double c1 = bars.get(n-1).getClose();
            double c5 = bars.get(Math.max(0,n-5)).getClose();
            double c20= bars.get(Math.max(0,n-20)).getClose();
            if (c1 > c5 && c5 > c20) return "bullish";
            if (c1 < c5 && c5 < c20) return "bearish";
            if (!br.isEmpty()) {
                StructureBreak last = br.get(br.size()-1);
                if (last.getBreakType() == StructureType.CHOCH) return c1 > c5 ? "bullish" : "bearish";
            }
            return "neutral";
        } catch (Exception e) { return "neutral"; }
    }

    private String buildTrendSummary(List<OHLCV> bars, String bias, double atr, double price) {
        int n = bars.size();
        double c1  = bars.get(n-1).getClose();
        double c10 = bars.get(Math.max(0,n-10)).getClose();
        double c20 = bars.get(Math.max(0,n-20)).getClose();
        double chg = c10 > 0 ? (c1-c10)/c10*100 : 0;
        String dir  = c1>c10 && c10>c20 ? "Higher highs & lows ↗"
                    : c1<c10 && c10<c20 ? "Lower highs & lows ↘"
                    :                     "Choppy / ranging ↔";
        String vol  = atr/price*100 < 0.5 ? "low vol" : atr/price*100 < 1.5 ? "normal vol" : "HIGH vol";
        return String.format("%s  |  %+.1f%% last 10 bars  |  ATR $%.2f (%s)", dir, chg, atr, vol);
    }

    // ── Watch + Action Notes ───────────────────────────────────────────────────

    private String buildWatchFor(String bias, boolean atSup, boolean atRes,
                                  Double nearSup, Double nearRes, double price) {
        if ("bullish".equals(bias)) {
            if (atSup)         return String.format("LONG setup — at demand $%.2f", nearSup);
            if (atRes)         return String.format("Watch: resistance at $%.2f — may reject", nearRes);
            if (nearSup!=null) return String.format("Bullish — pullback to $%.2f = LONG zone", nearSup);
        } else if ("bearish".equals(bias)) {
            if (atRes)         return String.format("SHORT setup — at supply $%.2f", nearRes);
            if (atSup)         return String.format("Watch: support at $%.2f — may bounce", nearSup);
            if (nearRes!=null) return String.format("Bearish — rally to $%.2f = SHORT zone", nearRes);
        }
        if (atSup && nearSup!=null) return String.format("Watch LONG — approaching support $%.2f", nearSup);
        if (atRes && nearRes!=null) return String.format("Watch SHORT — approaching resistance $%.2f", nearRes);
        return nearSup!=null && nearRes!=null
                ? String.format("Range $%.2f – $%.2f, wait for edge", nearSup, nearRes)
                : "No clear level nearby — wait";
    }

    private String buildActionNote(String bias, boolean atSup, boolean atRes,
                                    Double nearSup, Double nearRes,
                                    List<Level> supLevels, List<Level> resLevels,
                                    double price, double atr) {
        StringBuilder sb = new StringBuilder();
        if ("bullish".equals(bias) && atSup && nearSup != null) {
            double stop   = Math.round((nearSup - atr*0.5)*100)/100.0;
            double target = nearRes != null ? nearRes : Math.round((price+atr*3)*100)/100.0;
            sb.append(String.format("🎯 LONG if $%.2f holds  |  Stop ~$%.2f  |  Target ~$%.2f", nearSup, stop, target));
        } else if ("bearish".equals(bias) && atRes && nearRes != null) {
            double stop   = Math.round((nearRes + atr*0.5)*100)/100.0;
            double target = nearSup != null ? nearSup : Math.round((price-atr*3)*100)/100.0;
            sb.append(String.format("🎯 SHORT if $%.2f rejects  |  Stop ~$%.2f  |  Target ~$%.2f", nearRes, stop, target));
        } else if (nearSup != null && nearRes != null) {
            double longStop  = Math.round((nearSup - atr*0.5)*100)/100.0;
            double shortStop = Math.round((nearRes + atr*0.5)*100)/100.0;
            sb.append(String.format("⬆️ LONG if $%.2f holds → target $%.2f (Stop $%.2f)%n", nearSup, nearRes, longStop));
            sb.append(String.format("⬇️ SHORT if $%.2f rejects → target $%.2f (Stop $%.2f)", nearRes, nearSup, shortStop));
        } else if (nearSup != null) {
            sb.append(String.format("⬆️ LONG if $%.2f holds  |  Stop ~$%.2f",
                    nearSup, Math.round((nearSup-atr*0.5)*100)/100.0));
        } else if (nearRes != null) {
            sb.append(String.format("⬇️ SHORT if $%.2f rejects  |  Stop ~$%.2f",
                    nearRes, Math.round((nearRes+atr*0.5)*100)/100.0));
        } else {
            sb.append("⏳ No clear setup — wait for price to approach a key level");
        }
        return sb.toString().trim();
    }

    // ── Text Report ────────────────────────────────────────────────────────────

    public String formatTextReport(List<TickerReport> reports) {
        String dateStr = ZonedDateTime.now(ET)
                .format(DateTimeFormatter.ofPattern("EEEE MMM dd, yyyy  HH:mm 'ET'"));
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(72)).append("\n");
        sb.append("  EOD WATCHLIST REPORT — ").append(dateStr).append("\n");
        sb.append("=".repeat(72)).append("\n\n");
        appendGroup(sb, "📈 BULLISH", reports, "bullish");
        appendGroup(sb, "📉 BEARISH", reports, "bearish");
        appendGroup(sb, "⚪ NEUTRAL", reports, "neutral");
        sb.append("=".repeat(72)).append("\n");
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String heading,
                              List<TickerReport> reps, String bias) {
        List<TickerReport> g = reps.stream()
                .filter(r -> bias.equals(r.getBias()) && r.getError()==null).toList();
        if (g.isEmpty()) return;
        sb.append(heading).append("\n").append("-".repeat(64)).append("\n");
        for (TickerReport r : g) {
            sb.append(String.format("  %-8s $%8.2f  %s%n",
                    r.getTicker(), r.getCurrentPrice(), r.getWatchFor()));
            if (r.getTrendSummary()!=null)
                sb.append(String.format("  %s%n", r.getTrendSummary()));
            if (r.getActionNote()!=null)
                sb.append(String.format("  %s%n", r.getActionNote()));
            // Volume Profile
            if (r.getVolumeProfile()!=null) {
                VolumeProfile vp = r.getVolumeProfile();
                sb.append(String.format("  📊 VPOC $%.2f  |  VAH $%.2f  |  VAL $%.2f%n",
                        vp.getVpoc(), vp.getVah(), vp.getVal()));
            }
            // Correlation
            if (r.getCorrelation()!=null) {
                CorrelationResult c = r.getCorrelation();
                sb.append(String.format("  📈 SPY β=%.2f ρ=%.2f  |  QQQ β=%.2f ρ=%.2f%n",
                        c.getBetaSpy(), c.getCorrSpy(), c.getBetaQqq(), c.getCorrQqq()));
            }
            // Insider
            if (r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null)
                sb.append(String.format("  🏛 %s%n", r.getInsiderActivity().getSignal()));
            // Key levels
            r.getSupportLevels().stream().limit(2).forEach(l ->
                sb.append(String.format("    ↑ SUP  $%.2f  (-%.2f pts / -%.1f%%)  %s  %s%n",
                        l.getPrice(), l.getDistancePts(), l.getDistancePct(),
                        l.getLabel(), l.getStrength())));
            r.getResistanceLevels().stream().limit(2).forEach(l ->
                sb.append(String.format("    ↓ RES  $%.2f  (+%.2f pts / +%.1f%%)  %s  %s%n",
                        l.getPrice(), l.getDistancePts(), l.getDistancePct(),
                        l.getLabel(), l.getStrength())));
            sb.append("\n");
        }
    }

    // ── Discord Formatting ─────────────────────────────────────────────────────

    public List<Map<String,Object>> formatDiscordEmbeds(List<TickerReport> reports) {
        List<TickerReport> good    = reports.stream().filter(r->r.getError()==null && r.getCurrentPrice()>0).toList();
        List<TickerReport> bullish = good.stream().filter(r->"bullish".equals(r.getBias())).toList();
        List<TickerReport> bearish = good.stream().filter(r->"bearish".equals(r.getBias())).toList();
        List<TickerReport> neutral = good.stream().filter(r->"neutral".equals(r.getBias())).toList();

        String now    = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("HH:mm 'ET'"));
        Map<String,Object> footer = Map.of("text","SD Scanner  |  EOD Report  |  "+now);
        List<Map<String,Object>> embeds = new ArrayList<>();

        // ── Summary embed ──────────────────────────────────────────────────────
        List<TickerReport> actionable = good.stream()
                .filter(r->r.getActionNote()!=null && !r.getActionNote().startsWith("⏳")).toList();
        StringBuilder sumDesc = new StringBuilder();
        sumDesc.append(String.format(
                "🟢 **%d bullish** | 🔴 **%d bearish** | ⚪ **%d neutral**\n\n**Actionable setups:**\n",
                bullish.size(), bearish.size(), neutral.size()));
        if (actionable.isEmpty()) {
            sumDesc.append("_No tickers at key levels — wait for next session._");
        } else {
            actionable.stream().limit(8).forEach(r -> {
                String icon = "bullish".equals(r.getBias()) ? "🟢" : ("bearish".equals(r.getBias()) ? "🔴" : "⚪");
                String ins  = r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null
                              ? "  " + r.getInsiderActivity().getSignal() : "";
                sumDesc.append(String.format("%s **%s** `$%.2f`  —  %s%s\n",
                        icon, r.getTicker(), r.getCurrentPrice(), r.getWatchFor(), ins));
            });
        }
        embeds.add(Map.of("title","📋 EOD Watchlist Report",
                          "description", sumDesc.toString(),
                          "color", 0x3498DB, "footer", footer));

        // ── Per-ticker embeds (bullish + bearish, max 20) ───────────────────────
        List<TickerReport> toShow = new ArrayList<>(bullish);
        toShow.addAll(bearish);
        toShow = toShow.stream().limit(20).toList();

        for (TickerReport r : toShow) {
            int    color = "bullish".equals(r.getBias()) ? 0x00C853 : 0xFF1744;
            String icon  = "bullish".equals(r.getBias()) ? "📈" : "📉";
            List<Map<String,Object>> fields = new ArrayList<>();

            // Price / bias / ATR
            fields.add(f("Current Price", String.format("**$%.2f**", r.getCurrentPrice()), true));
            fields.add(f("Bias", "bullish".equals(r.getBias()) ? "🟢 Bullish" : "🔴 Bearish", true));
            fields.add(f("ATR", String.format("$%.2f", r.getAtr()), true));

            // Trend
            if (r.getTrendSummary()!=null)
                fields.add(f("Trend", r.getTrendSummary(), false));

            // Volume Profile
            if (r.getVolumeProfile()!=null) {
                VolumeProfile vp = r.getVolumeProfile();
                fields.add(f("📊 Volume Profile",
                        String.format("VPOC `$%.2f`  |  VAH `$%.2f`  |  VAL `$%.2f`",
                                vp.getVpoc(), vp.getVah(), vp.getVal()), false));
            }

            // Correlation
            if (r.getCorrelation()!=null) {
                CorrelationResult c = r.getCorrelation();
                fields.add(f("📈 SPY / QQQ Beta",
                        String.format("SPY β `%.2f` ρ `%.2f`  |  QQQ β `%.2f` ρ `%.2f`",
                                c.getBetaSpy(), c.getCorrSpy(), c.getBetaQqq(), c.getCorrQqq()), false));
            }

            // Support levels
            if (!r.getSupportLevels().isEmpty()) {
                StringBuilder supStr = new StringBuilder();
                r.getSupportLevels().stream().limit(3).forEach(l ->
                    supStr.append(String.format("`$%.2f` -%.1f%% %s %s\n",
                            l.getPrice(), l.getDistancePct(), l.getStrength(), l.getLabel())));
                fields.add(f("⬆️ Support Levels", supStr.toString().trim(), true));
            }

            // Resistance levels
            if (!r.getResistanceLevels().isEmpty()) {
                StringBuilder resStr = new StringBuilder();
                r.getResistanceLevels().stream().limit(3).forEach(l ->
                    resStr.append(String.format("`$%.2f` +%.1f%% %s %s\n",
                            l.getPrice(), l.getDistancePct(), l.getStrength(), l.getLabel())));
                fields.add(f("⬇️ Resistance Levels", resStr.toString().trim(), true));
            }

            // Action note
            if (r.getActionNote()!=null)
                fields.add(f("🎯 Action for Tomorrow", r.getActionNote(), false));

            // Insider signal
            if (r.getInsiderActivity()!=null && r.getInsiderActivity().getSignal()!=null)
                fields.add(f("🏛 Insider Activity", r.getInsiderActivity().getSignal(), true));

            embeds.add(Map.of(
                    "title",  icon + " " + r.getTicker() + " — " + r.getWatchFor(),
                    "color",  color,
                    "fields", fields,
                    "footer", footer));
        }

        // ── Neutral compact summary ────────────────────────────────────────────
        if (!neutral.isEmpty()) {
            StringBuilder nd = new StringBuilder();
            neutral.stream()
                    .filter(r->r.getNearestSupport()!=null || r.getNearestResistance()!=null)
                    .forEach(r -> nd.append(String.format("⚪ **%s** `$%.2f`  S `%s`  R `%s`\n",
                            r.getTicker(), r.getCurrentPrice(),
                            r.getNearestSupport()!=null?String.format("$%.2f",r.getNearestSupport()):"—",
                            r.getNearestResistance()!=null?String.format("$%.2f",r.getNearestResistance()):"—")));
            if (!nd.isEmpty())
                embeds.add(Map.of("title","⚪ Neutral — Key Levels",
                                  "color", 0x95A5A6,
                                  "description", nd.toString(), "footer", footer));
        }
        return embeds;
    }

    private Map<String,Object> f(String name, String value, boolean inline) {
        return Map.of("name", name, "value", value.isBlank()?"—":value, "inline", inline);
    }

    private double lastNz(double[] a) {
        for (int i=a.length-1;i>=0;i--) if(a[i]>0) return a[i];
        return 0;
    }
}
