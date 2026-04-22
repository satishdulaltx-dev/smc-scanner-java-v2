package com.smcscanner.strategy;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.TradeSetup;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Index Divergence strategy: AAPL vs SPY catch-up trade.
 *
 * When SPY makes a meaningful directional move (≥ 0.3% in 30 min) but AAPL
 * has not followed (< 0.1%), trade AAPL in the direction of SPY's move.
 * The divergence window is typically 30–90 minutes.
 *
 * AAPL rationale: AAPL represents ~7% of SPY. On trending market days, AAPL
 * eventually follows the index. The lag creates a predictable catch-up trade.
 * Conversely, when SPY drops but AAPL holds, AAPL will eventually be dragged down.
 */
@Service
public class IndexDivergenceDetector {

    private static final ZoneId  ET            = ZoneId.of("America/New_York");
    private static final int     LOOKBACK      = 6;    // 30 minutes (6 × 5m bars)
    private static final double  SPY_MIN_MOVE  = 0.003; // SPY must move ≥ 0.3%
    private static final double  AAPL_MAX_LAG  = 0.001; // AAPL lags behind (< 0.1% move)

    /**
     * @param aaplBars  AAPL 5m bars (current window)
     * @param spyBars   SPY 5m bars for same session
     * @param ticker    ticker (typically AAPL)
     * @param dailyAtr  daily ATR for TP sizing
     */
    public List<TradeSetup> detect(List<OHLCV> aaplBars, List<OHLCV> spyBars,
                                    String ticker, double dailyAtr) {
        List<TradeSetup> result = new ArrayList<>();
        if (aaplBars == null || aaplBars.size() < LOOKBACK + 5) return result;
        if (spyBars  == null || spyBars.size()  < LOOKBACK + 2) return result;

        LocalTime mktOpen  = LocalTime.of(9, 30);
        LocalTime mktClose = LocalTime.of(16, 0);
        LocalDate today    = Instant.ofEpochMilli(
                aaplBars.get(aaplBars.size() - 1).getTimestamp()).atZone(ET).toLocalDate();

        // Filter AAPL to today's session
        List<OHLCV> aaplSess = new ArrayList<>();
        for (OHLCV bar : aaplBars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                aaplSess.add(bar);
            }
        }
        if (aaplSess.size() < LOOKBACK + 3) return result;

        // Filter SPY to today's session
        List<OHLCV> spySess = new ArrayList<>();
        for (OHLCV bar : spyBars) {
            ZonedDateTime zdt = Instant.ofEpochMilli(bar.getTimestamp()).atZone(ET);
            if (zdt.toLocalDate().equals(today)
                    && !zdt.toLocalTime().isBefore(mktOpen)
                    && zdt.toLocalTime().isBefore(mktClose)) {
                spySess.add(bar);
            }
        }
        if (spySess.size() < LOOKBACK + 2) return result;

        // 30-min return for AAPL and SPY
        int aaplEnd  = aaplSess.size() - 1;
        int aaplBase = Math.max(0, aaplEnd - LOOKBACK);
        double aaplClose  = aaplSess.get(aaplEnd).getClose();
        double aaplPrev   = aaplSess.get(aaplBase).getClose();
        double aaplReturn = aaplPrev > 0 ? (aaplClose - aaplPrev) / aaplPrev : 0;

        int spyEnd  = spySess.size() - 1;
        int spyBase = Math.max(0, spyEnd - LOOKBACK);
        double spyClose  = spySess.get(spyEnd).getClose();
        double spyPrev   = spySess.get(spyBase).getClose();
        double spyReturn = spyPrev > 0 ? (spyClose - spyPrev) / spyPrev : 0;

        double divergence = spyReturn - aaplReturn; // positive = SPY leading up, AAPL lagging

        double atr5m = computeAtr(aaplSess);
        double atr   = Math.max(atr5m, aaplClose * 0.001);
        double effAtr = (dailyAtr > atr * 3) ? dailyAtr : atr * 8;
        double avgVol = aaplSess.stream().mapToDouble(OHLCV::getVolume).average().orElse(1.0);

        OHLCV aaplLast = aaplSess.get(aaplEnd);

        // LONG: SPY moved up strongly, AAPL lagging
        if (spyReturn > SPY_MIN_MOVE && aaplReturn < AAPL_MAX_LAG) {
            // AAPL must show early sign of catching up (not still falling)
            boolean turning = aaplLast.getClose() >= aaplSess.get(Math.max(0, aaplEnd - 1)).getClose() * 0.999;
            boolean volOk   = aaplLast.getVolume() > avgVol * 0.9;

            if (turning && volOk) {
                double entry = r4(aaplClose);
                double sl    = r4(entry - atr * 0.5);
                double risk  = entry - sl;
                // TP: AAPL catching up to SPY's equivalent move (80% beta approximation)
                double catchUp = Math.abs(spyReturn) * aaplClose * 0.8;
                double tp    = r4(entry + Math.max(risk * 2.0, catchUp));

                int conf = 65;
                if (Math.abs(divergence) > 0.006) conf += 5;
                if (Math.abs(divergence) > 0.010) conf += 5;
                if (aaplLast.getVolume() > avgVol * 1.5) conf += 5;

                if (sl < entry && tp > entry) {
                    result.add(TradeSetup.builder()
                            .ticker(ticker).direction("long")
                            .entry(entry).stopLoss(sl).takeProfit(tp)
                            .confidence(conf).session("NYSE").volatility("idiv")
                            .atr(atr).hasBos(false).hasChoch(false)
                            .fvgTop(r4(aaplClose + atr)).fvgBottom(r4(aaplClose - atr))
                            .timestamp(Instant.ofEpochMilli(aaplLast.getTimestamp()).atZone(ET).toLocalDateTime()).build());
                }
            }
        }

        // SHORT: SPY down strongly, AAPL held up (will be dragged down)
        if (spyReturn < -SPY_MIN_MOVE && aaplReturn > -AAPL_MAX_LAG && result.isEmpty()) {
            boolean turning = aaplLast.getClose() <= aaplSess.get(Math.max(0, aaplEnd - 1)).getClose() * 1.001;
            boolean volOk   = aaplLast.getVolume() > avgVol * 0.9;

            if (turning && volOk) {
                double entry = r4(aaplClose);
                double sl    = r4(entry + atr * 0.5);
                double risk  = sl - entry;
                double catchUp = Math.abs(spyReturn) * aaplClose * 0.8;
                double tp    = r4(entry - Math.max(risk * 2.0, catchUp));

                int conf = 65;
                if (Math.abs(divergence) > 0.006) conf += 5;
                if (Math.abs(divergence) > 0.010) conf += 5;
                if (aaplLast.getVolume() > avgVol * 1.5) conf += 5;

                if (sl > entry && tp < entry) {
                    result.add(TradeSetup.builder()
                            .ticker(ticker).direction("short")
                            .entry(entry).stopLoss(sl).takeProfit(tp)
                            .confidence(conf).session("NYSE").volatility("idiv")
                            .atr(atr).hasBos(false).hasChoch(false)
                            .fvgTop(r4(aaplClose + atr)).fvgBottom(r4(aaplClose - atr))
                            .timestamp(Instant.ofEpochMilli(aaplLast.getTimestamp()).atZone(ET).toLocalDateTime()).build());
                }
            }
        }
        return result;
    }

    private double computeAtr(List<OHLCV> bars) {
        int period = Math.min(14, bars.size() - 1);
        if (period <= 0) return 0.001;
        int start = bars.size() - period - 1;
        double sum = 0;
        for (int i = start + 1; i < bars.size(); i++) {
            OHLCV c = bars.get(i), p = bars.get(i - 1);
            double tr = Math.max(c.getHigh() - c.getLow(),
                        Math.max(Math.abs(c.getHigh() - p.getClose()),
                                 Math.abs(c.getLow()  - p.getClose())));
            sum += tr;
        }
        return sum / period;
    }

    private double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
