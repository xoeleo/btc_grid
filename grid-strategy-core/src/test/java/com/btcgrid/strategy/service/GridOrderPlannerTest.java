package com.btcgrid.strategy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void marketableReduceSideUsesLatestPriceAndRecomputesQtyFromActualDistance() {
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

        assertEquals(new BigDecimal("110.0"), pair.sellOrder().price());
        assertEquals(new BigDecimal("0.100"), pair.sellOrder().qty());
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
    void profitableCycleBoostsReduceSideQtyByConfiguredMultiplier() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory(), new BigDecimal("1.10"));
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
                true,
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
                new BigDecimal("1"),
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

        assertEquals(new BigDecimal("0.110"), pair.sellOrder().qty());
    }

    @Test
    void profitableCycleBoostCanUseCandidateOrderPrice() {
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (market, ledger, orderPrice) -> orderPrice.compareTo(new BigDecimal("101")) >= 0
        );
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
                true,
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
                new BigDecimal("99"),
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

        assertEquals(new BigDecimal("0.011"), pair.sellOrder().qty());
        assertEquals(new BigDecimal("101.0"), pair.sellOrder().price());
        assertTrue(pair.sellOrder().reason().contains("profit-reduce-boost:base=0.01,boosted=0.011"));
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
    void spotLongOnlyInitialOrderAlwaysBuys() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.SPOT,
                "BTCUSDT",
                EntryDirection.SPOT_LONG_ONLY,
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

        assertEquals(TradeSide.BUY, planner.planInitialOrder(config, instrument, aboveUpper).side());
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

    @Test
    void spotLongOnlySellQtyDoesNotExceedInventory() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.SPOT,
                "BTCUSDT",
                EntryDirection.SPOT_LONG_ONLY,
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
                new BigDecimal("0.004"),
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

        assertEquals(new BigDecimal("0.004"), pair.sellOrder().qty());
    }

    @Test
    void spotLongOnlySellQtyIsRoundedDownToQtyStep() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.SPOT,
                "SOLUSDT",
                EntryDirection.SPOT_LONG_ONLY,
                new BigDecimal("0.0016"),
                new BigDecimal("0.85"),
                new BigDecimal("1"),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("300"),
                new BigDecimal("300"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "SOLUSDT",
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                new BigDecimal("0.01")
        );
        MarketSnapshot market = new MarketSnapshot(
                "SOLUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("80.83"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("80"),
                        new BigDecimal("82"),
                        new BigDecimal("80"),
                        new BigDecimal("78")
                )
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "SOLUSDT",
                new BigDecimal("1.06539675"),
                new BigDecimal("80.82"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("80.70"),
                new BigDecimal("80.83"),
                TradeSide.SELL,
                market.indicators().zoneOf(new BigDecimal("80.83")),
                0,
                1,
                InventoryMode.NORMAL,
                market.timestamp()
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config,
                instrument,
                market,
                ledger,
                new BigDecimal("80.83"),
                new BigDecimal("0.14"),
                false,
                "manual-requote"
        );

        assertEquals(new BigDecimal("1.06"), pair.sellOrder().qty());
    }

    @Test
    void marketableAddSideUsesLatestPriceAndRecomputesQtyFromActualDistance() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "SOLUSDT",
                EntryDirection.LONG_ONLY,
                new BigDecimal("0.0016"),
                new BigDecimal("0.85"),
                new BigDecimal("1"),
                new BigDecimal("5"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "SOLUSDT",
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("0.010"),
                new BigDecimal("5")
        );
        MarketSnapshot market = new MarketSnapshot(
                "SOLUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("90.26"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("94.56"),
                        new BigDecimal("99.00"),
                        new BigDecimal("94.56"),
                        new BigDecimal("90.12")
                )
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "SOLUSDT",
                new BigDecimal("8.4"),
                new BigDecimal("90.74"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("90.81"),
                TradeSide.BUY,
                market.indicators().zoneOf(new BigDecimal("90.81")),
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
                new BigDecimal("90.81"),
                null,
                false,
                "follow-fill"
        );

        assertEquals(new BigDecimal("90.260"), pair.buyOrder().price());
        assertEquals(new BigDecimal("3.7"), pair.buyOrder().qty());
    }
}
