package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitOrderSubmission;
import com.btcgrid.bybit.adapter.BybitPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkConfig;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.bybit.adapter.BybitAccountGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateFundingFeeListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridLiveRunnerSafetyTest {

    @Test
    void partialExecutionDoesNotMarkOrderFilled() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_PARTIAL", new BigDecimal("2"), OrderStatus.NEW));
        Fixture fixture = fixture(registry, new QueryGateway(), new CapturingStateStore(), new CapturingTradeGateway());

        invoke(fixture.runner, "handleExecution", new Class<?>[]{FillEvent.class, String.class},
                fill("exec-partial", "GRID_SOLUSDT_PARTIAL", "1"), "rest-reconcile");

        assertEquals(OrderStatus.NEW, only(registry, "GRID_SOLUSDT_PARTIAL").status());
        assertEquals(new BigDecimal("1"), fixture.ledger.snapshot().netQty());
        assertTrue(fixture.trade.submitted.isEmpty());
    }

    @Test
    void recoveryFailureLeavesRunnerBlockedWithoutSubmittingOrders() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_RECOVERY", BigDecimal.ONE, OrderStatus.NEW));
        QueryGateway query = new QueryGateway();
        query.executionFailure = new IllegalStateException("execution history unavailable");
        CapturingTradeGateway trade = new CapturingTradeGateway();
        Fixture fixture = fixture(registry, query, new CapturingStateStore(), trade);

        invoke(fixture.runner, "recoverAndEvaluate", new Class<?>[]{MarketSnapshot.class}, fixture.snapshot);

        assertTrue(fixture.runner.recoveryStatus().contains("unavailable"));
        assertTrue(trade.submitted.isEmpty());
        assertFalse((boolean) field(fixture.runner, "recoveryReady").get(fixture.runner));
    }

    @Test
    void stopRequestedPersistsAndAReceivedGridFillCannotPlaceNewOrders() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_STOP", BigDecimal.ONE, OrderStatus.NEW));
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingTradeGateway trade = new CapturingTradeGateway();
        Fixture fixture = fixture(registry, new QueryGateway(), stateStore, trade);
        set(fixture.runner, "stopRequested", true);
        set(fixture.runner, "recoveryReady", true);
        set(fixture.runner, "ledgerTrusted", true);

        fixture.runner.onExecution(fill("exec-stop", "GRID_SOLUSDT_STOP", "1"));

        assertTrue(trade.submitted.isEmpty());
        assertEquals(OrderStatus.FILLED, only(registry, "GRID_SOLUSDT_STOP").status());
        assertNotNull(stateStore.last);
        assertTrue(stateStore.last.safetyState().stopRequested());
    }

    @Test
    void flattenCancelFailureKeepsRunnerOpenAndPersistsStopRequest() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_CANCEL_FAIL", BigDecimal.ONE, OrderStatus.NEW));
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingTradeGateway trade = new CapturingTradeGateway();
        trade.cancelFailure = new IllegalStateException("cancel timeout");
        Fixture fixture = fixture(registry, new QueryGateway(), stateStore, trade);
        ((AtomicBoolean) field(fixture.runner, "running").get(fixture.runner)).set(true);

        assertThrows(IllegalStateException.class, fixture.runner::flattenAndStop);

        assertTrue(((AtomicBoolean) field(fixture.runner, "running").get(fixture.runner)).get());
        assertTrue(trade.submitted.isEmpty());
        assertNotNull(stateStore.last);
        assertTrue(stateStore.last.safetyState().stopRequested());
    }

    @Test
    void acceptedFlattenWithoutFillIsRetriedWithSameClientIdAfterRestart() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingTradeGateway firstTrade = new CapturingTradeGateway();
        QueryGateway firstQuery = new QueryGateway();
        firstQuery.failExecutionOnCall = 2;
        Fixture first = fixture(registry, firstQuery, stateStore, firstTrade);
        seedPosition(first.ledger);
        set(first.runner, "ledgerTrusted", true);
        ((AtomicBoolean) field(first.runner, "running").get(first.runner)).set(true);

        assertThrows(IllegalStateException.class, first.runner::flattenAndStop);

        assertEquals(1, firstTrade.submitted.size());
        String flattenClientOrderId = firstTrade.submitted.get(0).clientOrderId();
        assertTrue(flattenClientOrderId.startsWith("MANUAL_FLATTEN_"));
        assertEquals(flattenClientOrderId, stateStore.last.safetyState().flattenClientOrderId());
        assertEquals(OrderStatus.NEW, only(registry, flattenClientOrderId).status());

        // Simulate the next process using the durable state and durable order
        // record. A lost response must not create a second market order.
        WorkingOrderRegistry restartedRegistry = new WorkingOrderRegistry();
        restartedRegistry.replaceAll(registry.snapshot());
        CapturingTradeGateway secondTrade = new CapturingTradeGateway();
        QueryGateway secondQuery = new QueryGateway();
        secondQuery.executionFailure = new IllegalStateException("restart query unavailable");
        Fixture restarted = fixture(restartedRegistry, secondQuery, stateStore, secondTrade);
        seedPosition(restarted.ledger);
        invoke(restarted.runner, "restoreSafetyState", new Class<?>[0]);
        set(restarted.runner, "ledgerTrusted", true);

        assertThrows(IllegalStateException.class, restarted.runner::flattenAndStop);

        assertTrue(secondTrade.submitted.isEmpty());
        assertEquals(flattenClientOrderId, stateStore.last.safetyState().flattenClientOrderId());
    }

    @Test
    void spotFlattenCapsSharedWalletToStrategyLedgerQuantity() {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        StrategyLedger ledger = spotLedger(new BigDecimal("2"));
        LiveCommandExecutor executor = spotExecutor(trade, new BigDecimal("45"));

        List<String> failures = executor.executeManualFlatten(
                flatten("MANUAL_FLATTEN_SPOT_CAP", new BigDecimal("2")), ledger.snapshot());

        assertTrue(failures.isEmpty());
        assertEquals(new BigDecimal("2"), trade.submitted.get(0).qty());
    }

    @Test
    void spotFlattenWithZeroStrategyLedgerCannotSellSharedWalletInventory() {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        LiveCommandExecutor executor = spotExecutor(trade, new BigDecimal("45"));

        List<String> failures = executor.executeManualFlatten(
                flatten("MANUAL_FLATTEN_SPOT_ZERO", new BigDecimal("45")),
                spotLedger(BigDecimal.ZERO).snapshot());

        assertTrue(trade.submitted.isEmpty());
        assertTrue(failures.stream().anyMatch(message -> message.contains("rejected")));
    }

    @Test
    void spotFlattenRunnerRoundsWalletCapDownToQuantityStep() throws Exception {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        StrategyLedger ledger = spotLedger(new BigDecimal("2"));
        Fixture fixture = spotFixture(new BigDecimal("1.56"), ledger, trade);
        set(fixture.runner, "ledgerTrusted", true);

        assertThrows(IllegalStateException.class, fixture.runner::flattenAndStop);

        assertEquals(1, trade.submitted.size());
        assertEquals(new BigDecimal("1.5"), trade.submitted.get(0).qty());
    }

    @Test
    void spotFlattenWithBaseFeeUsesNetLedgerAndRoundsDown() throws Exception {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        StrategyLedger ledger = new InMemoryStrategyLedger("SOLUSDT");
        ledger.applyFill(new FillEvent("exec-fee", "SOLUSDT", "GRID_SOLUSDT_FEE", TradeSide.BUY,
                new BigDecimal("2"), new BigDecimal("85"), Instant.now(), new BigDecimal("170"),
                new BigDecimal("0.03"), "SOL"));
        Fixture fixture = spotFixture(new BigDecimal("45"), ledger, trade);
        set(fixture.runner, "ledgerTrusted", true);

        assertThrows(IllegalStateException.class, fixture.runner::flattenAndStop);

        assertEquals(new BigDecimal("1.9"), trade.submitted.get(0).qty());
    }

    @Test
    void spotFlattenBelowMinimumLeavesStopPendingWithoutSubmitting() throws Exception {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        StrategyLedger ledger = spotLedger(new BigDecimal("0.2"));
        Fixture fixture = spotFixture(new BigDecimal("0.05"), ledger, trade);
        set(fixture.runner, "ledgerTrusted", true);

        IllegalStateException failure = assertThrows(IllegalStateException.class, fixture.runner::flattenAndStop);

        assertTrue(failure.getMessage().contains("未完成"));
        assertTrue(trade.submitted.isEmpty());
    }

    @Test
    void fullFlattenFillClosesRunnerAndLeavesLedgerAtZero() throws Exception {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        StrategyLedger ledger = spotLedger(new BigDecimal("1"));
        QueryGateway query = new QueryGateway();
        query.fillOnExecutionCall = 2;
        Fixture fixture = spotFixture(new BigDecimal("45"), ledger, trade, query);
        set(fixture.runner, "ledgerTrusted", true);
        ((AtomicBoolean) field(fixture.runner, "running").get(fixture.runner)).set(true);

        fixture.runner.flattenAndStop();

        assertEquals(1, trade.submitted.size());
        assertEquals(BigDecimal.ZERO, ledger.snapshot().netQty());
        assertFalse(((AtomicBoolean) field(fixture.runner, "running").get(fixture.runner)).get());
    }

    @Test
    void partialTerminalFlattenDoesNotCompleteOrSubmitAgain() throws Exception {
        CapturingTradeGateway trade = new CapturingTradeGateway();
        StrategyLedger ledger = spotLedger(new BigDecimal("1"));
        QueryGateway query = new QueryGateway();
        query.fillOnExecutionCall = 2;
        query.partialTerminal = true;
        Fixture fixture = spotFixture(new BigDecimal("45"), ledger, trade, query);
        set(fixture.runner, "ledgerTrusted", true);
        ((AtomicBoolean) field(fixture.runner, "running").get(fixture.runner)).set(true);

        assertThrows(IllegalStateException.class, fixture.runner::flattenAndStop);

        assertEquals(1, trade.submitted.size());
        assertEquals(new BigDecimal("0.5"), ledger.snapshot().netQty());
        assertTrue(((AtomicBoolean) field(fixture.runner, "running").get(fixture.runner)).get());
        assertThrows(IllegalStateException.class, fixture.runner::flattenAndStop);
        assertEquals(1, trade.submitted.size());
        fixture.runner.close();
    }

    @Test
    void duplicateExecutionIdIsAccountedOnlyOnce() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_DEDUP", new BigDecimal("2"), OrderStatus.NEW));
        Fixture fixture = fixture(registry, new QueryGateway(), new CapturingStateStore(), new CapturingTradeGateway());
        FillEvent fill = fill("exec-dedup", "GRID_SOLUSDT_DEDUP", "1");

        invoke(fixture.runner, "handleExecution", new Class<?>[]{FillEvent.class, String.class}, fill, "stream");
        invoke(fixture.runner, "handleExecution", new Class<?>[]{FillEvent.class, String.class}, fill, "stream");

        assertEquals(new BigDecimal("1"), fixture.ledger.snapshot().netQty());
        assertEquals(1, ((Set<?>) field(fixture.runner, "processedExecutionIds").get(fixture.runner)).size());
    }

    @Test
    void failedExecutionRecoveryDoesNotAdvanceWatermark() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_WATERMARK", BigDecimal.ONE, OrderStatus.NEW));
        QueryGateway query = new QueryGateway();
        query.executionFailure = new IllegalStateException("history unavailable");
        Fixture fixture = fixture(registry, query, new CapturingStateStore(), new CapturingTradeGateway());
        Instant watermark = Instant.parse("2026-01-01T00:00:00Z");
        set(fixture.runner, "executionsThrough", watermark);

        assertThrows(IllegalStateException.class,
                () -> invoke(fixture.runner, "reconcileMissingExecutions", new Class<?>[0]));

        assertEquals(watermark, field(fixture.runner, "executionsThrough").get(fixture.runner));
        assertEquals(null, field(fixture.runner, "queriedThrough").get(fixture.runner));
    }

    @Test
    void FilledPrivateOrderWithoutExecutionCannotPassRecovery() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("GRID_SOLUSDT_FILLED_MISSING_EXEC", BigDecimal.ONE, OrderStatus.FILLED));
        QueryGateway query = new QueryGateway();
        Fixture fixture = fixture(registry, query, new CapturingStateStore(), new CapturingTradeGateway());

        invoke(fixture.runner, "recoverAndEvaluate", new Class<?>[]{MarketSnapshot.class}, fixture.snapshot);

        assertFalse((boolean) field(fixture.runner, "recoveryReady").get(fixture.runner));
        assertTrue(fixture.trade.submitted.isEmpty());
    }

    @Test
    void freshStartWithSubscribedPrivateStreamReachesReadyAndRunning() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingTradeGateway trade = new CapturingTradeGateway();
        Fixture fixture = fixture(registry, new QueryGateway(), stateStore, trade,
                new AutoSubscribedPrivateStreamGateway());

        try {
            fixture.runner.start();

            assertTrue(fixture.runner.isRunning());
            assertEquals("ready", fixture.runner.recoveryStatus());
            assertTrue((boolean) field(fixture.runner, "recoveryReady").get(fixture.runner));
            assertTrue((boolean) field(fixture.runner, "ledgerTrusted").get(fixture.runner));
            assertTrue(((AtomicBoolean) field(fixture.runner, "privateStreamSubscribed").get(fixture.runner)).get());
        } finally {
            fixture.runner.close();
        }
    }

    @Test
    void legacyNonZeroStateWithoutSafetyEvidenceBlocksFreshStart() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        CapturingStateStore stateStore = new CapturingStateStore();
        CapturingTradeGateway trade = new CapturingTradeGateway();
        Fixture fixture = fixture(registry, new QueryGateway(), stateStore, trade,
                new AutoSubscribedPrivateStreamGateway());
        seedPosition(fixture.ledger);
        stateStore.last = new StrategyStateSnapshot(
                fixture.ledger.snapshot(), null, null, Set.of(), List.of(), null
        );

        try {
            fixture.runner.start();

            assertFalse(fixture.runner.isStopPending());
            assertTrue(fixture.runner.recoveryStatus().contains("可信"));
            assertFalse((boolean) field(fixture.runner, "recoveryReady").get(fixture.runner));
            assertTrue(trade.submitted.isEmpty());
        } finally {
            fixture.runner.close();
        }
    }

    private Fixture fixture(
            WorkingOrderRegistry registry,
            QueryGateway query,
            CapturingStateStore stateStore,
            CapturingTradeGateway trade
    ) {
        return fixture(registry, query, stateStore, trade, new NoopPrivateStreamGateway());
    }

    private Fixture fixture(
            WorkingOrderRegistry registry,
            QueryGateway query,
            CapturingStateStore stateStore,
            CapturingTradeGateway trade,
            BybitPrivateStreamGateway privateStreamGateway
    ) {
        GridStrategyConfig config = config();
        StrategyLedger ledger = new InMemoryStrategyLedger("SOLUSDT");
        GridStrategyEngine engine = new GridStrategyEngine(
                config,
                new InstrumentSpec("SOLUSDT", new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.01"), new BigDecimal("0.01")),
                ledger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory())
        );
        EventLogger logger = new NoopEventLogger();
        AlertService alerts = new AlertService(logger);
        GridLiveRunner runner = new GridLiveRunner(
                properties(config),
                new FakeMarketDataGateway(snapshot()),
                privateStreamGateway,
                engine,
                ledger,
                new LiveCommandExecutor(trade, registry, new RiskGuardService(config), logger, alerts),
                query,
                stateStore,
                registry,
                ConcurrentHashMap.newKeySet(),
                logger,
                alerts,
                ignored -> {
                }
        );
        setUnchecked(runner, "latestSnapshot", snapshot());
        return new Fixture(runner, ledger, trade, snapshot());
    }

    private Fixture spotFixture(BigDecimal walletBalance, StrategyLedger ledger,
                                CapturingTradeGateway trade) {
        return spotFixture(walletBalance, ledger, trade, new QueryGateway());
    }

    private Fixture spotFixture(BigDecimal walletBalance, StrategyLedger ledger,
                                CapturingTradeGateway trade, QueryGateway query) {
        query.tradeForDynamicFill = trade;
        GridStrategyConfig config = spotConfig();
        GridStrategyEngine engine = new GridStrategyEngine(
                config,
                new InstrumentSpec("SOLUSDT", new BigDecimal("0.1"), new BigDecimal("0.1"),
                        new BigDecimal("0.01"), new BigDecimal("0.01")),
                ledger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory())
        );
        EventLogger logger = new NoopEventLogger();
        AlertService alerts = new AlertService(logger);
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        CapturingStateStore stateStore = new CapturingStateStore();
        GridLiveRunner runner = new GridLiveRunner(
                properties(config),
                new FakeMarketDataGateway(snapshot()),
                new NoopPrivateStreamGateway(),
                engine,
                ledger,
                new LiveCommandExecutor(trade, registry, new RiskGuardService(config), logger, alerts,
                        ProductCategory.SPOT, new FixedAccountGateway(walletBalance)),
                query,
                stateStore,
                registry,
                ConcurrentHashMap.newKeySet(),
                logger,
                alerts,
                ignored -> {
                }
        );
        setUnchecked(runner, "latestSnapshot", snapshot());
        return new Fixture(runner, ledger, trade, snapshot());
    }

    private StrategyLedger spotLedger(BigDecimal netQty) {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("SOLUSDT");
        if (netQty.signum() > 0) {
            ledger.applyFill(new FillEvent("exec-seed-" + netQty, "SOLUSDT", "GRID_SOLUSDT_SEED",
                    TradeSide.BUY, netQty, new BigDecimal("85"), Instant.now()));
        }
        return ledger;
    }

    private LiveCommandExecutor spotExecutor(CapturingTradeGateway trade, BigDecimal walletBalance) {
        GridStrategyConfig config = spotConfig();
        EventLogger logger = new NoopEventLogger();
        return new LiveCommandExecutor(trade, new WorkingOrderRegistry(), new RiskGuardService(config),
                logger, new AlertService(logger), ProductCategory.SPOT,
                new FixedAccountGateway(walletBalance));
    }

    private OrderIntent flatten(String clientOrderId, BigDecimal qty) {
        return new OrderIntent("SOLUSDT", clientOrderId, TradeSide.SELL, OrderType.MARKET,
                qty, null, false, "manual-flatten-and-stop");
    }

    private void seedPosition(StrategyLedger ledger) {
        ledger.applyFill(fill("exec-seed", "GRID_SOLUSDT_SEED", "1"));
    }

    private WorkingOrder only(WorkingOrderRegistry registry, String clientOrderId) {
        return registry.snapshot().stream()
                .filter(order -> clientOrderId.equals(order.clientOrderId()))
                .findFirst()
                .orElseThrow();
    }

    private WorkingOrder order(String clientOrderId, BigDecimal qty, OrderStatus status) {
        return new WorkingOrder(
                "SOLUSDT", clientOrderId, clientOrderId + "-exchange", TradeSide.BUY,
                OrderType.LIMIT, new BigDecimal("84.99"), qty, false, status,
                Instant.now().minusSeconds(30), Instant.now()
        );
    }

    private FillEvent fill(String executionId, String clientOrderId, String qty) {
        return new FillEvent(executionId, "SOLUSDT", clientOrderId, TradeSide.BUY,
                new BigDecimal(qty), new BigDecimal("84.99"), Instant.now());
    }

    private Object invoke(GridLiveRunner runner, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = GridLiveRunner.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(runner, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception exception ? exception : e;
        }
    }

    private Field field(GridLiveRunner runner, String name) throws NoSuchFieldException {
        Field field = GridLiveRunner.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void set(GridLiveRunner runner, String name, Object value) throws Exception {
        field(runner, name).set(runner, value);
    }

    private void setUnchecked(GridLiveRunner runner, String name, Object value) {
        try {
            Field field = field(runner, name);
            if (field.getType() == java.util.concurrent.atomic.AtomicReference.class) {
                ((java.util.concurrent.atomic.AtomicReference) field.get(runner)).set(value);
            } else {
                field.set(runner, value);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private GridStrategyConfig config() {
        return new GridStrategyConfig(
                ProductCategory.LINEAR, "SOLUSDT", EntryDirection.BOTH,
                new BigDecimal("0.0016"), new BigDecimal("0.85"), BigDecimal.ONE,
                new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("100"), 4, 55, 20, new BigDecimal("2.0"),
                new BigDecimal("0.01"), new BigDecimal("10"), new BigDecimal("0.30"),
                true, Duration.ofMinutes(15), Duration.ofMinutes(5)
        );
    }

    private GridStrategyConfig spotConfig() {
        return new GridStrategyConfig(
                ProductCategory.SPOT, "SOLUSDT", EntryDirection.SPOT_LONG_ONLY,
                new BigDecimal("0.0016"), new BigDecimal("0.85"), BigDecimal.ONE,
                new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("100"), 4, 55, 20, new BigDecimal("2.0"),
                new BigDecimal("0.01"), new BigDecimal("10"), new BigDecimal("0.30"),
                true, Duration.ofMinutes(15), Duration.ofMinutes(5)
        );
    }

    private GridProperties properties(GridStrategyConfig config) {
        return new GridProperties(
                config,
                new RebalanceSettings(false, ProductCategory.SPOT, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                        List.of(new RebalanceBand(BigDecimal.ZERO, BigDecimal.ZERO)), new BigDecimal("0.01"),
                        new BigDecimal("0.0016"), new BigDecimal("0.85"), new BigDecimal("0.001"),
                        new BigDecimal("5"), new BigDecimal("0.001"), new BigDecimal("0.15"),
                        new BigDecimal("0.001"), new BigDecimal("0.001"), Duration.ofMinutes(15), 3,
                        "rebalance-config.properties", "rebalance-state.json"),
                new BybitSdkConfig("", "", false, false, false, false, 5000),
                new RuntimeSettings(Duration.ofSeconds(15), false, "state.json", "events.log", "status.json",
                        "fills.sqlite", false, "127.0.0.1", 8787, 1, 1),
                new Properties()
        );
    }

    private MarketSnapshot snapshot() {
        return new MarketSnapshot("SOLUSDT", Instant.now(), new BigDecimal("85"),
                new IndicatorSnapshot(Instant.now(), new BigDecimal("85"), new BigDecimal("100"),
                        new BigDecimal("85"), new BigDecimal("70")));
    }

    private record Fixture(GridLiveRunner runner, StrategyLedger ledger, CapturingTradeGateway trade,
                           MarketSnapshot snapshot) {
    }

    private static final class QueryGateway implements BybitOrderQueryGateway {
        private List<WorkingOrder> openOrders = List.of();
        private List<WorkingOrder> recentOrders = List.of();
        private List<FillEvent> executions = List.of();
        private RuntimeException executionFailure;
        private int executionCalls;
        private int failExecutionOnCall = -1;
        private int fillOnExecutionCall = -1;
        private boolean partialTerminal;
        private CapturingTradeGateway tradeForDynamicFill;

        @Override
        public List<WorkingOrder> fetchOpenOrders(String symbol) {
            return openOrders;
        }

        @Override
        public List<WorkingOrder> fetchRecentOrders(String symbol, Set<String> clientOrderIds) {
            if (partialTerminal && !clientOrderIds.isEmpty()) {
                String clientOrderId = clientOrderIds.iterator().next();
                return List.of(new WorkingOrder(
                        symbol, clientOrderId, clientOrderId + "-exchange", TradeSide.SELL,
                        OrderType.MARKET, null, BigDecimal.ONE, true, OrderStatus.CANCELED,
                        Instant.now().minusSeconds(1), Instant.now(),
                        "manual-flatten-and-stop", new BigDecimal("0.5"), false
                ));
            }
            return recentOrders;
        }

        @Override
        public List<FillEvent> fetchExecutions(String symbol, Set<String> clientOrderIds,
                                               Instant startInclusive, Instant endInclusive) {
            executionCalls++;
            if (executionFailure != null || executionCalls == failExecutionOnCall) {
                throw executionFailure == null ? new IllegalStateException("execution query failed") : executionFailure;
            }
            if (executionCalls == fillOnExecutionCall) {
                String clientOrderId = clientOrderIds != null && !clientOrderIds.isEmpty()
                        ? clientOrderIds.iterator().next()
                        : tradeForDynamicFill == null || tradeForDynamicFill.submitted.isEmpty()
                        ? null : tradeForDynamicFill.submitted.get(0).clientOrderId();
                if (clientOrderId == null) {
                    return List.of();
                }
                BigDecimal qty = partialTerminal ? new BigDecimal("0.5") : BigDecimal.ONE;
                return List.of(new FillEvent(
                        "exec-flatten-" + executionCalls, symbol, clientOrderId, TradeSide.SELL,
                        qty, new BigDecimal("85"), Instant.now()
                ));
            }
            return executions;
        }

        @Override
        public List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds) {
            return executions;
        }
    }

    private static final class CapturingTradeGateway implements BybitTradeGateway {
        private final List<OrderIntent> submitted = new ArrayList<>();
        private RuntimeException submitFailure;
        private RuntimeException cancelFailure;

        @Override
        public BybitOrderSubmission submit(OrderIntent orderIntent) {
            if (submitFailure != null) {
                throw submitFailure;
            }
            submitted.add(orderIntent);
            return new BybitOrderSubmission(orderIntent.clientOrderId() + "-exchange", orderIntent.clientOrderId());
        }

        @Override
        public void cancel(String symbol, String clientOrderId) {
            if (cancelFailure != null) {
                throw cancelFailure;
            }
        }
    }

    private static final class CapturingStateStore implements StateStore {
        private StrategyStateSnapshot last;

        @Override
        public Optional<StrategyStateSnapshot> load() {
            return Optional.ofNullable(last);
        }

        @Override
        public void save(StrategyStateSnapshot snapshot) {
            last = snapshot;
        }
    }

    private static final class FixedAccountGateway implements BybitAccountGateway {
        private final BigDecimal baseBalance;

        private FixedAccountGateway(BigDecimal baseBalance) {
            this.baseBalance = baseBalance;
        }

        @Override
        public AccountAssetSnapshot walletSnapshot(String accountType, String baseCoin, String quoteCoin,
                                                   ProductCategory positionCategory, String symbol) {
            return new AccountAssetSnapshot(Instant.now(), accountType, baseCoin, quoteCoin,
                    baseBalance, BigDecimal.ZERO, BigDecimal.ZERO, baseBalance, BigDecimal.ZERO);
        }
    }

    private static final class FakeMarketDataGateway extends BybitSdkMarketDataGateway {
        private FakeMarketDataGateway(MarketSnapshot snapshot) {
            super(null, ProductCategory.LINEAR, null, 55, 20, new BigDecimal("2.0"));
        }

        @Override
        public MarketSnapshot latestSnapshot(String symbol) {
            return new MarketSnapshot(symbol, Instant.now(), new BigDecimal("85"),
                    new IndicatorSnapshot(Instant.now(), new BigDecimal("85"), new BigDecimal("100"),
                            new BigDecimal("85"), new BigDecimal("70")));
        }

        @Override
        public InstrumentSpec loadInstrumentSpec(String symbol) {
            return new InstrumentSpec(symbol, new BigDecimal("0.1"), new BigDecimal("0.1"),
                    new BigDecimal("0.01"), new BigDecimal("0.01"));
        }
    }

    private static final class NoopPrivateStreamGateway implements BybitPrivateStreamGateway {
        @Override
        public void connect(PrivateExecutionListener executionListener, PrivateFundingFeeListener fundingFeeListener,
                            PrivateOrderListener orderListener, PrivateStreamLifecycleListener lifecycleListener) {
        }

        @Override
        public void close() {
        }
    }

    private static final class AutoSubscribedPrivateStreamGateway implements BybitPrivateStreamGateway {
        @Override
        public void connect(PrivateExecutionListener executionListener, PrivateFundingFeeListener fundingFeeListener,
                            PrivateOrderListener orderListener, PrivateStreamLifecycleListener lifecycleListener) {
            lifecycleListener.onConnected();
            lifecycleListener.onSubscribed();
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopEventLogger implements EventLogger {
        @Override
        public void info(String eventType, Map<String, Object> payload) {
        }

        @Override
        public void warn(String eventType, Map<String, Object> payload) {
        }

        @Override
        public void error(String eventType, Map<String, Object> payload) {
        }
    }
}
