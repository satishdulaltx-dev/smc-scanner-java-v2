package com.smcscanner.model.indicator;

public class VolumeProfile {
    private final double vpoc;   // Value Point of Control — highest volume price
    private final double vah;    // Value Area High  (70 % of volume above)
    private final double val;    // Value Area Low   (70 % of volume below)

    public VolumeProfile(double vpoc, double vah, double val) {
        this.vpoc = vpoc; this.vah = vah; this.val = val;
    }
    public double getVpoc() { return vpoc; }
    public double getVah()  { return vah;  }
    public double getVal()  { return val;  }
}
