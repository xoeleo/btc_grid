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
import com.btcgrid.strategy.model.ProfitSizingState;
import com.btcgrid.strategy.model.QuantityRoundingState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GridOrderPlannerTest {

    @Test
    void carriesFractionalGridQuantityIntoNextFilledOrder() {
        AtomicReference<QuantityRoundingState> roundingState = new AtomicReference<>(QuantityRoundingState.empty());
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                BigDecimal.ONE,
                null,
                null,
                symbol -> ProfitSizingState.empty(),
                symbol -> roundingState.get()
        );
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.003"),
                new BigDecimal("5"),
                new BigDecimal("0.1"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(15),
                Duration.ZERO
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
        Instant now = Instant.parse("2026-08-23T07:13:49Z");
        MarketSnapshot market = new MarketSnapshot(
                "BTCUSDT",
                now,
                new BigDecimal("100"),
                new IndicatorSnapshot(now, new BigDecimal("100"), new BigDecimal("110"),
                        new BigDecimal("100"), new BigDecimal("90"))
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "BTCUSDT",
                new BigDecimal("0.05"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("99"),
                new BigDecimal("100"),
                TradeSide.SELL,
                market.indicators().zoneOf(new BigDecimal("100")),
                0,
                2,
                InventoryMode.NORMAL,
                now
        );

        OrderPairPlan first = planner.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"),
                new BigDecimal("1.5"), false, "follow-fill"
        );

        assertEquals(new BigDecimal("0.004"), first.sellOrder().qty());
        assertTrue(first.sellOrder().reason().contains(
                "qty-rounding:raw=0.0045,carry=0,rounded=0.004,order=0.004"));

        roundingState.set(new QuantityRoundingState(BigDecimal.ZERO, new BigDecimal("0.0005")));
        OrderPairPlan second = planner.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"),
                new BigDecimal("1.5"), false, "follow-fill"
        );

        assertEquals(new BigDecimal("0.005"), second.sellOrder().qty());
        assertTrue(second.sellOrder().reason().contains(
                "qty-rounding:raw=0.0045,carry=0.0005,rounded=0.005,order=0.005"));
    }

    @Test
    void roundingCarryChangesOnlyInProportionToActualFill() {
        QuantityRoundingState empty = QuantityRoundingState.empty();

        QuantityRoundingState firstFill = empty.afterFill(
                TradeSide.SELL,
                new BigDecimal("0.0045"),
                new BigDecimal("0.004"),
                new BigDecimal("0.004"),
                new BigDecimal("0.004")
        );
        assertEquals(new BigDecimal("0.0005"), firstFill.sellCarryQty());

        QuantityRoundingState consumed = firstFill.afterFill(
                TradeSide.SELL,
                new BigDecimal("0.0045"),
                new BigDecimal("0.005"),
                new BigDecimal("0.005"),
                new BigDecimal("0.005")
        );
        assertEquals(0, consumed.sellCarryQty().compareTo(BigDecimal.ZERO));

        QuantityRoundingState partial = empty.afterFill(
                TradeSide.SELL,
                new BigDecimal("0.0045"),
                new BigDecimal("0.004"),
                new BigDecimal("0.004"),
                new BigDecimal("0.002")
        );
        assertEquals(new BigDecimal("0.00025"), partial.sellCarryQty());

        assertEquals(empty, empty.afterFill(
                TradeSide.SELL,
                new BigDecimal("0.0045"),
                new BigDecimal("0.004"),
                new BigDecimal("0.004"),
                BigDecimal.ZERO
        ));
    }

    @Test
    void anomalouslyCloseConsecutiveFillsCannotShrinkPlacementBelowBaseSpacing() {
        GridOrderPlanner planner = new GridOrderPlanner(new ClientOrderIdFactory());
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.001"),
                new BigDecimal("0.85"),
                new BigDecimal("0.005"),
                new BigDecimal("5"),
                new BigDecimal("0.1"),
                new BigDecimal("0.15"),
                new BigDecimal("0.15"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(15),
                Duration.ZERO
        );
        InstrumentSpec instrument = new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
        Instant now = Instant.parse("2026-08-18T16:46:54Z");
        MarketSnapshot market = new MarketSnapshot(
                "BTCUSDT",
                now,
                new BigDecimal("64811.4"),
                new IndicatorSnapshot(
                        now,
                        new BigDecimal("64811.4"),
                        new BigDecimal("70000"),
                        new BigDecimal("64811.4"),
                        new BigDecimal("60000")
                )
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "BTCUSDT",
                new BigDecimal("0.075"),
                new BigDecimal("61135.334071"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("64787.3"),
                new BigDecimal("64787.9"),
                TradeSide.SELL,
                market.indicators().zoneOf(new BigDecimal("64787.9")),
                0,
                2,
                InventoryMode.NORMAL,
                now
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config,
                instrument,
                market,
                ledger,
                new BigDecimal("64787.9"),
                null,
                false,
                "rebalance-recover-pair"
        );

        assertEquals(new BigDecimal("64852.7"), pair.sellOrder().price());
        assertEquals(new BigDecimal("0.005"), pair.sellOrder().qty());
    }

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
        AtomicReference<BigDecimal> evaluatedOrderQty = new AtomicReference<>();
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (market, ledger, orderPrice, orderQty) -> {
                    evaluatedOrderQty.set(orderQty);
                    return orderPrice.compareTo(new BigDecimal("101")) >= 0;
                }
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
        assertEquals(new BigDecimal("0.011"), evaluatedOrderQty.get());
        assertEquals(new BigDecimal("101.0"), pair.sellOrder().price());
        assertTrue(pair.sellOrder().reason().contains(
                "profit-reduce-boost:base=0.01,order=0.011,intended=0.001,carry=0,applied=0.001"
        ));
    }

    @Test
    void accumulatesSubStepReduceAndReleasesExecutedExtraWhenCycleTurnsToLoss() {
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "SOLUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.5"),
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
                "SOLUSDT",
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("0.01"),
                new BigDecimal("1")
        );
        MarketSnapshot market = new MarketSnapshot(
                "SOLUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("100"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("100"),
                        new BigDecimal("200"),
                        new BigDecimal("100"),
                        new BigDecimal("50")
                )
        );
        StrategyLedgerSnapshot ledger = new StrategyLedgerSnapshot(
                "SOLUSDT",
                new BigDecimal("5"),
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

        GridOrderPlanner firstProfitPlan = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, state, price, qty) -> true,
                (snapshot, state, price, qty) -> false,
                symbol -> ProfitSizingState.empty()
        );
        OrderPairPlan firstPair = firstProfitPlan.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );
        assertEquals(new BigDecimal("0.5"), firstPair.sellOrder().qty());
        assertTrue(firstPair.sellOrder().reason().contains("intended=0.05,carry=0,applied=0"));

        GridOrderPlanner accumulatedProfitPlan = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, state, price, qty) -> true,
                (snapshot, state, price, qty) -> false,
                symbol -> new ProfitSizingState(
                        new BigDecimal("0.05"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )
        );
        OrderPairPlan accumulatedPair = accumulatedProfitPlan.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );
        assertEquals(new BigDecimal("0.6"), accumulatedPair.sellOrder().qty());
        assertTrue(accumulatedPair.sellOrder().reason().contains("intended=0.05,carry=0.05,applied=0.1"));

        GridOrderPlanner lossReleasePlan = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, state, price, qty) -> false,
                (snapshot, state, price, qty) -> true,
                symbol -> new ProfitSizingState(
                        BigDecimal.ZERO,
                        new BigDecimal("0.1"),
                        new BigDecimal("0.05"),
                        BigDecimal.ZERO
                )
        );
        OrderPairPlan lossPair = lossReleasePlan.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );
        assertEquals(new BigDecimal("0.6"), lossPair.buyOrder().qty());
        assertTrue(lossPair.buyOrder().reason().contains(
                "loss-add-release:base=0.5,order=0.6,intended=0.05,carry=0.05,applied=0.1,reserve=0.1"
        ));
    }

    @Test
    void stopsExtraReductionWhenReserveIsHalfOfNetInventory() {
        GridStrategyConfig config = profitSizingConfig();
        InstrumentSpec instrument = profitSizingInstrument();
        MarketSnapshot market = profitSizingMarket();
        StrategyLedgerSnapshot ledger = profitSizingLedger(market, new BigDecimal("5"));
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, state, price, qty) -> true,
                (snapshot, state, price, qty) -> false,
                symbol -> new ProfitSizingState(
                        new BigDecimal("0.1"),
                        new BigDecimal("2.5"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );

        assertEquals(new BigDecimal("0.5"), pair.sellOrder().qty());
        assertFalse(pair.sellOrder().reason().contains("profit-reduce-boost"));
    }

    @Test
    void capsExtraReductionAgainstProjectedPostFillInventory() {
        GridStrategyConfig config = profitSizingConfig();
        InstrumentSpec instrument = profitSizingInstrument();
        MarketSnapshot market = profitSizingMarket();
        StrategyLedgerSnapshot ledger = profitSizingLedger(market, new BigDecimal("5"));
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, state, price, qty) -> true,
                (snapshot, state, price, qty) -> false,
                symbol -> new ProfitSizingState(
                        new BigDecimal("0.2"),
                        new BigDecimal("2.0"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );

        assertEquals(new BigDecimal("0.6"), pair.sellOrder().qty());
        BigDecimal projectedReserve = new BigDecimal("2.1");
        BigDecimal projectedNetQty = new BigDecimal("4.4");
        assertTrue(projectedReserve.compareTo(projectedNetQty.multiply(new BigDecimal("0.5"))) <= 0);
    }

    @Test
    void permanentProfitReductionIsTaggedAndNeverBecomesLossAddReserve() {
        GridStrategyConfig config = profitSizingConfig();
        InstrumentSpec instrument = profitSizingInstrument();
        MarketSnapshot market = profitSizingMarket();
        StrategyLedgerSnapshot ledger = profitSizingLedger(market, new BigDecimal("5"));
        ProfitSizingState state = new ProfitSizingState(
                new BigDecimal("0.05"),
                new BigDecimal("0.2"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("0.1"),
                new BigDecimal("0.3")
        );
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, snapshotLedger, price, qty) -> true,
                (snapshot, snapshotLedger, price, qty) -> true,
                symbol -> state
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );

        assertEquals(new BigDecimal("0.6"), pair.sellOrder().qty());
        assertTrue(pair.sellOrder().reason().contains("permanent=0.1"));
        assertEquals(new BigDecimal("0.1"), state.releasableQty());
        assertEquals(new BigDecimal("0.2"), state.permanentReduceRemainingQty());
    }

    @Test
    void lossAddDoesNotReleasePermanentReduction() {
        GridStrategyConfig config = profitSizingConfig();
        InstrumentSpec instrument = profitSizingInstrument();
        MarketSnapshot market = profitSizingMarket();
        StrategyLedgerSnapshot ledger = profitSizingLedger(market, new BigDecimal("5"));
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal("1.10"),
                (snapshot, snapshotLedger, price, qty) -> false,
                (snapshot, snapshotLedger, price, qty) -> true,
                symbol -> new ProfitSizingState(
                        BigDecimal.ZERO,
                        new BigDecimal("0.3"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("0.3"),
                        new BigDecimal("0.3")
                )
        );

        OrderPairPlan pair = planner.planPairedOrders(
                config, instrument, market, ledger, new BigDecimal("100"), null, false, "follow-fill"
        );

        assertEquals(new BigDecimal("0.5"), pair.buyOrder().qty());
        assertFalse(pair.buyOrder().reason().contains("loss-add-release"));
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

    private GridStrategyConfig profitSizingConfig() {
        return new GridStrategyConfig(
                ProductCategory.LINEAR,
                "SOLUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.5"),
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
    }

    private InstrumentSpec profitSizingInstrument() {
        return new InstrumentSpec(
                "SOLUSDT",
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("0.01"),
                new BigDecimal("1")
        );
    }

    private MarketSnapshot profitSizingMarket() {
        return new MarketSnapshot(
                "SOLUSDT",
                Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("100"),
                new IndicatorSnapshot(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new BigDecimal("100"),
                        new BigDecimal("200"),
                        new BigDecimal("100"),
                        new BigDecimal("50")
                )
        );
    }

    private StrategyLedgerSnapshot profitSizingLedger(MarketSnapshot market, BigDecimal netQty) {
        return new StrategyLedgerSnapshot(
                "SOLUSDT",
                netQty,
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
    }
}
