package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitOrderSubmission;
import com.btcgrid.bybit.adapter.BybitPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkConfig;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateFundingFeeListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
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
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridLiveRunnerOpenOrderReconciliationTest {

    @Test
    void missingRemoteOpenOrderWithExecutionIsHandledAsFill() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(openOrder("GRID_SOLUSDT_PB_test", Instant.now().minusSeconds(30)));
        FakeOrderQueryGateway orderQueryGateway = new FakeOrderQueryGateway(List.of(
                fill("exec-1", "GRID_SOLUSDT_PB_test", "1", "84.99")
        ));
        GridLiveRunner runner = runner(registry, orderQueryGateway);

        Object result = invokeReconcileOpenOrders(runner);

        assertEquals(OrderStatus.FILLED, onlyOrder(registry).status());
        assertTrue(recoveredExecution(result));
        assertEquals(Set.of("GRID_SOLUSDT_PB_test"), orderQueryGateway.executionQueryClientOrderIds);
    }

    @Test
    void missingRemoteOpenOrderWithoutExecutionIsMarkedCanceled() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(openOrder("GRID_SOLUSDT_PB_test", Instant.now().minusSeconds(30)));
        GridLiveRunner runner = runner(registry, new FakeOrderQueryGateway(List.of()));

        invokeReconcileOpenOrders(runner);

        assertEquals(OrderStatus.CANCELED, onlyOrder(registry).status());
    }

    @Test
    void newlyAcceptedOrderGetsVisibilityGraceBeforeMissingRemoteCancellation() throws Exception {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(openOrder("GRID_SOLUSDT_PB_recent", Instant.now()));
        GridLiveRunner runner = runner(registry, new FakeOrderQueryGateway(List.of()));

        invokeReconcileOpenOrders(runner);

        assertEquals(OrderStatus.NEW, onlyOrder(registry).status());
    }

    private GridLiveRunner runner(WorkingOrderRegistry registry, BybitOrderQueryGateway orderQueryGateway) {
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
        AlertService alertService = new AlertService(logger);
        return new GridLiveRunner(
                properties(config),
                new FakeMarketDataGateway(snapshot()),
                new NoopPrivateStreamGateway(),
                engine,
                ledger,
                new LiveCommandExecutor(new NoopTradeGateway(), registry, new RiskGuardService(config), logger, alertService),
                orderQueryGateway,
                new NoopStateStore(),
                registry,
                ConcurrentHashMap.newKeySet(),
                logger,
                alertService,
                snapshot -> {
                }
        );
    }

    private Object invokeReconcileOpenOrders(GridLiveRunner runner) throws Exception {
        Method method = GridLiveRunner.class.getDeclaredMethod("reconcileOpenOrders");
        method.setAccessible(true);
        return method.invoke(runner);
    }

    private boolean recoveredExecution(Object result) throws Exception {
        Method method = result.getClass().getDeclaredMethod("recoveredExecution");
        method.setAccessible(true);
        return (boolean) method.invoke(result);
    }

    private WorkingOrder onlyOrder(WorkingOrderRegistry registry) {
        List<WorkingOrder> orders = new ArrayList<>(registry.snapshot());
        assertEquals(1, orders.size());
        return orders.get(0);
    }

    private WorkingOrder openOrder(String clientOrderId, Instant createdAt) {
        return new WorkingOrder(
                "SOLUSDT",
                clientOrderId,
                clientOrderId + "-exchange",
                TradeSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("84.99"),
                new BigDecimal("1"),
                false,
                OrderStatus.NEW,
                createdAt,
                Instant.now()
        );
    }

    private FillEvent fill(String executionId, String clientOrderId, String qty, String price) {
        return new FillEvent(
                executionId,
                "SOLUSDT",
                clientOrderId,
                TradeSide.BUY,
                new BigDecimal(qty),
                new BigDecimal(price),
                Instant.now()
        );
    }

    private GridStrategyConfig config() {
        return new GridStrategyConfig(
                ProductCategory.LINEAR,
                "SOLUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.0016"),
                new BigDecimal("0.85"),
                BigDecimal.ONE,
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                4,
                55,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
    }

    private GridProperties properties(GridStrategyConfig config) {
        return new GridProperties(
                config,
                new RebalanceSettings(
                        false,
                        com.btcgrid.common.model.ProductCategory.SPOT,
                        "BTCUSDT",
                        "UNIFIED",
                        "BTC",
                        "USDT",
                        java.util.List.of(new RebalanceBand(BigDecimal.ZERO, BigDecimal.ZERO)),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.0016"),
                        new BigDecimal("0.85"),
                        new BigDecimal("0.001"),
                        new BigDecimal("5"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.15"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        Duration.ofMinutes(15),
                        3,
                        "rebalance-config.properties",
                        "rebalance-state.json"
                ),
                new BybitSdkConfig("", "", false, false, false, false, 5000),
                new RuntimeSettings(
                        Duration.ofSeconds(15),
                        false,
                        "state.json",
                        "events.log",
                        "status.json",
                        "fills.sqlite",
                        false,
                        "127.0.0.1",
                        8787,
                        1,
                        1
                ),
                new Properties()
        );
    }

    private MarketSnapshot snapshot() {
        return new MarketSnapshot(
                "SOLUSDT",
                Instant.now(),
                new BigDecimal("85"),
                new IndicatorSnapshot(
                        Instant.now(),
                        new BigDecimal("85"),
                        new BigDecimal("100"),
                        new BigDecimal("85"),
                        new BigDecimal("70")
                )
        );
    }

    private static class FakeOrderQueryGateway implements BybitOrderQueryGateway {
        private final List<FillEvent> executions;
        private Set<String> executionQueryClientOrderIds = Set.of();

        private FakeOrderQueryGateway(List<FillEvent> executions) {
            this.executions = executions;
        }

        @Override
        public List<WorkingOrder> fetchOpenOrders(String symbol) {
            return List.of();
        }

        @Override
        public List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds) {
            executionQueryClientOrderIds = Set.copyOf(clientOrderIds);
            return executions;
        }
    }

    private static class FakeMarketDataGateway extends BybitSdkMarketDataGateway {
        private final MarketSnapshot snapshot;

        private FakeMarketDataGateway(MarketSnapshot snapshot) {
            super(null, ProductCategory.LINEAR, null, 55, 20, new BigDecimal("2.0"));
            this.snapshot = snapshot;
        }

        @Override
        public MarketSnapshot latestSnapshot(String symbol) {
            return snapshot;
        }
    }

    private static class NoopPrivateStreamGateway implements BybitPrivateStreamGateway {
        @Override
        public void connect(
                PrivateExecutionListener executionListener,
                PrivateFundingFeeListener fundingFeeListener,
                PrivateOrderListener orderListener,
                PrivateStreamLifecycleListener lifecycleListener
        ) {
        }

        @Override
        public void close() {
        }
    }

    private static class NoopTradeGateway implements BybitTradeGateway {
        @Override
        public BybitOrderSubmission submit(OrderIntent orderIntent) {
            return new BybitOrderSubmission(orderIntent.clientOrderId() + "-exchange", orderIntent.clientOrderId());
        }

        @Override
        public void cancel(String symbol, String clientOrderId) {
        }
    }

    private static class NoopStateStore implements StateStore {
        @Override
        public Optional<StrategyStateSnapshot> load() {
            return Optional.empty();
        }

        @Override
        public void save(StrategyStateSnapshot snapshot) {
        }
    }

    private static class NoopEventLogger implements EventLogger {
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
