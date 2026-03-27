package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * VWAP Mean-Reversion strategy detector for large-cap stable stocks.
 * Looks for price that has deviated significantly from session VWAP and
 * is beginning to revert back toward it.
 */
@Service
public class VwapStrategyDetector {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * Detect VWAP mean-reversion setups for the given bars.
     *
     * @param bars      5-minute OHLCV bars (100+ bars typical)
     * @param ticker    ticker symbol
     * @param dailyAtr  daily ATR from calling service
     * @return list of detected setups (0 or 1 element)
     */
    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.isEmpty()) return result;

        // Filter to regular NYSE session only: same date AND 9:30 AM–4:00 PM ET
        // This excludes pre-market and after-hours bars which skew VWAP calculations
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(Long.parseLong(lastRaw.getTimestamp()))
                .atZone(ET).toLocalDate();
        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);

        List<OHLCV> sessionBars = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(Long.parseLong(bar.getTimestamp())).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                sessionBars.add(bar);
            }
        }

        if (sessionBars.size() < 12) return result;

        // Compute rolling session VWAP: sum(typical_price * volume) / sum(volume)
        double sumTpVol = 0.0;
        double sumVol   = 0.0;
        for (OHLCV bar : sessionBars) {
            double tp = (bar.getHigh() + bar.getLow() + bar.getClose()) / 3.0;
            sumTpVol += tp * bar.getVolume();
            sumVol   += bar.getVolume();
        }
        double vwap = sumVol > 0 ? sumTpVol / sumVol : sessionBars.get(sessionBars.size() - 1).getClose();

        // ── Z-Score: only trade at the extremes (> 2.0 SD from VWAP) ────────
        // AAPL/MSFT spend 70% of the day within 1 SD of VWAP. Entering near the
        // mean is a coin flip. The edge is mean-reversion from the tails only.
        double sumSqDev = 0.0;
        for (OHLCV bar : sessionBars) {
            double dev = bar.getClose() - vwap;
            sumSqDev += dev * dev;
        }
        double stdDev = Math.sqrt(sumSqDev / sessionBars.size());
        double lastClose = sessionBars.get(sessionBars.size() - 1).getClose();
        double zScore = stdDev > 0 ? (lastClose - vwap) / stdDev : 0.0;

        // Compute 14-bar ATR from all (non-session-filtered) bars
        double atr    = computeAtr(bars);
        double curAtr = Math.max(atr, lastClose * 0.002);

        // Determine target ATR for TP sizing
        double targetAtr = (dailyAtr > curAtr * 2) ? dailyAtr : curAtr * 4;

        // Average volume of session bars
        double avgVol = sessionBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        OHLCV last = sessionBars.get(sessionBars.size() - 1);

        // ── Macro trend filter ────────────────────────────────────────────────
        // VWAP reversion LONGs in a clearly bearish trending day have very low
        // win rates — price dips below VWAP, bounces slightly, then continues down.
        // VWAP reversion SHORTs in a clearly bullish trending day have the same problem.
        //
        // Rules:
        //   1. Compute day change % from first session bar open → current close
        //   2. Compute SMA20 direction (declining or rising)
        //   3. trendBearish  = day < -0.8% AND SMA20 declining
        //      trendBullish  = day >  0.8% AND SMA20 rising
        //   4. Hard block LONGs when day < -1.5% (strongly trending down)
        //      Hard block SHORTs when day >  1.5% (strongly trending up)
        //   5. Soft penalty (-15 conf) for moderate bearish/bullish trend vs LONG/SHORT

        double dayOpen      = sessionBars.get(0).getOpen();
        double dayChangePct = dayOpen > 0 ? (lastClose - dayOpen) / dayOpen * 100.0 : 0.0;

        // SMA20 of session closes
        int smaPeriod = Math.min(20, sessionBars.size());
        double sma20 = 0.0;
        for (int i = sessionBars.size() - smaPeriod; i < sessionBars.size(); i++) {
            sma20 += sessionBars.get(i).getClose();
        }
        sma20 /= smaPeriod;

        // SMA20 direction: compare to SMA20 from 5 bars ago
        double sma20Earlier = 0.0;
        boolean hasSmaEarlier = sessionBars.size() >= smaPeriod + 5;
        if (hasSmaEarlier) {
            for (int i = sessionBars.size() - smaPeriod - 5; i < sessionBars.size() - 5; i++) {
                sma20Earlier += sessionBars.get(i).getClose();
            }
            sma20Earlier /= smaPeriod;
        }
        boolean smaDeclining = hasSmaEarlier && sma20 < sma20Earlier;
        boolean smaRising    = hasSmaEarlier && sma20 > sma20Earlier;

        // Composite trend flags
        boolean trendBearish         = dayChangePct < -0.8  && smaDeclining;
        boolean trendStronglyBearish = dayChangePct < -1.5;
        boolean trendBullish         = dayChangePct >  0.8  && smaRising;
        boolean trendStronglyBullish = dayChangePct >  1.5;

        // ── LONG setup ────────────────────────────────────────────────────────
        // Look back 20 bars for lowest close (wider window catches more setups)
        int lookback = Math.min(20, sessionBars.size());
        int startIdx = sessionBars.size() - lookback;
        double lowestClose  = Double.MAX_VALUE;
        double highestClose = Double.MIN_VALUE;
        for (int i = startIdx; i < sessionBars.size(); i++) {
            double c = sessionBars.get(i).getClose();
            if (c < lowestClose)  lowestClose  = c;
            if (c > highestClose) highestClose = c;
        }

        // LONG: price must have dipped meaningfully below VWAP (Z-Score < -1.2)
        // Lowered from 1.5→1.2: 1.5 SD was starving AMZN/SPY (0 signals in 180d).
        // 1.2 SD catches more "rubber band snap" setups while still filtering noise.
        if (lowestClose < vwap - 0.5 * curAtr && zScore < -1.2) {
            // ── Macro trend hard block ─────────────────────────────────────
            // Don't fire LONG if the whole day is strongly trending down.
            // A -1.5%+ day with declining SMA is a downtrend, not a dip to buy.
            if (trendStronglyBearish) {
                // Day is strongly bearish (< -1.5%) — suppress all VWAP LONGs.
                // VWAP reversion in a downtrend catches falling knives, not reversals.
                return result;
            }

            double curClose = last.getClose();
            boolean belowVwap      = curClose < vwap;
            boolean bouncingUp     = curClose > lowestClose + curAtr * 0.2;
            boolean bullishBar     = last.getClose() > last.getOpen();
            boolean volSpike       = last.getVolume() > avgVol * 1.1;
            boolean notFreeFall    = curClose >= vwap - curAtr * 3.0;

            if (belowVwap && bouncingUp && bullishBar && volSpike && notFreeFall) {
                double entry = r4(curClose);
                double slRaw = r4(lowestClose - curAtr * 0.3);
                double sl    = Math.min(slRaw, r4(entry - targetAtr * 0.35));
                // sl must be below entry
                sl = Math.min(sl, r4(entry - curAtr * 0.1));

                double tpRaw = r4(vwap + curAtr * 0.3);
                double tp    = tpRaw > entry * 1.005 ? tpRaw : r4(entry + targetAtr * 0.9);

                int confidence = 65;
                if (last.getVolume() > avgVol * 1.8)             confidence += 5;
                if ((vwap - curClose) > 0.8 * curAtr)            confidence += 5;
                if (last.getVolume() > avgVol * 2.5)             confidence += 5;
                if (zScore < -2.0)                               confidence += 5;  // extreme Z-Score = high-probability reversion
                // ── Soft trend penalty ─────────────────────────────────────
                // Moderate bearish day (-0.8% to -1.5%) with declining SMA:
                // lower confidence by 15 so it needs 80+ to survive filters.
                if (trendBearish) confidence -= 15;

                // Enforce 2:1 R:R minimum — raises TP if VWAP is too close
                double longRisk = entry - sl;
                double longMin2R = r4(entry + longRisk * 2.0);
                if (tp < longMin2R) tp = longMin2R;

                if (sl < entry && tp > entry) {
                    result.add(TradeSetup.builder()
                            .ticker(ticker)
                            .direction("long")
                            .entry(entry)
                            .stopLoss(sl)
                            .takeProfit(tp)
                            .confidence(confidence)
                            .session("NYSE")
                            .volatility("normal")
                            .atr(curAtr)
                            .hasBos(false)
                            .hasChoch(false)
                            .fvgTop(r4(vwap))
                            .fvgBottom(r4(lowestClose))
                            .timestamp(LocalDateTime.now())
                            .build());
                    return result;
                }
            }
        }

        // ── SHORT setup ───────────────────────────────────────────────────────
        // Price must have spiked meaningfully above VWAP (Z-Score > 1.2)
        if (highestClose > vwap + 0.5 * curAtr && zScore > 1.2) {
            // ── Macro trend hard block ─────────────────────────────────────
            // Don't fire SHORT if the whole day is strongly trending up.
            if (trendStronglyBullish) {
                return result;
            }

            double curClose = last.getClose();
            boolean aboveVwap     = curClose > vwap;
            boolean revertingDown  = curClose < highestClose - curAtr * 0.2;
            boolean bearishBar    = last.getClose() < last.getOpen();
            boolean volSpike      = last.getVolume() > avgVol * 1.1;
            boolean notFreeRocket = curClose <= vwap + curAtr * 3.0;

            if (aboveVwap && revertingDown && bearishBar && volSpike && notFreeRocket) {
                double entry = r4(curClose);
                double slRaw = r4(highestClose + curAtr * 0.3);
                double sl    = Math.max(slRaw, r4(entry + targetAtr * 0.35));
                // sl must be above entry
                sl = Math.max(sl, r4(entry + curAtr * 0.1));

                double tpRaw = r4(vwap - curAtr * 0.3);
                double tp    = tpRaw < entry * 0.995 ? tpRaw : r4(entry - targetAtr * 0.9);

                int confidence = 65;
                if (last.getVolume() > avgVol * 1.8)             confidence += 5;
                if ((curClose - vwap) > 0.8 * curAtr)            confidence += 5;
                if (last.getVolume() > avgVol * 2.5)             confidence += 5;
                if (zScore > 2.0)                                confidence += 5;  // extreme Z-Score = high-probability reversion
                // Soft trend penalty for moderate counter-trend setup
                if (trendBullish) confidence -= 15;

                // Enforce 2:1 R:R minimum
                double shortRisk = sl - entry;
                double shortMin2R = r4(entry - shortRisk * 2.0);
                if (tp > shortMin2R) tp = shortMin2R;

                if (sl > entry && tp < entry) {
                    result.add(TradeSetup.builder()
                            .ticker(ticker)
                            .direction("short")
                            .entry(entry)
                            .stopLoss(sl)
                            .takeProfit(tp)
                            .confidence(confidence)
                            .session("NYSE")
                            .volatility("normal")
                            .atr(curAtr)
                            .hasBos(false)
                            .hasChoch(false)
                            .fvgTop(r4(highestClose))
                            .fvgBottom(r4(vwap))
                            .timestamp(LocalDateTime.now())
                            .build());
                }
            }
        }

        return result;
    }

    /** Compute 14-bar simple ATR from the last N bars. */
    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0.0;
        int start = bars.size() - period - 1;
        double sum = 0.0;
        for (int i = start + 1; i < bars.size(); i++) {
            OHLCV cur  = bars.get(i);
            OHLCV prev = bars.get(i - 1);
            double tr = Math.max(cur.getHigh() - cur.getLow(),
                        Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                 Math.abs(cur.getLow()  - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    /** Round to 4 decimal places. */
    private double r4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
