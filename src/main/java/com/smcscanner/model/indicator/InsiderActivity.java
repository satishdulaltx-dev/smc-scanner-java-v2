package com.smcscanner.model.indicator;

public class InsiderActivity {
    private final int    buyCount;
    private final int    sellCount;
    private final double buyValueM;   // total buy value in $millions
    private final double sellValueM;  // total sell value in $millions
    private final String signal;      // formatted emoji string for display

    public InsiderActivity(int buyCount, int sellCount,
                           double buyValueM, double sellValueM, String signal) {
        this.buyCount   = buyCount;   this.sellCount  = sellCount;
        this.buyValueM  = buyValueM;  this.sellValueM = sellValueM;
        this.signal     = signal;
    }

    public int    getBuyCount()   { return buyCount;   }
    public int    getSellCount()  { return sellCount;  }
    public double getBuyValueM()  { return buyValueM;  }
    public double getSellValueM() { return sellValueM; }
    public String getSignal()     { return signal;     }
}
