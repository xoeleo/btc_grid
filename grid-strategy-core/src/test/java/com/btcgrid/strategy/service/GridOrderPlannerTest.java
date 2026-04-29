package com.btcgrid.strategy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.strategy.model.OrderPairPlan;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GridOrderPlannerTest {

    @Test
    void reduceQtyFallsBackToNormalQtyWhenSmaller() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
        MarketSnapshot market = new MarketSnapshot(
                "BTCUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("110"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("100"),
                        new BigDecimal("200"),
                        new BigDecimal("100"),
                        new BigDecimal("50")
                )
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "BTCUSDT",
                new BigDecimal("0.5"),
                new BigDecimal("95"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                TradeSide.BUY,
                market.indicators().zoneOf(new BigDecimal("100")),
                1,
                0,
                InventoryMode.NORMAL,
                market.timestamp()
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config,
                instrument,
                market,
                ledger,
                new BigDecimal("100"),
                null,
                false,
                "follow-fill"
        );

        assertEquals(new BigDecimal("0.010"), pair.sellOrder().qty());
        assertFalse(pair.sellOrder().reason().contains("reduce-qty"));
    }

    @Test
    void initialOrderUsesConfiguredEntrySteps() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
        MarketSnapshot market = new MarketSnapshot(
                "BTCUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("98.5"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("100"),
                        new BigDecimal("99")
                )
        );

        assertEquals(new BigDecimal("0.050"), planner.planInitialOrder(config, instrument, market).qty());
    }

    @Test
    void initialOrderHonorsEntryDirection() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig longOnlyConfig = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.LONG_ONLY,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
        MarketSnapshot aboveUpper = new MarketSnapshot(
                "BTCUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("111"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("100"),
                        new BigDecimal("90")
                )
        );

        assertNull(planner.planInitialOrder(longOnlyConfig, instrument, aboveUpper));
    }

    @Test
    void addSideQtyIsCappedByRemainingInventoryCapacity() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("0.25"),
                new BigDecimal("0.25"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
        MarketSnapshot market = new MarketSnapshot(
                "BTCUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("100"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("100"),
                        new BigDecimal("100.5"),
                        new BigDecimal("100"),
                        new BigDecimal("99.5")
                )
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "BTCUSDT",
                new BigDecimal("0.248"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100"),
                TradeSide.BUY,
                market.indicators().zoneOf(new BigDecimal("100")),
                1,
                0,
                InventoryMode.NORMAL,
                market.timestamp()
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config,
                instrument,
                market,
                ledger,
                new BigDecimal("100"),
                null,
                false,
                "follow-fill"
        );

        assertEquals(new BigDecimal("0.002"), pair.buyOrder().qty());
    }
}
