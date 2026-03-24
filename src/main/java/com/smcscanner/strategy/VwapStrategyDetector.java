package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

        // Filter to today's ET session (same LocalDate as last bar)
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(Long.parseLong(lastRaw.getTimestamp()))
                .atZone(ET).toLocalDate();

        List<OHLCV> sessionBars = new ArrayList<>();
        for (OHLCV bar : bars) {
            LocalDate barDate = Instant.ofEpochMilli(Long.parseLong(bar.getTimestamp()))
                    .atZone(ET).toLocalDate();
            if (barDate.equals(today)) {
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

        // Compute 14-bar ATR from all (non-session-filtered) bars
        double atr    = computeAtr(bars);
        double lastClose = sessionBars.get(sessionBars.size() - 1).getClose();
        double curAtr = Math.max(atr, lastClose * 0.002);

        // Determine target ATR for TP sizing
        double targetAtr = (dailyAtr > curAtr * 2) ? dailyAtr : curAtr * 4;

        // Average volume of session bars
        double avgVol = sessionBars.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        OHLCV last = sessionBars.get(sessionBars.size() - 1);

        // ── LONG setup ────────────────────────────────────────────────────────
        // Look back 15 bars for lowest close
        int lookback = Math.min(15, sessionBars.size());
        int startIdx = sessionBars.size() - lookback;
        double lowestClose  = Double.MAX_VALUE;
        double highestClose = Double.MIN_VALUE;
        for (int i = startIdx; i < sessionBars.size(); i++) {
            double c = sessionBars.get(i).getClose();
            if (c < lowestClose)  lowestClose  = c;
            if (c > highestClose) highestClose = c;
        }

        // LONG: price must have dipped meaningfully below VWAP
        if (lowestClose < vwap - 0.8 * curAtr) {
            double curClose = last.getClose();
            boolean belowVwap      = curClose < vwap;
            boolean bouncingUp     = curClose > lowestClose + curAtr * 0.3;
            boolean bullishBar     = last.getClose() > last.getOpen();
            boolean volSpike       = last.getVolume() > avgVol * 1.3;
            boolean notFreeFall    = curClose >= vwap - curAtr * 2.5;

            if (belowVwap && bouncingUp && bullishBar && volSpike && notFreeFall) {
                double entry = r4(curClose);
                double slRaw = r4(lowestClose - curAtr * 0.3);
                double sl    = Math.min(slRaw, r4(entry - targetAtr * 0.35));
                // sl must be below entry
                sl = Math.min(sl, r4(entry - curAtr * 0.1));

                double tpRaw = r4(vwap + curAtr * 0.3);
                double tp    = tpRaw > entry * 1.005 ? tpRaw : r4(entry + targetAtr * 0.9);

                int confidence = 68;
                if (last.getVolume() > avgVol * 2)               confidence += 5;
                if ((vwap - curClose) > 1.2 * curAtr)            confidence += 5;

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
        // Price must have spiked meaningfully above VWAP
        if (highestClose > vwap + 0.8 * curAtr) {
            double curClose = last.getClose();
            boolean aboveVwap     = curClose > vwap;
            boolean revertingDown  = curClose < highestClose - curAtr * 0.3;
            boolean bearishBar    = last.getClose() < last.getOpen();
            boolean volSpike      = last.getVolume() > avgVol * 1.3;
            boolean notFreeRocket = curClose <= vwap + curAtr * 2.5;

            if (aboveVwap && revertingDown && bearishBar && volSpike && notFreeRocket) {
                double entry = r4(curClose);
                double slRaw = r4(highestClose + curAtr * 0.3);
                double sl    = Math.max(slRaw, r4(entry + targetAtr * 0.35));
                // sl must be above entry
                sl = Math.max(sl, r4(entry + curAtr * 0.1));

                double tpRaw = r4(vwap - curAtr * 0.3);
                double tp    = tpRaw < entry * 0.995 ? tpRaw : r4(entry - targetAtr * 0.9);

                int confidence = 68;
                if (last.getVolume() > avgVol * 2)               confidence += 5;
                if ((curClose - vwap) > 1.2 * curAtr)            confidence += 5;

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
