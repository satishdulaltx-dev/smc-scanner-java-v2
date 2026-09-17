package com.smcscanner.alert;

import com.smcscanner.config.ScannerConfig;
import com.smcscanner.model.TradeSetup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualOptionsAlertTest {
    @Test void manualAlertDoesNotInventQuantityOrExecutableExitPrices() throws Exception {
        var setup=TradeSetup.builder().ticker("TEST").direction("long").entry(100).stopLoss(99).takeProfit(102)
                .confidence(90).volatility("scalp").factorBreakdown("pdhpdl-breakout-retest-long | level=100")
                .optionsContract("TEST-CONTRACT").optionsType("call").optionsExpiry("2026-12-18")
                .optionsPremium(1.05).optionsStrike(100).optionsBreakEven(101.05).optionsSuggested(0)
                .optionsProfitPer(50).optionsLossPer(25).build();
        var service=new DiscordAlertService(new ScannerConfig(),null);
        var method=DiscordAlertService.class.getDeclaredMethod("buildEmbed",TradeSetup.class);method.setAccessible(true);
        String rendered=method.invoke(service,setup).toString();
        assertTrue(rendered.contains("Prior-day breakout / retest"));assertTrue(rendered.contains("Choose quantity manually"));
        assertTrue(rendered.contains("Expiration break-even"));assertTrue(rendered.contains("Model-only"));
        assertFalse(rendered.contains("Broker Bracket Orders"));assertFalse(rendered.contains("SELL TP"));
        assertFalse(rendered.contains("0 contracts"));assertFalse(rendered.contains("Move SL"));
    }
}
