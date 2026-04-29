package com.btcgrid.app;

import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskGuardServiceTest {

    @Test
    void blocksCommandWhenNetInventoryWouldExceedLimit() {
        RiskGuardService riskGuardService = new RiskGuardService(config());
        StrategyLedgerSnapshot ledgerSnapshot = ledger(new BigDecimal("1.95"), 0, 0);
        StrategyCommand command = StrategyCommand.place(order("A1", TradeSide.BUY, new BigDecimal("0.10"), false));

        List<RiskDecision> decisions = riskGuardService.evaluate(List.of(command), ledgerSnapshot);

        assertEquals(1, decisions.size());
        assertFalse(decisions.get(0).allowed());
        assertEquals("net-inventory-exceeds-max", decisions.get(0).reason());
    }

    @Test
    void allowsCommandWhenSameSideStreakLimitReached() {
        RiskGuardService riskGuardService = new RiskGuardService(config());
        StrategyLedgerSnapshot ledgerSnapshot = ledger(new BigDecimal("0.50"), 4, 0);
        StrategyCommand command = StrategyCommand.place(order("A2", TradeSide.BUY, new BigDecimal("0.05"), false));

        List<RiskDecision> decisions = riskGuardService.evaluate(List.of(command), ledgerSnapshot);

        assertTrue(decisions.get(0).allowed());
    }

    @Test
    void allowsReduceOnlyOrderEvenWhenExposureIsHigh() {
        RiskGuardService riskGuardService = new RiskGuardService(config());
        StrategyLedgerSnapshot ledgerSnapshot = ledger(new BigDecimal("2.00"), 4, 0);
        StrategyCommand command = StrategyCommand.place(order("A3", TradeSide.SELL, new BigDecimal("0.20"), true));

        List<RiskDecision> decisions = riskGuardService.evaluate(List.of(command), ledgerSnapshot);

        assertTrue(decisions.get(0).allowed());
    }

    private GridStrategyConfig config() {
        return new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("100"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("0.20"),
                new BigDecimal("2.00"),
                new BigDecimal("2.00"),
                4,
                55,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
    }

    private StrategyLedgerSnapshot ledger(BigDecimal netQty, int consecutiveBuyFills, int consecutiveSellFills) {
        return new StrategyLedgerSnapshot(
                "BTCUSDT",
                netQty,
                new BigDecimal("70000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("69900"),
                new BigDecimal("70000"),
                netQty.signum() >= 0 ? TradeSide.BUY : TradeSide.SELL,
                com.btcgrid.common.model.BandZone.INSIDE_BAND,
                consecutiveBuyFills,
                consecutiveSellFills,
                InventoryMode.NORMAL,
                Instant.now()
        );
    }

    private OrderIntent order(String id, TradeSide side, BigDecimal qty, boolean reduceOnly) {
        return new OrderIntent("BTCUSDT", id, side, OrderType.LIMIT, qty, new BigDecimal("70000"), reduceOnly, "test");
    }
}
