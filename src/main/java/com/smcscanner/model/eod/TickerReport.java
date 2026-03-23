package com.smcscanner.model.eod;

import com.smcscanner.model.indicator.CorrelationResult;
import com.smcscanner.model.indicator.InsiderActivity;
import com.smcscanner.model.indicator.VolumeProfile;

import java.util.ArrayList;
import java.util.List;

public class TickerReport {
    private String ticker, bias, watchFor, error;
    private String trendSummary;
    private String actionNote;
    private double currentPrice;
    private double atr;
    private List<Level> supportLevels    = new ArrayList<>();
    private List<Level> resistanceLevels = new ArrayList<>();
    private Double nearestSupport, nearestResistance;

    // ── New fields ────────────────────────────────────────────────────────────
    private VolumeProfile    volumeProfile;   // VPOC / VAH / VAL
    private CorrelationResult correlation;    // beta + corr vs SPY / QQQ
    private InsiderActivity  insiderActivity; // SEC Form 4 signal

    private TickerReport(Builder b) {
        this.ticker = b.ticker; this.bias = b.bias; this.watchFor = b.watchFor;
        this.error  = b.error;  this.currentPrice = b.currentPrice; this.atr = b.atr;
        this.trendSummary = b.trendSummary; this.actionNote = b.actionNote;
        this.supportLevels = b.supportLevels; this.resistanceLevels = b.resistanceLevels;
        this.nearestSupport = b.nearestSupport; this.nearestResistance = b.nearestResistance;
        this.volumeProfile  = b.volumeProfile;
        this.correlation    = b.correlation;
        this.insiderActivity = b.insiderActivity;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String          getTicker()            { return ticker; }
    public String          getBias()              { return bias; }
    public String          getWatchFor()          { return watchFor; }
    public String          getError()             { return error; }
    public double          getCurrentPrice()      { return currentPrice; }
    public double          getAtr()               { return atr; }
    public String          getTrendSummary()      { return trendSummary; }
    public String          getActionNote()        { return actionNote; }
    public List<Level>     getSupportLevels()     { return supportLevels; }
    public List<Level>     getResistanceLevels()  { return resistanceLevels; }
    public Double          getNearestSupport()    { return nearestSupport; }
    public Double          getNearestResistance() { return nearestResistance; }
    public VolumeProfile   getVolumeProfile()     { return volumeProfile; }
    public CorrelationResult getCorrelation()     { return correlation; }
    public InsiderActivity getInsiderActivity()   { return insiderActivity; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setSupportLevels(List<Level> v)    { this.supportLevels = v; }
    public void setResistanceLevels(List<Level> v) { this.resistanceLevels = v; }

    public static Builder builder() { return new Builder(); }

    // ── Builder ───────────────────────────────────────────────────────────────
    public static class Builder {
        private String ticker, bias, watchFor, error, trendSummary, actionNote;
        private double currentPrice, atr;
        private List<Level> supportLevels = new ArrayList<>(), resistanceLevels = new ArrayList<>();
        private Double nearestSupport, nearestResistance;
        private VolumeProfile    volumeProfile;
        private CorrelationResult correlation;
        private InsiderActivity  insiderActivity;

        public Builder ticker(String v)                    { this.ticker = v;            return this; }
        public Builder bias(String v)                      { this.bias = v;              return this; }
        public Builder watchFor(String v)                  { this.watchFor = v;          return this; }
        public Builder error(String v)                     { this.error = v;             return this; }
        public Builder currentPrice(double v)              { this.currentPrice = v;      return this; }
        public Builder atr(double v)                       { this.atr = v;               return this; }
        public Builder trendSummary(String v)              { this.trendSummary = v;      return this; }
        public Builder actionNote(String v)                { this.actionNote = v;        return this; }
        public Builder supportLevels(List<Level> v)        { this.supportLevels = v;     return this; }
        public Builder resistanceLevels(List<Level> v)     { this.resistanceLevels = v;  return this; }
        public Builder nearestSupport(Double v)            { this.nearestSupport = v;    return this; }
        public Builder nearestResistance(Double v)         { this.nearestResistance = v; return this; }
        public Builder volumeProfile(VolumeProfile v)      { this.volumeProfile = v;     return this; }
        public Builder correlation(CorrelationResult v)    { this.correlation = v;       return this; }
        public Builder insiderActivity(InsiderActivity v)  { this.insiderActivity = v;   return this; }
        public TickerReport build()                        { return new TickerReport(this); }
    }
}
