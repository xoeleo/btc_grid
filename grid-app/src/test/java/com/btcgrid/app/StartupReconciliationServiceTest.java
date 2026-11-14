package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupReconciliationServiceTest {

    @Test
    void missingRemoteOrderRemainsPendingInsteadOfBeingMarkedCanceled() {
        WorkingOrder local = order("GRID_SOLUSDT_BUY_1", "SOLUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of());

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway)
                .reconcile("SOLUSDT", List.of(local));

        assertEquals(List.of(local), reconciled);
        assertEquals(OrderStatus.NEW, reconciled.get(0).status());
    }

    @Test
    void terminalLocalOrderIsNotResurrectedByRemoteOpenOrder() {
        WorkingOrder local = order("GRID_SOLUSDT_BUY_1", "SOLUSDT", OrderStatus.FILLED);
        WorkingOrder remote = order("GRID_SOLUSDT_BUY_1", "SOLUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of(remote));

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway)
                .reconcile("SOLUSDT", List.of(local));

        assertSame(local, reconciled.get(0));
        assertEquals(OrderStatus.FILLED, reconciled.get(0).status());
    }

    @Test
    void remoteKnownOpenOrderRefreshesNonTerminalLocalOrder() {
        Instant localCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        WorkingOrder local = new WorkingOrder(
                "SOLUSDT", "GRID_SOLUSDT_BUY_1", "old-exchange", TradeSide.BUY,
                OrderType.LIMIT, new BigDecimal("84"), new BigDecimal("1"), false,
                OrderStatus.NEW, localCreatedAt, localCreatedAt, "manual-stop", new BigDecimal("0.25"), true
        );
        WorkingOrder remote = new WorkingOrder(
                "SOLUSDT", "GRID_SOLUSDT_BUY_1", "exchange-1", TradeSide.BUY,
                OrderType.LIMIT, new BigDecimal("85"), new BigDecimal("1"), false,
                OrderStatus.NEW, Instant.now().minusSeconds(10), Instant.now()
        );
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of(remote));

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway)
                .reconcile("SOLUSDT", List.of(local));

        WorkingOrder merged = reconciled.get(0);
        assertNotSame(remote, merged);
        assertEquals("exchange-1", merged.exchangeOrderId());
        assertEquals(remote.status(), merged.status());
        assertEquals(localCreatedAt, merged.createdAt());
        assertEquals("manual-stop", merged.reason());
        assertEquals(new BigDecimal("0.25"), merged.cumulativeExecutedQty());
        assertTrue(merged.cancellationRequested());
    }

    @Test
    void sharedWalletOrdersForOtherClientsOrSymbolsAreNotImported() {
        WorkingOrder local = order("GRID_SOLUSDT_BUY_1", "SOLUSDT", OrderStatus.NEW);
        WorkingOrder external = order("EXTERNAL_SOLUSDT_1", "SOLUSDT", OrderStatus.NEW);
        WorkingOrder otherSymbol = order("GRID_BTCUSDT_BUY_1", "BTCUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of(external, otherSymbol));

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway)
                .reconcile("SOLUSDT", List.of(local));

        assertEquals(List.of(local), reconciled);
    }

    @Test
    void queryFailureIsPropagatedRatherThanStartingWithGuessedState() {
        WorkingOrder local = order("GRID_SOLUSDT_BUY_1", "SOLUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of());
        gateway.failure = new IllegalStateException("Bybit unavailable");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupReconciliationService(gateway).reconcile("SOLUSDT", List.of(local)));

        assertEquals("Bybit unavailable", failure.getMessage());
    }

    @Test
    void disabledReconciliationReturnsLocalStateWithoutQuerying() {
        WorkingOrder local = order("GRID_SOLUSDT_BUY_1", "SOLUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of());

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway, false)
                .reconcile("SOLUSDT", List.of(local));

        assertEquals(List.of(local), reconciled);
        assertEquals(0, gateway.fetchOpenOrdersCalls);
    }

    @Test
    void manualFlattenOrderIsRecognizedAsStrategyOrder() {
        WorkingOrder local = order("MANUAL_FLATTEN_SOLUSDT_1", "SOLUSDT", OrderStatus.NEW);
        WorkingOrder remote = order("MANUAL_FLATTEN_SOLUSDT_1", "SOLUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of(remote));

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway)
                .reconcile("SOLUSDT", List.of(local));

        assertNotSame(remote, reconciled.get(0));
        assertEquals(remote.exchangeOrderId(), reconciled.get(0).exchangeOrderId());
    }

    @Test
    void remoteStrategyOrderWithoutLocalRecordIsMarkedUnattributed() {
        WorkingOrder remote = order("GRID_SOLUSDT_ORPHAN", "SOLUSDT", OrderStatus.NEW);
        FakeOrderQueryGateway gateway = new FakeOrderQueryGateway(List.of(remote));

        List<WorkingOrder> reconciled = new StartupReconciliationService(gateway)
                .reconcile("SOLUSDT", List.of());

        assertEquals(1, reconciled.size());
        assertEquals("unattributed-startup-order", reconciled.get(0).reason());
        assertEquals(OrderStatus.NEW, reconciled.get(0).status());
    }

    private WorkingOrder order(String clientOrderId, String symbol, OrderStatus status) {
        return new WorkingOrder(
                symbol, clientOrderId, clientOrderId + "-exchange", TradeSide.BUY,
                OrderType.LIMIT, new BigDecimal("85"), new BigDecimal("1"), false,
                status, Instant.now().minusSeconds(10), Instant.now()
        );
    }

    private static final class FakeOrderQueryGateway implements BybitOrderQueryGateway {
        private final List<WorkingOrder> openOrders;
        private int fetchOpenOrdersCalls;
        private RuntimeException failure;

        private FakeOrderQueryGateway(List<WorkingOrder> openOrders) {
            this.openOrders = openOrders;
        }

        @Override
        public List<WorkingOrder> fetchOpenOrders(String symbol) {
            fetchOpenOrdersCalls++;
            if (failure != null) {
                throw failure;
            }
            return openOrders;
        }

        @Override
        public List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds) {
            return List.of();
        }
    }
}
