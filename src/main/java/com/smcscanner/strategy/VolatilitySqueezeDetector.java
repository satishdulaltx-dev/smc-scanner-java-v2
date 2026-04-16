package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Volatility Squeeze strategy: Bollinger Bands + Keltner Channel.
 *
 * When BB compresses inside KC (squeeze ON), volatility is coiling.
 * When BB expands back outside KC (squeeze release), trade the breakout direction.
 *
 * AAPL rationale: AAPL oscillates in tight ranges for hours/days before sharp
 * directional moves. The BB/KC squeeze is a reliable precursor.
 */
@Service
public class VolatilitySqueezeDetector {

    private static final ZoneId  ET          = ZoneId.of("America/New_York");
    private static final int     PERIOD      = 20;
    private static final double  BB_MULT     = 2.0;
    private static final double  KC_MULT     = 1.5;
    private static final int     MIN_SQUEEZE = 3; // must have been squeezed ≥ 3 bars

    public List<TradeSetup> detect(List<OHLCV> bars, String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (bars == null || bars.size() < PERIOD + 5) return result;

        // Filter to regular NYSE session only (9:30–16:00 ET)
        OHLCV lastRaw = bars.get(bars.size() - 1);
        LocalDate today = Instant.ofEpochMilli(lastRaw.getTimestamp())
                .atZone(ET).toLocalDate();
        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);

        List<OHLCV> sb = new ArrayList<>();
        for (OHLCV bar : bars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                sb.add(bar);
            }
        }
        if (sb.size() < PERIOD + 5) return result;

        int n = sb.size();
        OHLCV last     = sb.get(n - 1);
        double curClose = last.getClose();

        double atr5m = computeAtrAt(sb, n - 1);
        double atr   = Math.max(atr5m, curClose * 0.001);
        double effAtr = (dailyAtr > atr * 3) ? dailyAtr : atr * 8;
        double avgVol = sb.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        // Current bar must NOT be in squeeze (squeeze just released)
        if (isSqueeze(sb, n - 1, atr)) return result;

        // Count consecutive preceding bars that WERE in squeeze
        int squeezeCount = 0;
        for (int i = n - 2; i >= Math.max(0, n - 15); i--) {
            double atrI = computeAtrAt(sb, i);
            if (isSqueeze(sb, i, atrI)) squeezeCount++;
            else break;
        }
        if (squeezeCount < MIN_SQUEEZE) return result;

        double[] bb = computeBB(sb, n - 1);
        double bbMid   = bb[0];
        double bbUpper = bb[1];
        double bbLower = bb[2];

        // Direction: close above midband = bullish breakout, below = bearish
        boolean longDir  = curClose > bbMid;
        boolean shortDir = curClose < bbMid;

        // Momentum: last 3 bars moving in same direction
        boolean longMom  = false;
        boolean shortMom = false;
        if (n >= 4) {
            double mom = curClose - sb.get(n - 4).getClose();
            longMom  = mom >  atr * 0.15;
            shortMom = mom < -atr * 0.15;
        }

        boolean volSpike = last.getVolume() > avgVol * 1.3;

        if (longDir && longMom && volSpike) {
            double entry = r4(curClose);
            double sl    = r4(Math.min(bbLower, entry - atr * 0.5));
            double risk  = entry - sl;
            double tp    = r4(entry + Math.max(risk * 2.0, effAtr > 0 ? effAtr * 0.8 : risk * 2.0));

            int conf = 65;
            if (last.getVolume() > avgVol * 1.8) conf += 5;
            if (squeezeCount >= 5)              conf += 5;
            if (squeezeCount >= 8)              conf += 5;

            if (sl < entry && tp > entry) {
                String factors = String.format(
                        "vsqueeze-long | squeeze=%d bars | BB=[%.2f/%.2f] mid=%.2f" +
                        " | breakout=UP | vol=%.1f×avg",
                        squeezeCount, bbLower, bbUpper, bbMid,
                        last.getVolume() / Math.max(avgVol, 1));
                result.add(TradeSetup.builder()
                        .ticker(ticker).direction("long")
                        .entry(entry).stopLoss(sl).takeProfit(tp)
                        .confidence(conf).session("NYSE").volatility("squeeze")
                        .atr(atr).hasBos(false).hasChoch(false)
                        .fvgTop(r4(bbUpper)).fvgBottom(r4(bbLower))
                        .factorBreakdown(factors)
                        .timestamp(LocalDateTime.now()).build());
            }
        } else if (shortDir && shortMom && volSpike) {
            double entry = r4(curClose);
            double sl    = r4(Math.max(bbUpper, entry + atr * 0.5));
            double risk  = sl - entry;
            double tp    = r4(entry - Math.max(risk * 2.0, effAtr > 0 ? effAtr * 0.8 : risk * 2.0));

            int conf = 65;
            if (last.getVolume() > avgVol * 1.8) conf += 5;
            if (squeezeCount >= 5)              conf += 5;
            if (squeezeCount >= 8)              conf += 5;

            if (sl > entry && tp < entry) {
                String factors = String.format(
                        "vsqueeze-short | squeeze=%d bars | BB=[%.2f/%.2f] mid=%.2f" +
                        " | breakout=DOWN | vol=%.1f×avg",
                        squeezeCount, bbLower, bbUpper, bbMid,
                        last.getVolume() / Math.max(avgVol, 1));
                result.add(TradeSetup.builder()
                        .ticker(ticker).direction("short")
                        .entry(entry).stopLoss(sl).takeProfit(tp)
                        .confidence(conf).session("NYSE").volatility("squeeze")
                        .atr(atr).hasBos(false).hasChoch(false)
                        .fvgTop(r4(bbUpper)).fvgBottom(r4(bbLower))
                        .factorBreakdown(factors)
                        .timestamp(LocalDateTime.now()).build());
            }
        }
        return result;
    }

    private boolean isSqueeze(List<OHLCV> bars, int idx, double localAtr) {
        if (idx < PERIOD) return false;
        double[] bb = computeBB(bars, idx);
        double[] kc = computeKC(bars, idx, localAtr);
        return bb[1] < kc[1] && bb[2] > kc[2];
    }

    /** Bollinger Bands: [mid, upper, lower] */
    private double[] computeBB(List<OHLCV> bars, int idx) {
        int start = Math.max(0, idx - PERIOD + 1);
        double sum = 0; int cnt = 0;
        for (int i = start; i <= idx; i++) { sum += bars.get(i).getClose(); cnt++; }
        double mid = sum / cnt;
        double var = 0;
        for (int i = start; i <= idx; i++) { double d = bars.get(i).getClose() - mid; var += d * d; }
        double sd = Math.sqrt(var / cnt);
        return new double[]{mid, mid + BB_MULT * sd, mid - BB_MULT * sd};
    }

    /** Keltner Channel: [mid, upper, lower] using SMA as EMA proxy */
    private double[] computeKC(List<OHLCV> bars, int idx, double localAtr) {
        int start = Math.max(0, idx - PERIOD + 1);
        double sum = 0; int cnt = 0;
        for (int i = start; i <= idx; i++) { sum += bars.get(i).getClose(); cnt++; }
        double ema = sum / cnt;
        double a = localAtr > 0 ? localAtr : (bars.get(idx).getHigh() - bars.get(idx).getLow());
        return new double[]{ema, ema + KC_MULT * a, ema - KC_MULT * a};
    }

    private double computeAtrAt(List<OHLCV> bars, int idx) {
        int period = Math.min(14, idx);
        if (period <= 0) return 0.001;
        double sum = 0;
        for (int i = idx - period + 1; i <= idx; i++) {
            OHLCV cur = bars.get(i), prev = bars.get(i - 1);
            double tr = Math.max(cur.getHigh() - cur.getLow(),
                        Math.max(Math.abs(cur.getHigh() - prev.getClose()),
                                 Math.abs(cur.getLow()  - prev.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    private double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
