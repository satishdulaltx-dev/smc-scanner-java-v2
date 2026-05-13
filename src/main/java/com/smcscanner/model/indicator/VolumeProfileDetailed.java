package com.smcscanner.model.indicator;

/**
 * Extends VolumeProfile with the raw bucket array, enabling HVN/LVN detection.
 *
 * High Volume Node (HVN): bucket vol >= hvnThreshold × VPOC vol — strong S/R
 * Low  Volume Node (LVN): bucket vol <= lvnThreshold × VPOC vol — price accelerates through
 */
public class VolumeProfileDetailed extends VolumeProfile {

    private final double[] buckets;
    private final double   priceBase;   // price at bucket index 0 lower edge
    private final double   bucketSize;
    private final double   vpocVolume;  // highest bucket volume (for threshold comparisons)

    public VolumeProfileDetailed(double vpoc, double vah, double val,
                                  double[] buckets, double priceBase, double bucketSize) {
        super(vpoc, vah, val);
        this.buckets    = buckets.clone();
        this.priceBase  = priceBase;
        this.bucketSize = bucketSize;
        double max = 0;
        for (double v : buckets) if (v > max) max = v;
        this.vpocVolume = max;
    }

    /** Center price of bucket i */
    public double bucketPrice(int i) {
        return priceBase + (i + 0.5) * bucketSize;
    }

    /** Volume in the bucket that contains the given price, or 0 if out of range */
    public double volumeAt(double price) {
        int i = (int) ((price - priceBase) / bucketSize);
        if (i < 0 || i >= buckets.length) return 0;
        return buckets[i];
    }

    /** Bucket index for a given price (-1 if out of range) */
    public int bucketIndex(double price) {
        int i = (int) ((price - priceBase) / bucketSize);
        return (i >= 0 && i < buckets.length) ? i : -1;
    }

    /** Returns price levels of all HVN buckets (vol >= hvnThreshold × vpocVol) */
    public double[] hvnLevels(double hvnThreshold) {
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] >= vpocVolume * hvnThreshold) {
                out.add(bucketPrice(i));
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** True if the bucket at index i qualifies as an HVN */
    public boolean isHvn(int i, double hvnThreshold) {
        return i >= 0 && i < buckets.length && buckets[i] >= vpocVolume * hvnThreshold;
    }

    /** True if the bucket at index i qualifies as an LVN */
    public boolean isLvn(int i, double lvnThreshold) {
        return i >= 0 && i < buckets.length && buckets[i] <= vpocVolume * lvnThreshold;
    }

    public double[] getBuckets()  { return buckets.clone(); }
    public double getPriceBase()  { return priceBase; }
    public double getBucketSize() { return bucketSize; }
    public double getVpocVolume() { return vpocVolume; }
}
