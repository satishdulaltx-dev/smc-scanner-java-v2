package com.smcscanner.vision;

public record VisionVerdict(boolean approve, int score, String reason, boolean failedOpen) {

    public static VisionVerdict failOpen(String reason) {
        return new VisionVerdict(true, 50, "fail-open: " + reason, true);
    }

    public static VisionVerdict noKey() {
        return new VisionVerdict(true, 50, "no API key — vision disabled", true);
    }
}
