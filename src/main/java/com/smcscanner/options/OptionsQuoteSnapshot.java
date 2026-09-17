package com.smcscanner.options;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable executable option quote and liquidity context captured at one instant. */
public record OptionsQuoteSnapshot(
        String contractTicker,double bid,double ask,double bidSize,double askSize,
        long quoteTimestampMs,String quoteTimeframe,long volume,long openInterest,
        double iv,double delta,double gamma,double theta,double vega,int sharesPerContract) {

    public double spreadFraction(){double mid=(bid+ask)/2.0;return mid>0?(ask-bid)/mid:0;}

    public Map<String,Object> toMap(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("contract",contractTicker);out.put("bid",bid);out.put("ask",ask);
        out.put("bidSize",bidSize);out.put("askSize",askSize);out.put("spreadFraction",spreadFraction());
        out.put("quoteTimestampMs",quoteTimestampMs);out.put("quoteTimeframe",quoteTimeframe);
        out.put("volume",volume);out.put("openInterest",openInterest);out.put("iv",iv);
        out.put("delta",delta);out.put("gamma",gamma);out.put("theta",theta);out.put("vega",vega);
        out.put("sharesPerContract",sharesPerContract);return Map.copyOf(out);
    }
}
