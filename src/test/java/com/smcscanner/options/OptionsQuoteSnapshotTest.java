package com.smcscanner.options;

import com.smcscanner.model.TradeSetup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OptionsQuoteSnapshotTest {
    @Test
    void tradeSetupRetainsTheExecutableQuoteAndLiquidityContext() {
        var quote=new OptionsQuoteSnapshot("O:SPY260918C00700000",2.10,2.16,34,28,
                1_800_000_000_000L,"REAL-TIME",825,4_200,.24,.45,.03,-.08,.12,100);
        var setup=TradeSetup.builder().ticker("SPY").direction("long").entry(700).stopLoss(697)
                .takeProfit(706).optionsContract(quote.contractTicker()).optionsPremium(quote.ask())
                .optionsQuoteSnapshot(quote).build();

        assertEquals(quote,setup.getOptionsQuoteSnapshot());
        @SuppressWarnings("unchecked") Map<String,Object> stored=(Map<String,Object>)setup.toMap().get("options_quote");
        assertNotNull(stored);
        assertEquals(2.10,(double)stored.get("bid"),1e-9);
        assertEquals(2.16,(double)stored.get("ask"),1e-9);
        assertEquals(4_200L,stored.get("openInterest"));
    }
}
