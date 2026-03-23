package com.smcscanner.model;

public class SMCState {
    private SetupPhase phase     = SetupPhase.IDLE;
    private String     direction = null;
    private int        sweepBar  = -1;
    private int        displacementBar = -1;
    private double     fvgTop    = 0.0;
    private double     fvgBottom = 0.0;
    private int        fvgBar    = -1;
    private int        retestBar = -1;

    public SetupPhase getPhase()           { return phase; }
    public String     getDirection()       { return direction; }
    public int        getSweepBar()        { return sweepBar; }
    public int        getDisplacementBar() { return displacementBar; }
    public double     getFvgTop()          { return fvgTop; }
    public double     getFvgBottom()       { return fvgBottom; }
    public int        getFvgBar()          { return fvgBar; }
    public int        getRetestBar()       { return retestBar; }

    public void setPhase(SetupPhase v)          { this.phase = v; }
    public void setDirection(String v)          { this.direction = v; }
    public void setSweepBar(int v)              { this.sweepBar = v; }
    public void setDisplacementBar(int v)       { this.displacementBar = v; }
    public void setFvgTop(double v)             { this.fvgTop = v; }
    public void setFvgBottom(double v)          { this.fvgBottom = v; }
    public void setFvgBar(int v)                { this.fvgBar = v; }
    public void setRetestBar(int v)             { this.retestBar = v; }

    public boolean isComplete()  { return phase == SetupPhase.RETEST_DETECTED; }
    public String  phaseMsg()    { return phase.phaseMsg(); }
}
