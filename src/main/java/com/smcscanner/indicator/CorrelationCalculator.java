package com.smcscanner.indicator;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.indicator.CorrelationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Computes rolling beta and Pearson correlation of a stock vs SPY and QQQ.
 *
 * Beta  = Cov(stock, benchmark) / Var(benchmark)
 * Corr  = Cov(stock, benchmark) / (Std(stock) * Std(benchmark))
 *
 * Both use simple daily log-returns aligned by index (most recent N bars).
 */
@Service
public class CorrelationCalculator {
    private static final Logger log = LoggerFactory.getLogger(CorrelationCalculator.class);
    private static final int MIN_BARS = 15;

    /** Returns null if there is insufficient data for a meaningful result. */
    public CorrelationResult calculate(List<OHLCV> tickerBars,
                                        List<OHLCV> spyBars,
                                        List<OHLCV> qqqBars) {
        try {
            if (tickerBars == null || tickerBars.size() < MIN_BARS + 1) return null;
            double[] tr = returns(tickerBars);

            double betaSpy = 0, corrSpy = 0, betaQqq = 0, corrQqq = 0;

            if (spyBars != null && spyBars.size() > MIN_BARS) {
                double[] br = returns(spyBars);
                int n = Math.min(tr.length, br.length);
                if (n >= MIN_BARS) {
                    double[] t = tail(tr, n), b = tail(br, n);
                    betaSpy = r2(beta(t, b));
                    corrSpy = r2(correlation(t, b));
                }
            }
            if (qqqBars != null && qqqBars.size() > MIN_BARS) {
                double[] br = returns(qqqBars);
                int n = Math.min(tr.length, br.length);
                if (n >= MIN_BARS) {
                    double[] t = tail(tr, n), b = tail(br, n);
                    betaQqq = r2(beta(t, b));
                    corrQqq = r2(correlation(t, b));
                }
            }
            return new CorrelationResult(betaSpy, corrSpy, betaQqq, corrQqq);
        } catch (Exception e) {
            log.debug("Correlation error: {}", e.getMessage());
            return null;
        }
    }

    // ── Math ──────────────────────────────────────────────────────────────────

    private double[] returns(List<OHLCV> bars) {
        double[] r = new double[bars.size() - 1];
        for (int i = 1; i < bars.size(); i++) {
            double prev = bars.get(i-1).getClose();
            r[i-1] = prev > 0 ? (bars.get(i).getClose() - prev) / prev : 0;
        }
        return r;
    }

    private double[] tail(double[] a, int n) {
        double[] out = new double[n];
        System.arraycopy(a, a.length - n, out, 0, n);
        return out;
    }

    private double mean(double[] a) {
        double s = 0; for (double v : a) s += v; return s / a.length;
    }

    private double variance(double[] a) {
        double m = mean(a), s = 0;
        for (double v : a) s += (v-m)*(v-m);
        return s / a.length;
    }

    private double stddev(double[] a) { return Math.sqrt(variance(a)); }

    private double covariance(double[] a, double[] b) {
        double ma = mean(a), mb = mean(b), s = 0;
        for (int i = 0; i < a.length; i++) s += (a[i]-ma)*(b[i]-mb);
        return s / a.length;
    }

    private double beta(double[] stock, double[] bench) {
        double var = variance(bench);
        return var > 0 ? covariance(stock, bench) / var : 0;
    }

    private double correlation(double[] a, double[] b) {
        double sa = stddev(a), sb = stddev(b);
        return sa > 0 && sb > 0 ? covariance(a, b) / (sa * sb) : 0;
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
