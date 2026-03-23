package com.smcscanner.indicator;

import com.smcscanner.model.OHLCV;
import com.smcscanner.model.indicator.VolumeProfile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Computes a session Volume Profile from a list of OHLCV bars.
 *
 * Algorithm:
 *  1. Divide the full price range into BUCKETS equal-width price buckets.
 *  2. For each bar, distribute its volume evenly across every bucket that
 *     its high–low range touches.
 *  3. VPOC  = bucket with the highest accumulated volume.
 *  4. Value Area = expand outward from VPOC until 70 % of total volume
 *     is covered — the upper edge is VAH, the lower edge is VAL.
 */
@Service
public class VolumeProfileCalculator {

    private static final int    BUCKETS    = 60;
    private static final double VALUE_AREA = 0.70;

    public VolumeProfile calculate(List<OHLCV> bars) {
        if (bars == null || bars.size() < 10) return null;

        // ── 1. Price range ────────────────────────────────────────────────────
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double totalVol = 0;
        for (OHLCV b : bars) {
            if (b.getLow()  < lo) lo = b.getLow();
            if (b.getHigh() > hi) hi = b.getHigh();
            totalVol += b.getVolume();
        }
        if (hi <= lo || lo <= 0 || totalVol <= 0) return null;

        double bucketSz = (hi - lo) / BUCKETS;

        // ── 2. Accumulate volume into buckets ─────────────────────────────────
        double[] vol = new double[BUCKETS];
        for (OHLCV b : bars) {
            if (b.getVolume() <= 0) continue;
            int bLo = Math.max(0,         (int) ((b.getLow()  - lo) / bucketSz));
            int bHi = Math.min(BUCKETS-1, (int) ((b.getHigh() - lo) / bucketSz));
            int n   = bHi - bLo + 1;
            double vPer = b.getVolume() / n;
            for (int i = bLo; i <= bHi; i++) vol[i] += vPer;
        }

        // ── 3. VPOC ───────────────────────────────────────────────────────────
        int vpocIdx = 0;
        for (int i = 1; i < BUCKETS; i++) if (vol[i] > vol[vpocIdx]) vpocIdx = i;

        // ── 4. Value Area (expand from VPOC) ──────────────────────────────────
        double target  = totalVol * VALUE_AREA;
        int    aLo     = vpocIdx, aHi = vpocIdx;
        double covered = vol[vpocIdx];

        while (covered < target) {
            double addLo = aLo > 0         ? vol[aLo - 1] : -1;
            double addHi = aHi < BUCKETS-1 ? vol[aHi + 1] : -1;
            if (addLo < 0 && addHi < 0) break;
            if (addLo >= addHi && addLo >= 0) covered += vol[--aLo];
            else                               covered += vol[++aHi];
        }

        double vpoc = r2(lo + (vpocIdx + 0.5) * bucketSz);
        double vah  = r2(lo + (aHi + 1.0)     * bucketSz);
        double val  = r2(lo +  aLo             * bucketSz);

        return new VolumeProfile(vpoc, vah, val);
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
