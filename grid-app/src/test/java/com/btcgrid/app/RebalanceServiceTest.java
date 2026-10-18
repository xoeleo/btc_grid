package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderSubmission;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.common.indicator.IndicatorCalculator;
import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.strategy.model.ProfitSizingState;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebalanceServiceTest {

    @Test
    void splitsFundingFeeReconciliationIntoBybitSevenDayWindows() {
        Instant start = Instant.parse("2026-07-21T08:03:39Z");
        Instant end = start.plus(Duration.ofDays(8)).plusSeconds(30);

        List<RebalanceLiveRunner.FundingFeeQueryWindow> windows =
                RebalanceLiveRunner.fundingFeeQueryWindows(start, end);

        assertEquals(2, windows.size());
        assertEquals(start, windows.get(0).startInclusive());
        assertEquals(Duration.ofDays(7).minusMillis(1), Duration.between(
                windows.get(0).startInclusive(),
                windows.get(0).endInclusive()
        ));
        assertEquals(windows.get(0).endInclusive().plusMillis(1), windows.get(1).startInclusive());
        assertEquals(end, windows.get(1).endInclusive());
    }

    @Test
    void placesBuyOrderWhenBtcRatioIsBelowPriceBandTarget() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(
                                new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.70")),
                                new RebalanceBand(new BigDecimal("100000"), new BigDecimal("0.50"))
                        ),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.010"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("110000")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("0.30"),
                        new BigDecimal("67000"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.30"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );

        service.onPoll();

        assertEquals(1, tradeGateway.submitted.size());
        OrderIntent order = tradeGateway.submitted.get(0);
        assertTrue(order.clientOrderId().startsWith(RebalanceService.CLIENT_ORDER_PREFIX));
        assertEquals(TradeSide.BUY, order.side());
        assertEquals(new BigDecimal("0.050"), order.qty());
        assertTrue(order.isMarket());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertEquals(new BigDecimal("0.50000000"), saved.targetRatio().setScale(8));
        Files.deleteIfExists(stateFile);
    }

    @Test
    void doesNotPlaceAnotherInitialOrderWhileRebalanceCycleIsActive() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        new FileRebalanceStateStore(stateFile).save(new RebalanceState(
                true,
                "BTCUSDT",
                "GRID_ACTIVE",
                new BigDecimal("0.75"),
                new BigDecimal("0.73"),
                new BigDecimal("0.30"),
                BigDecimal.ZERO,
                new BigDecimal("0.30"),
                new BigDecimal("100000"),
                new BigDecimal("65000"),
                null,
                null,
                null,
                null,
                List.of(new BigDecimal("0.73")),
                Instant.now(),
                "rebalance grid running"
        ));
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(
                                new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.80")),
                                new RebalanceBand(new BigDecimal("59000"), new BigDecimal("0.75")),
                                new RebalanceBand(new BigDecimal("65000"), new BigDecimal("0.70"))
                        ),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("64990")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("0.30"),
                        new BigDecimal("67500"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.30"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );

        service.onPoll();

        assertEquals(0, tradeGateway.submitted.size());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_ACTIVE", saved.status());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void activeCycleUsesLockedTargetRatioWhenPriceMovesToAnotherBand() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        new FileRebalanceStateStore(stateFile).save(new RebalanceState(
                true,
                "BTCUSDT",
                "GRID_ACTIVE",
                new BigDecimal("0.70"),
                new BigDecimal("0.735"),
                new BigDecimal("1.13251156"),
                BigDecimal.ZERO,
                new BigDecimal("1.13251156"),
                new BigDecimal("100000"),
                new BigDecimal("65100"),
                TradeSide.SELL,
                null,
                null,
                null,
                List.of(new BigDecimal("0.735")),
                Instant.now(),
                "rebalance grid running"
        ));
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(
                                new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.80")),
                                new RebalanceBand(new BigDecimal("59000"), new BigDecimal("0.75")),
                                new RebalanceBand(new BigDecimal("65000"), new BigDecimal("0.70"))
                        ),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("64900")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("1.13251156"),
                        new BigDecimal("26499.99964"),
                        BigDecimal.ZERO,
                        new BigDecimal("1.13251156"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );

        service.onPoll();

        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_ACTIVE", saved.status());
        assertEquals(new BigDecimal("0.70"), saved.targetRatio());
        assertEquals(0, tradeGateway.submitted.size());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void endedCycleDoesNotReuseLockedTargetForNextCycleBandSelection() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        new FileRebalanceStateStore(stateFile).save(new RebalanceState(
                true,
                "BTCUSDT",
                "IDLE",
                new BigDecimal("0.70"),
                new BigDecimal("0.752"),
                new BigDecimal("1.0"),
                BigDecimal.ZERO,
                new BigDecimal("1.0"),
                new BigDecimal("100000"),
                new BigDecimal("64001"),
                null,
                null,
                null,
                null,
                List.of(new BigDecimal("0.752")),
                Instant.now(),
                "rebalance grid position is flat; stopped"
        ));
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(
                                new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.80")),
                                new RebalanceBand(new BigDecimal("59000"), new BigDecimal("0.75")),
                                new RebalanceBand(new BigDecimal("64000"), new BigDecimal("0.70"))
                        ),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("63991.7")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("1.1955"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("1.1955"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );

        service.onPoll();

        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertEquals(new BigDecimal("0.75"), saved.targetRatio());
        assertEquals(TradeSide.SELL, tradeGateway.submitted.get(0).side());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void shortCycleCrossingFlatDoesNotEndAsTargetReached() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        List<String> cycleEndReasons = new ArrayList<>();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(
                                new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.80")),
                                new RebalanceBand(new BigDecimal("59000"), new BigDecimal("0.75")),
                                new RebalanceBand(new BigDecimal("65000"), new BigDecimal("0.70"))
                        ),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("64762.9")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("1.137"),
                        new BigDecimal("0.002"),
                        BigDecimal.ZERO,
                        new BigDecimal("1.139"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger()),
                cycleEndReasons::add
        );
        service.restoreActiveCycle(
                List.of(
                        new FillRecord(
                                Instant.now().minusSeconds(60),
                                "RBAL:BTCUSDT",
                                "SELL",
                                new BigDecimal("0.003"),
                                new BigDecimal("65102.7"),
                                new BigDecimal("195.3081"),
                                BigDecimal.ZERO,
                                "USDT",
                                new BigDecimal("-0.003"),
                                new BigDecimal("-195.3081"),
                                BigDecimal.ZERO,
                                new BigDecimal("-0.003"),
                                new BigDecimal("65102.7"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "RBAL_BTCUSDT_I_test",
                                "initial-short"
                        ),
                        new FillRecord(
                                Instant.now().minusSeconds(10),
                                "RBAL:BTCUSDT",
                                "BUY",
                                new BigDecimal("0.005"),
                                new BigDecimal("64762.9"),
                                new BigDecimal("323.8145"),
                                BigDecimal.ZERO,
                                "USDT",
                                new BigDecimal("0.002"),
                                new BigDecimal("75.8833"),
                                BigDecimal.ZERO,
                                new BigDecimal("0.002"),
                                new BigDecimal("37941.65"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "RBAL_BTCUSDT_PB_test",
                                "flat-cross-buy"
                        )
                ),
                List.of()
        );

        service.onPoll();

        assertEquals(List.of("rebalance grid position crossed zero; stopped"), cycleEndReasons);
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("IDLE", saved.status());
        assertEquals("rebalance grid position crossed zero; stopped", saved.message());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void restoredCycleUsesImmutableFillTargetInsteadOfDriftedStateTarget() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        new FileRebalanceStateStore(stateFile).save(new RebalanceState(
                true,
                "BTCUSDT",
                "GRID_ACTIVE",
                new BigDecimal("0.75"),
                new BigDecimal("0.745"),
                new BigDecimal("1.10"),
                new BigDecimal("0.025"),
                new BigDecimal("1.125"),
                new BigDecimal("100000"),
                new BigDecimal("65000"),
                TradeSide.BUY,
                null,
                null,
                null,
                List.of(new BigDecimal("0.745")),
                Instant.now(),
                "rebalance grid running"
        ));
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        List<String> cycleEndReasons = new ArrayList<>();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.75"))),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("65000")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("1.10"),
                        new BigDecimal("0.06923077"),
                        BigDecimal.ZERO,
                        new BigDecimal("1.16923077"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger()),
                cycleEndReasons::add
        );
        service.restoreActiveCycle(
                List.of(new FillRecord(
                        Instant.now().minusSeconds(30),
                        "RBAL:BTCUSDT",
                        "BUY",
                        new BigDecimal("0.025"),
                        new BigDecimal("64774.4"),
                        new BigDecimal("1619.36"),
                        BigDecimal.ZERO,
                        "USDT",
                        new BigDecimal("0.025"),
                        new BigDecimal("1619.36"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.025"),
                        new BigDecimal("64774.4"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RBAL_BTCUSDT_I_test",
                        "initial-long",
                        "{\"targetRatio\":1}"
                )),
                List.of()
        );

        service.onPoll();

        assertTrue(cycleEndReasons.isEmpty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_ACTIVE", saved.status());
        assertEquals(BigDecimal.ONE, saved.targetRatio());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void recoveredCycleClosingFillStopsImmediatelyAndCancelsActiveOrders() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        List<String> cycleEndReasons = new ArrayList<>();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.75"))),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("64694.3")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("1.10"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("1.10"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger()),
                cycleEndReasons::add
        );
        service.restoreActiveCycle(
                List.of(new FillRecord(
                        Instant.now().minusSeconds(60),
                        "RBAL:BTCUSDT",
                        "BUY",
                        new BigDecimal("0.025"),
                        new BigDecimal("64127.9"),
                        new BigDecimal("1603.1975"),
                        BigDecimal.ZERO,
                        "USDT",
                        new BigDecimal("0.025"),
                        new BigDecimal("1603.1975"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.025"),
                        new BigDecimal("64127.9"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RBAL_BTCUSDT_I_test",
                        "initial-long"
                )),
                List.of(
                        new WorkingOrder(
                                "BTCUSDT",
                                "RBAL_BTCUSDT_PB_test",
                                "buy-exchange",
                                TradeSide.BUY,
                                OrderType.LIMIT,
                                new BigDecimal("64063.8"),
                                new BigDecimal("0.005"),
                                false,
                                OrderStatus.NEW,
                                Instant.now(),
                                Instant.now()
                        ),
                        new WorkingOrder(
                                "BTCUSDT",
                                "RBAL_BTCUSDT_PS_test",
                                "sell-exchange",
                                TradeSide.SELL,
                                OrderType.LIMIT,
                                new BigDecimal("64694.3"),
                                new BigDecimal("0.025"),
                                false,
                                OrderStatus.NEW,
                                Instant.now(),
                                Instant.now()
                        )
                )
        );

        service.onExecution(new FillEvent(
                "closing-sell",
                "BTCUSDT",
                "RBAL_BTCUSDT_PS_test",
                TradeSide.SELL,
                new BigDecimal("0.025"),
                new BigDecimal("64694.3"),
                Instant.now()
        ), "test");

        RebalanceState afterFill = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("IDLE", afterFill.status());
        assertEquals(List.of("rebalance grid position is flat; stopped"), cycleEndReasons);
        assertEquals(List.of("RBAL_BTCUSDT_PB_test"), tradeGateway.canceled);

        registry.markCanceled("RBAL_BTCUSDT_PB_test", Instant.now());
        service.onOrderUpdate(
                "BTCUSDT",
                "RBAL_BTCUSDT_PB_test",
                OrderStatus.CANCELED,
                Instant.now()
        );

        RebalanceState afterLateCancel = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("IDLE", afterLateCancel.status());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void duplicateExecutionDoesNotAdvanceRebalanceGridTwice() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.70"))),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.005"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                new FakeMarketDataGateway(new BigDecimal("65000")),
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(),
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("1.20"),
                        new BigDecimal("-0.025"),
                        BigDecimal.ZERO,
                        new BigDecimal("1.175"),
                        new BigDecimal("100000")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );
        service.restoreActiveCycle(
                List.of(new FillRecord(
                        Instant.now().minusSeconds(30),
                        "RBAL:BTCUSDT",
                        "SELL",
                        new BigDecimal("0.025"),
                        new BigDecimal("65000"),
                        new BigDecimal("1625"),
                        BigDecimal.ZERO,
                        "USDT",
                        new BigDecimal("-0.025"),
                        new BigDecimal("-1625"),
                        BigDecimal.ZERO,
                        new BigDecimal("-0.025"),
                        new BigDecimal("65000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RBAL_BTCUSDT_I_test",
                        "initial-exec"
                )),
                List.of(
                        new WorkingOrder(
                                "BTCUSDT",
                                "RBAL_BTCUSDT_PB_test",
                                "buy-exchange",
                                TradeSide.BUY,
                                OrderType.LIMIT,
                                new BigDecimal("64935"),
                                new BigDecimal("0.005"),
                                false,
                                OrderStatus.NEW,
                                Instant.now(),
                                Instant.now()
                        ),
                        new WorkingOrder(
                                "BTCUSDT",
                                "RBAL_BTCUSDT_PS_test",
                                "sell-exchange",
                                TradeSide.SELL,
                                OrderType.LIMIT,
                                new BigDecimal("65100"),
                                new BigDecimal("0.005"),
                                false,
                                OrderStatus.NEW,
                                Instant.now(),
                                Instant.now()
                        )
                )
        );
        FillEvent fill = new FillEvent(
                "dup-exec",
                "BTCUSDT",
                "RBAL_BTCUSDT_PS_test",
                TradeSide.SELL,
                new BigDecimal("0.005"),
                new BigDecimal("65100"),
                Instant.now()
        );

        java.util.concurrent.atomic.AtomicInteger accountingCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        service.onExecution(fill, "test", accountingCallbacks::incrementAndGet);
        int submittedAfterFirst = tradeGateway.submitted.size();
        service.onExecution(fill, "test", accountingCallbacks::incrementAndGet);

        assertTrue(submittedAfterFirst > 0);
        assertEquals(submittedAfterFirst, tradeGateway.submitted.size());
        assertEquals(2, accountingCallbacks.get());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void activeCycleTimeoutRequotesPairWithGridEngine() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        FakeMarketDataGateway marketDataGateway = new FakeMarketDataGateway(new BigDecimal("100"));
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true,
                        ProductCategory.LINEAR,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.75"))),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.50"),
                        new BigDecimal("0.010"),
                        new BigDecimal("5"),
                        new BigDecimal("0.100"),
                        new BigDecimal("1.0"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(1),
                        1,
                        "rebalance-config.properties",
                        stateFile.toString()
                ),
                marketDataGateway,
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        marketDataGateway.timestamp,
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("0.72"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("0.72"),
                        new BigDecimal("100")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );

        service.onPoll();
        OrderIntent initial = tradeGateway.submitted.get(0);
        service.onExecution(new FillEvent(
                "initial-fill",
                "BTCUSDT",
                initial.clientOrderId(),
                TradeSide.BUY,
                initial.qty(),
                new BigDecimal("100"),
                marketDataGateway.timestamp
        ), "test");
        int submittedAfterPair = tradeGateway.submitted.size();
        assertEquals(3, submittedAfterPair);

        AtomicInteger correctionRecorded = new AtomicInteger();
        service.onExecution(new FillEvent(
                "correction-fill",
                "BTCUSDT",
                "RBAL_BTCUSDT_PS_fix025_test",
                TradeSide.SELL,
                new BigDecimal("0.005"),
                new BigDecimal("101"),
                marketDataGateway.timestamp
        ), "test", correctionRecorded::incrementAndGet);

        assertEquals(1, correctionRecorded.get());
        assertEquals(submittedAfterPair, tradeGateway.submitted.size());

        marketDataGateway.advance(Duration.ofMinutes(2));
        service.onPoll();

        assertEquals(2, tradeGateway.canceled.size());
        assertEquals(5, tradeGateway.submitted.size());
        assertTrue(tradeGateway.submitted.subList(submittedAfterPair, tradeGateway.submitted.size()).stream()
                .allMatch(order -> order.clientOrderId().startsWith(RebalanceService.CLIENT_ORDER_PREFIX)));
        Files.deleteIfExists(stateFile);
    }

    @Test
    void rebalanceGridUsesAccumulatedProfitReduceSizingState() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-profit-sizing", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        FakeMarketDataGateway marketDataGateway = new FakeMarketDataGateway(new BigDecimal("100"));
        GridOrderPlanner planner = new GridOrderPlanner(
                new ClientOrderIdFactory("RBAL"),
                new BigDecimal("1.10"),
                (market, ledger, price, qty) -> true,
                (market, ledger, price, qty) -> false,
                symbol -> new ProfitSizingState(
                        new BigDecimal("0.0005"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )
        );
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true, ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.75"))),
                        new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.85"),
                        new BigDecimal("0.005"), new BigDecimal("5"), new BigDecimal("0.100"),
                        new BigDecimal("1.0"), new BigDecimal("0.001"), new BigDecimal("0.001"),
                        Duration.ofMinutes(15), 1, "rebalance-config.properties", stateFile.toString()
                ),
                marketDataGateway,
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        marketDataGateway.timestamp,
                        accountType,
                        baseCoin,
                        quoteCoin,
                        new BigDecimal("0.72"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("0.72"),
                        new BigDecimal("100")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger()),
                reason -> {
                },
                planner
        );

        service.onPoll();
        OrderIntent initial = tradeGateway.submitted.get(0);
        service.onExecution(new FillEvent(
                "initial-fill-profit-sizing",
                "BTCUSDT",
                initial.clientOrderId(),
                TradeSide.BUY,
                initial.qty(),
                new BigDecimal("100"),
                marketDataGateway.timestamp
        ), "test");

        OrderIntent boostedReduce = tradeGateway.submitted.stream()
                .filter(order -> order.type() == OrderType.LIMIT && order.side() == TradeSide.SELL)
                .findFirst()
                .orElseThrow();
        assertEquals(new BigDecimal("0.006"), boostedReduce.qty());
        assertTrue(boostedReduce.reason().contains(
                "profit-reduce-boost:base=0.005,order=0.006,intended=0.0005,carry=0.0005,applied=0.001"
        ));
        Files.deleteIfExists(stateFile);
    }

    @Test
    void activeCycleCancelsOrdersOutsideCurrentPair() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-state", ".json");
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        FakeMarketDataGateway marketDataGateway = new FakeMarketDataGateway(new BigDecimal("100"));
        RebalanceService service = new RebalanceService(
                new RebalanceSettings(
                        true, ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.75"))),
                        new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.50"),
                        new BigDecimal("0.005"), new BigDecimal("5"), new BigDecimal("0.100"),
                        new BigDecimal("1.0"), new BigDecimal("0.001"), new BigDecimal("0.001"),
                        Duration.ofMinutes(15), 1, "rebalance-config.properties", stateFile.toString()
                ),
                marketDataGateway,
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(), accountType, baseCoin, quoteCoin, new BigDecimal("0.72"),
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.72"), new BigDecimal("100")
                ),
                tradeGateway,
                registry,
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );
        WorkingOrder currentBuy = openOrder("RBAL_BTCUSDT_PB_current", TradeSide.BUY, Instant.now());
        WorkingOrder currentSell = openOrder("RBAL_BTCUSDT_PS_current", TradeSide.SELL, Instant.now());
        WorkingOrder orphanBuy = openOrder("RBAL_BTCUSDT_PB_orphan", TradeSide.BUY, Instant.now().minusSeconds(60));
        registry.trackRemote(currentBuy);
        registry.trackRemote(currentSell);
        registry.trackRemote(orphanBuy);
        service.restoreActiveCycle(
                List.of(new FillRecord(
                        Instant.now().minusSeconds(120), "RBAL:BTCUSDT", "BUY", new BigDecimal("0.025"),
                        new BigDecimal("100"), new BigDecimal("2.5"), BigDecimal.ZERO, "USDT",
                        new BigDecimal("0.025"), new BigDecimal("2.5"), BigDecimal.ZERO,
                        new BigDecimal("0.025"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, "RBAL_BTCUSDT_I_test", "initial"
                )),
                List.of(currentBuy, currentSell, orphanBuy)
        );

        service.cancelOrphanOrdersIfNeeded();

        assertEquals(List.of(orphanBuy.clientOrderId()), tradeGateway.canceled);
        assertEquals(OrderStatus.CANCELED, registry.snapshot().stream()
                .filter(order -> order.clientOrderId().equals(orphanBuy.clientOrderId()))
                .findFirst().orElseThrow().status());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoCycleStartsFixedBuyGridOnlyWhenTotalEquityExceedsSevenBtc() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-state", ".json");
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoCycleService(stateFile, tradeGateway, new BigDecimal("800"));

        service.onPoll();

        assertEquals(1, tradeGateway.submitted.size());
        OrderIntent initial = tradeGateway.submitted.get(0);
        assertEquals(TradeSide.BUY, initial.side());
        assertEquals(new BigDecimal("0.025"), initial.qty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertEquals(BigDecimal.ONE, saved.targetRatio());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoCycleWaitsWhenTotalEquityIsExactlySevenBtc() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-state", ".json");
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoCycleService(stateFile, tradeGateway, new BigDecimal("700"));

        service.onPoll();

        assertTrue(tradeGateway.submitted.isEmpty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("IDLE", saved.status());
        assertTrue(saved.message().contains("must be greater than 7 BTC"));
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoShortCycleStartsSellGridAboveConfiguredPriceAndTargetsSeventyPercent() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-short-state", ".json");
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoShortCycleService(stateFile, tradeGateway, new BigDecimal("100.1"));

        service.onPoll();

        assertEquals(1, tradeGateway.submitted.size());
        OrderIntent initial = tradeGateway.submitted.get(0);
        assertEquals(TradeSide.SELL, initial.side());
        assertEquals(new BigDecimal("0.025"), initial.qty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertEquals(new BigDecimal("0.70"), saved.targetRatio());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoShortCycleWaitsAtConfiguredPriceThreshold() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-short-state", ".json");
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoShortCycleService(stateFile, tradeGateway, new BigDecimal("80"));

        service.onPoll();

        assertTrue(tradeGateway.submitted.isEmpty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("IDLE", saved.status());
        assertTrue(saved.message().contains("equals trigger threshold 80"));
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoShortCycleRecoversFromTransientErrorWhenNoCycleOrOrdersRemain() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-short-error-state", ".json");
        new FileRebalanceStateStore(stateFile).save(new RebalanceState(
                true, "BTCUSDT", "ERROR", new BigDecimal("0.70"), new BigDecimal("0.80"),
                new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("8"), new BigDecimal("1001"),
                new BigDecimal("100.1"), null, null, null, null, List.of(new BigDecimal("0.80")),
                Instant.now(), "temporary account snapshot failure"
        ));
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoShortCycleService(stateFile, tradeGateway, new BigDecimal("100.1"));

        service.onPoll();

        assertEquals(1, tradeGateway.submitted.size());
        assertEquals(TradeSide.SELL, tradeGateway.submitted.get(0).side());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertEquals(new BigDecimal("0.70"), saved.targetRatio());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoShortCycleStartsLongGridBelowConfiguredPriceOnlyWhileIdle() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-short-long-state", ".json");
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoShortCycleService(stateFile, tradeGateway, new BigDecimal("79.9"));

        service.onPoll();

        assertEquals(1, tradeGateway.submitted.size());
        OrderIntent initial = tradeGateway.submitted.get(0);
        assertEquals(TradeSide.BUY, initial.side());
        assertEquals(new BigDecimal("0.015"), initial.qty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertEquals(BigDecimal.ONE, saved.targetRatio());
        Files.deleteIfExists(stateFile);
    }

    @Test
    void autoLongBelowUsesSingleGridQtyAndNeverShortsAtOrAboveThreshold() throws Exception {
        for (String price : List.of("100000", "100001")) {
            Path state = Files.createTempFile("auto-long-below-wait", ".json");
            RecordingTradeGateway gateway = new RecordingTradeGateway();
            RebalanceService service = thresholdCycleService(state, gateway, new BigDecimal(price),
                    RebalanceMode.AUTO_LONG_BELOW, "100000", "0.006");
            service.onPoll();
            assertTrue(gateway.submitted.isEmpty());
            assertEquals("IDLE", new FileRebalanceStateStore(state).load().orElseThrow().status());
        }
        Path state = Files.createTempFile("auto-long-below-start", ".json");
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, new BigDecimal("99999"),
                RebalanceMode.AUTO_LONG_BELOW, "100000", "0.006");
        service.onPoll();
        assertEquals(1, gateway.submitted.size());
        assertEquals(TradeSide.BUY, gateway.submitted.get(0).side());
        assertEquals(0, new BigDecimal("0.030").compareTo(gateway.submitted.get(0).qty()));
        assertEquals(BigDecimal.ONE, new FileRebalanceStateStore(state).load().orElseThrow().targetRatio());
    }

    @Test
    void enabledMonitorsUseListOrderAndLockCycleSizing() throws Exception {
        Path state = Files.createTempDirectory("rebalance-monitors").resolve("state.json");
        RebalanceMonitorStore store = new RebalanceMonitorStore(state);
        store.save(List.of(
                new RebalanceMonitorRule("off", "disabled", false, TradeSide.BUY,
                        RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("10000"),
                        new BigDecimal("10"), new BigDecimal("0.01"), BigDecimal.ONE),
                new RebalanceMonitorRule("first", "first", true, TradeSide.BUY,
                        RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("10000"),
                        new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE),
                new RebalanceMonitorRule("second", "second", true, TradeSide.SELL,
                        RebalanceMonitorRule.Trigger.ABOVE, new BigDecimal("8000"),
                        new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("0.5"))));
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, new BigDecimal("9000"),
                RebalanceMode.AUTO_LONG_BELOW, "100000", "0.001");
        service.configureMonitorStore(store);

        service.onPoll();

        assertEquals(1, gateway.submitted.size());
        assertEquals(TradeSide.BUY, gateway.submitted.get(0).side());
        assertEquals(0, gateway.submitted.get(0).qty().compareTo(new BigDecimal("0.030")));
        assertEquals("first", store.active().orElseThrow().id());
        assertEquals(BigDecimal.ONE, new FileRebalanceStateStore(state).load().orElseThrow().targetRatio());
        store.save(List.of(new RebalanceMonitorRule("first", "first", false, TradeSide.BUY,
                RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("10000"),
                new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE)));
        assertEquals("first", store.active().orElseThrow().id());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> store.save(List.of()));
    }

    @Test
    void monitorTriggerIsStrictAndStoreRejectsInvalidRules() throws Exception {
        Path state = Files.createTempDirectory("rebalance-monitor-threshold").resolve("state.json");
        RebalanceMonitorStore store = new RebalanceMonitorStore(state);
        store.save(List.of(new RebalanceMonitorRule("rule", "test", true, TradeSide.BUY,
                RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("10000"),
                new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE)));
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, new BigDecimal("10000"),
                RebalanceMode.AUTO_LONG_BELOW, "100000", "0.001");
        service.configureMonitorStore(store);
        service.onPoll();
        assertTrue(gateway.submitted.isEmpty());
        assertEquals("IDLE", new FileRebalanceStateStore(state).load().orElseThrow().status());
        assertTrue(store.active().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> store.save(List.of(
                new RebalanceMonitorRule("bad", "bad", true, TradeSide.BUY,
                        RebalanceMonitorRule.Trigger.BELOW, BigDecimal.ZERO,
                        new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE))));
    }

    @Test
    void shortMonitorUsesItsOwnConfigurableEndRatio() throws Exception {
        Path state = Files.createTempDirectory("rebalance-monitor-short").resolve("state.json");
        RebalanceMonitorStore store = new RebalanceMonitorStore(state);
        store.save(List.of(new RebalanceMonitorRule("short", "high price short", true, TradeSide.SELL,
                RebalanceMonitorRule.Trigger.ABOVE, new BigDecimal("8000"),
                new BigDecimal("3"), new BigDecimal("0.004"), new BigDecimal("0.50"))));
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, new BigDecimal("9000"),
                RebalanceMode.AUTO_LONG_BELOW, "100000", "0.001");
        service.configureMonitorStore(store);

        service.onPoll();

        assertEquals(1, gateway.submitted.size());
        assertEquals(TradeSide.SELL, gateway.submitted.get(0).side());
        assertEquals(0, gateway.submitted.get(0).qty().compareTo(new BigDecimal("0.012")));
        assertEquals(0, new FileRebalanceStateStore(state).load().orElseThrow()
                .targetRatio().compareTo(new BigDecimal("0.50")));
    }

    @Test
    void changingBoundRulesEndRatioEndsCurrentCycleWithoutFlatteningInventory() throws Exception {
        Path state = Files.createTempDirectory("rebalance-monitor-live-target").resolve("state.json");
        RebalanceMonitorStore store = new RebalanceMonitorStore(state);
        RebalanceMonitorRule rule = new RebalanceMonitorRule("bound", "bound", true, TradeSide.BUY,
                RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("10000"),
                new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE);
        store.save(List.of(rule));
        store.setActive(rule);
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, new BigDecimal("9000"),
                RebalanceMode.AUTO_LONG_BELOW, "100000", "0.001");
        service.configureMonitorStore(store);
        service.restoreActiveCycle(List.of(new FillRecord(Instant.now().minusSeconds(30),
                "RBAL:BTCUSDT", "BUY", new BigDecimal("0.030"), new BigDecimal("9000"),
                new BigDecimal("0.030"), new BigDecimal("9000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "RBAL_BTCUSDT_I_bound", "bound-fill")), List.of());
        store.save(List.of(new RebalanceMonitorRule("bound", "bound", true, TradeSide.BUY,
                RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("10000"),
                new BigDecimal("5"), new BigDecimal("0.006"), new BigDecimal("0.70"))));

        service.onPoll();

        assertEquals("IDLE", new FileRebalanceStateStore(state).load().orElseThrow().status());
        assertEquals(0, new FileRebalanceStateStore(state).load().orElseThrow().targetRatio()
                .compareTo(new BigDecimal("0.70")));
        assertTrue(store.active().isEmpty());
        assertTrue(gateway.submitted.isEmpty(), "changing the end ratio must not submit a flattening trade");
    }

    @Test
    void autoShortCycleDoesNotStartLongGridBelowThresholdFromNonIdleState() throws Exception {
        Path stateFile = Files.createTempFile("rebalance-auto-short-non-idle-state", ".json");
        new FileRebalanceStateStore(stateFile).save(new RebalanceState(
                true, "BTCUSDT", "GRID_OPENING", new BigDecimal("0.70"), new BigDecimal("0.80"),
                new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("8"), new BigDecimal("799"),
                new BigDecimal("79.9"), null, null, null, null, List.of(new BigDecimal("0.80")),
                Instant.now(), "existing cycle state"
        ));
        RecordingTradeGateway tradeGateway = new RecordingTradeGateway();
        RebalanceService service = autoShortCycleService(stateFile, tradeGateway, new BigDecimal("79.9"));

        service.onPoll();

        assertTrue(tradeGateway.submitted.isEmpty());
        RebalanceState saved = new FileRebalanceStateStore(stateFile).load().orElseThrow();
        assertEquals("GRID_OPENING", saved.status());
        assertTrue(saved.message().contains("state must be IDLE"));
        Files.deleteIfExists(stateFile);
    }

    @Test
    void profitExitInstructionBlocksAutomaticCyclesAboveAndBelowThreshold() throws Exception {
        for (String price : List.of("79", "81")) {
            Path state = Files.createTempDirectory("profit-exit-service").resolve("state.json");
            RecordingTradeGateway gateway = new RecordingTradeGateway();
            RebalanceService service = autoShortCycleService(state, gateway, new BigDecimal(price));
            Files.writeString(Path.of(state + ".profit-exit.json"), "{\"cycleStartMillis\":1789894318196}");
            service.configureProfitExit(new RebalanceProfitExit.Exchange() {
                public List<WorkingOrder> openOrders() { throw new AssertionError("no exchange mutations for absent cycle"); }
                public RebalanceProfitExit.Evidence evidence(String id) { throw new AssertionError(); }
                public BigDecimal bestOppositePrice(TradeSide side) { throw new AssertionError(); }
            }, ignored -> null, ignored -> { throw new AssertionError(); });
            service.onPoll();
            assertTrue(gateway.submitted.isEmpty());
            assertTrue(new FileRebalanceStateStore(state).load().orElseThrow().message().contains("new cycles disabled"));
        }
    }

    private RebalanceService autoCycleService(Path stateFile, RecordingTradeGateway tradeGateway, BigDecimal totalEquity) {
        FakeMarketDataGateway marketDataGateway = new FakeMarketDataGateway(new BigDecimal("100"));
        RebalanceSettings settings = new RebalanceSettings(
                true, RebalanceMode.AUTO_CYCLE, new BigDecimal("7"), new BigDecimal("0.005"), new BigDecimal("5"),
                ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                List.of(
                        new RebalanceBand(BigDecimal.ZERO, new BigDecimal("0.80")),
                        new RebalanceBand(new BigDecimal("60000"), new BigDecimal("0.75")),
                        new RebalanceBand(new BigDecimal("65000"), new BigDecimal("0.70")),
                        new RebalanceBand(new BigDecimal("100000"), new BigDecimal("0.50"))
                ),
                new BigDecimal("0.01"), new BigDecimal("0.0016"), new BigDecimal("0.85"),
                new BigDecimal("0.001"), new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("1.0"),
                new BigDecimal("0.001"), new BigDecimal("0.001"), Duration.ofMinutes(15), 1,
                "rebalance-config.properties", stateFile.toString()
        );
        return new RebalanceService(
                settings,
                marketDataGateway,
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(), accountType, baseCoin, quoteCoin, new BigDecimal("0.10"),
                        totalEquity.subtract(new BigDecimal("10")), BigDecimal.ZERO, new BigDecimal("0.10"), totalEquity
                ),
                tradeGateway,
                new WorkingOrderRegistry(),
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );
    }

    @Test
    void largeFillNotificationReadsCycleAfterPersistenceAtMarketPrice() throws Exception {
        Path directory = Files.createTempDirectory("rebalance-notification");
        SqliteFillStore store = new SqliteFillStore(directory.resolve("fills.sqlite"));
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = autoShortCycleService(directory.resolve("state.json"), gateway,
                new BigDecimal("100"));
        List<CurrentCycleSummary> observed = new ArrayList<>();
        service.configureProfitExit(new RebalanceProfitExit.Exchange() {
            public List<WorkingOrder> openOrders() { throw new AssertionError("No exit expected"); }
            public RebalanceProfitExit.Evidence evidence(String id) { throw new AssertionError(); }
            public BigDecimal bestOppositePrice(TradeSide side) { throw new AssertionError(); }
        }, price -> {
            CurrentCycleSummary cycle = store.currentCycle("RBAL:BTCUSDT", Instant.now(), price);
            if (cycle != null) observed.add(cycle);
            return cycle;
        }, ignored -> { throw new AssertionError("No exit fill expected"); });
        service.onPoll();
        OrderIntent initial = gateway.submitted.get(0);
        Instant time = Instant.parse("2026-10-17T21:29:59.623Z");
        FillEvent execution = new FillEvent("notification-fill", "BTCUSDT", initial.clientOrderId(),
                TradeSide.SELL, initial.qty(), new BigDecimal("101"), time,
                initial.qty().multiply(new BigDecimal("101")), new BigDecimal("0.01"), "USDT");
        Runnable persist = () -> store.save(new FillRecord(time, "RBAL:BTCUSDT", "SELL", execution.fillQty(),
                execution.fillPrice(), execution.fillValue(), execution.fee(), "USDT",
                execution.fillQty().negate(), execution.fillValue().negate(), execution.fee(),
                execution.fillQty().negate(), execution.fillPrice(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, execution.clientOrderId(), execution.executionId()));

        service.onExecution(execution, "test", persist);

        assertEquals(1, observed.size(), "Notification must query the cycle after this fill is persisted");
        CurrentCycleSummary cycle = observed.get(0);
        assertEquals(1, cycle.fillCount());
        assertEquals(0, cycle.buyCount());
        assertEquals(1, cycle.sellCount());
        assertEquals(new BigDecimal("100"), cycle.marketPrice());
        assertEquals(0, execution.fillQty().subtract(execution.fee()).compareTo(cycle.markPricePnl()));
        assertEquals(time, store.currentCycleFills("RBAL:BTCUSDT").get(0).time());
        service.onExecution(execution, "test", persist);
        assertEquals(1, observed.size(), "Duplicate execution must not trigger another notification query");
    }

    @Test
    void waterExitCompletesCycleAndEnabledBoundMonitorCanStartNextCycle() throws Exception {
        RebalanceWaterExitTest.Fixture f = new RebalanceWaterExitTest.Fixture();
        f.price = new BigDecimal("105000");
        Path state = f.path.resolveSibling("service.json");
        RebalanceMonitorStore store = new RebalanceMonitorStore(state);
        RebalanceMonitorRule rule = new RebalanceMonitorRule("bound", "bound", true, TradeSide.BUY,
                RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("200000"),
                new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE);
        store.save(List.of(rule)); store.setActive(rule);
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, f.price,
                RebalanceMode.AUTO_LONG_BELOW, "200000", "0.006");
        service.configureMonitorStore(store);
        service.configureProfitExit(new RebalanceProfitExit.Exchange() {
            public List<WorkingOrder> openOrders() { return List.copyOf(f.open); }
            public RebalanceProfitExit.Evidence evidence(String id) { return f.evidence.get(id); }
            public BigDecimal bestOppositePrice(TradeSide side) { return f.price; }
        }, ignored -> f.cycle(), f::apply);
        service.restoreActiveCycle(List.of(new FillRecord(f.start, "RBAL:BTCUSDT", "BUY", f.qty,
                new BigDecimal("100000"), f.qty, new BigDecimal("100000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "RBAL_initial", "initial")), List.of());

        service.onPoll();
        assertEquals(1, gateway.submitted.size());
        OrderIntent exit = gateway.submitted.get(0);
        assertEquals(TradeSide.SELL, exit.side());
        assertEquals(0, f.qty.compareTo(exit.qty()));
        assertEquals("GRID_ACTIVE", new FileRebalanceStateStore(state).load().orElseThrow().status());
        assertTrue(store.active().isPresent());
        f.finish(exit); service.onPoll();
        assertEquals(0, f.qty.signum());
        assertEquals("IDLE", new FileRebalanceStateStore(state).load().orElseThrow().status());
        assertTrue(store.active().isEmpty()); assertTrue(store.rules().get(0).enabled());
        service.onPoll();
        assertEquals(2, gateway.submitted.size());
        assertEquals(TradeSide.BUY, gateway.submitted.get(1).side());
        assertEquals("GRID_OPENING", new FileRebalanceStateStore(state).load().orElseThrow().status());
    }

    @Test
    void manualReductionKeepsCurrentCycleAndRebuildsGridAfterConfirmedFill() throws Exception {
        RebalanceWaterExitTest.Fixture f = new RebalanceWaterExitTest.Fixture(); f.price = new BigDecimal("99000");
        Path state = f.path.resolveSibling("service.json");
        RebalanceMonitorStore store = new RebalanceMonitorStore(state);
        RebalanceMonitorRule rule = new RebalanceMonitorRule("bound", "bound", true, TradeSide.BUY,
                RebalanceMonitorRule.Trigger.BELOW, new BigDecimal("200000"),
                new BigDecimal("5"), new BigDecimal("0.006"), BigDecimal.ONE);
        store.save(List.of(rule)); store.setActive(rule);
        RecordingTradeGateway gateway = new RecordingTradeGateway();
        RebalanceService service = thresholdCycleService(state, gateway, f.price,
                RebalanceMode.AUTO_LONG_BELOW, "200000", "0.006");
        service.configureMonitorStore(store);
        service.configureProfitExit(new RebalanceProfitExit.Exchange() {
            public List<WorkingOrder> openOrders() { return List.copyOf(f.open); }
            public RebalanceProfitExit.Evidence evidence(String id) { return f.evidence.get(id); }
            public BigDecimal bestOppositePrice(TradeSide side) { return f.price; }
        }, ignored -> f.cycle(), f::apply);
        service.restoreActiveCycle(List.of(new FillRecord(f.start, "RBAL:BTCUSDT", "BUY", f.qty,
                new BigDecimal("100000"), f.qty, new BigDecimal("100000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "RBAL_initial", "initial")), List.of());
        assertEquals(0, new BigDecimal("0.010").compareTo(service.requestManualReduction()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::requestManualReduction);
        service.onPoll(); assertEquals(1, gateway.submitted.size());
        OrderIntent order = gateway.submitted.get(0);
        assertEquals(0, new BigDecimal("0.010").compareTo(order.qty()));
        f.finish(order); service.onPoll();
        assertEquals(0, new BigDecimal("0.09").compareTo(f.qty));
        assertEquals("GRID_ACTIVE", new FileRebalanceStateStore(state).load().orElseThrow().status());
        assertEquals(f.start, service.activeCycleStartedAt());
        assertEquals(3, gateway.submitted.size(), "confirmed reduction rebuilds the ordinary grid pair");
    }

    private RebalanceService autoShortCycleService(
            Path stateFile,
            RecordingTradeGateway tradeGateway,
            BigDecimal price
    ) {
        return thresholdCycleService(stateFile, tradeGateway, price, RebalanceMode.AUTO_SHORT_CYCLE, "80", "0.003");
    }

    private RebalanceService thresholdCycleService(
            Path stateFile, RecordingTradeGateway tradeGateway, BigDecimal price,
            RebalanceMode mode, String threshold, String singleGridQty
    ) {
        FakeMarketDataGateway marketDataGateway = new FakeMarketDataGateway(price);
        RebalanceSettings settings = new RebalanceSettings(
                true, mode, new BigDecimal("6"), new BigDecimal(singleGridQty), new BigDecimal("5"),
                ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                List.of(new RebalanceBand(new BigDecimal(threshold), new BigDecimal("0.70"))),
                new BigDecimal("0.01"), new BigDecimal("0.0016"), new BigDecimal("0.85"),
                new BigDecimal("0.005"), new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("10"),
                new BigDecimal("0.001"), new BigDecimal("0.001"), Duration.ofMinutes(15), 1,
                "rebalance-config.properties", stateFile.toString()
        );
        BigDecimal netBaseQty = new BigDecimal("8");
        BigDecimal totalEquity = price.multiply(BigDecimal.TEN);
        return new RebalanceService(
                settings,
                marketDataGateway,
                (accountType, baseCoin, quoteCoin, positionCategory, symbol) -> new AccountAssetSnapshot(
                        Instant.now(), accountType, baseCoin, quoteCoin, netBaseQty,
                        totalEquity.subtract(netBaseQty.multiply(price)), BigDecimal.ZERO, netBaseQty, totalEquity
                ),
                tradeGateway,
                new WorkingOrderRegistry(),
                new FileRebalanceStateStore(stateFile),
                new NoopEventLogger(),
                new AlertService(new NoopEventLogger())
        );
    }

    private WorkingOrder openOrder(String clientOrderId, TradeSide side, Instant createdAt) {
        return new WorkingOrder(
                "BTCUSDT", clientOrderId, clientOrderId + "-exchange", side, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("0.005"), false, OrderStatus.NEW,
                createdAt, createdAt
        );
    }

    private static class FakeMarketDataGateway extends BybitSdkMarketDataGateway {
        private final BigDecimal price;
        private Instant timestamp = Instant.now();

        FakeMarketDataGateway(BigDecimal price) {
            super(null, ProductCategory.SPOT, (IndicatorCalculator) null, 20, 20, new BigDecimal("2.0"));
            this.price = price;
        }

        void advance(Duration duration) {
            timestamp = timestamp.plus(duration);
        }

        @Override
        public InstrumentSpec loadInstrumentSpec(String symbol) {
            return new InstrumentSpec(symbol, new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.1"), new BigDecimal("0.1"));
        }

        @Override
        public MarketSnapshot latestSnapshot(String symbol) {
            return new MarketSnapshot(
                symbol,
                timestamp,
                price,
                new IndicatorSnapshot(timestamp, price, price, price, price)
            );
        }
    }

    private static class RecordingTradeGateway implements BybitTradeGateway {
        private final List<OrderIntent> submitted = new ArrayList<>();
        private final List<String> canceled = new ArrayList<>();

        @Override
        public BybitOrderSubmission submit(OrderIntent orderIntent) {
            submitted.add(orderIntent);
            return new BybitOrderSubmission("exchange-1", orderIntent.clientOrderId());
        }

        @Override
        public void cancel(String symbol, String clientOrderId) {
            canceled.add(clientOrderId);
        }
    }

    private static class NoopEventLogger implements EventLogger {
        @Override
        public void info(String event, Map<String, Object> fields) {
        }

        @Override
        public void warn(String event, Map<String, Object> fields) {
        }

        @Override
        public void error(String event, Map<String, Object> fields) {
        }
    }
}
