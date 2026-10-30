package com.btcgrid.app;

import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.OrderUpdateEvent;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkingOrderRegistryTest {

    @Test
    void markCancelPendingPreservesNewStatusAndSetsDurableFlag() {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("cancel-me"));

        registry.markCancelPending("cancel-me", Instant.now());

        WorkingOrder actual = only(registry, "cancel-me");
        assertEquals(OrderStatus.NEW, actual.status());
        assertTrue(actual.cancellationRequested());
    }

    @Test
    void remotePendingOrderRetainsCancellationRequestedFlag() {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        WorkingOrder remote = new WorkingOrder(
                "SOLUSDT", "remote-pending", "exchange-1", TradeSide.SELL,
                OrderType.LIMIT, new BigDecimal("80"), BigDecimal.ONE, false,
                OrderStatus.NEW, Instant.now().minusSeconds(10), Instant.now(),
                "grid", BigDecimal.ZERO, true
        );

        registry.trackRemote(remote);

        assertTrue(only(registry, "remote-pending").cancellationRequested());
    }

    @Test
    void staleNewUpdateCannotReviveTerminalOrder() {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("terminal"));
        Instant terminalAt = Instant.now();
        registry.applyUpdate(update("terminal", OrderStatus.CANCELED, BigDecimal.ONE, terminalAt));
        registry.applyUpdate(update("terminal", OrderStatus.NEW, BigDecimal.ZERO, terminalAt.plusSeconds(1)));

        WorkingOrder actual = only(registry, "terminal");
        assertEquals(OrderStatus.CANCELED, actual.status());
        assertFalse(actual.cancellationRequested());
    }

    @Test
    void cumulativeExecutionQuantityNeverMovesBackward() {
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackRemote(order("fills"));
        Instant firstAt = Instant.now();
        registry.applyUpdate(update("fills", OrderStatus.NEW, new BigDecimal("2"), firstAt));
        registry.applyUpdate(update("fills", OrderStatus.NEW, BigDecimal.ONE, firstAt.plusSeconds(1)));

        assertEquals(new BigDecimal("2"), only(registry, "fills").cumulativeExecutedQty());
    }

    private WorkingOrder order(String clientOrderId) {
        return new WorkingOrder(
                "SOLUSDT", clientOrderId, null, TradeSide.SELL,
                OrderType.LIMIT, new BigDecimal("80"), BigDecimal.ONE, false,
                OrderStatus.NEW, Instant.now(), Instant.now(), "grid"
        );
    }

    private OrderUpdateEvent update(String clientOrderId, OrderStatus status, BigDecimal cumulativeQty, Instant at) {
        return new OrderUpdateEvent(
                "SOLUSDT", clientOrderId, "exchange-" + clientOrderId, status,
                TradeSide.SELL, new BigDecimal("80"), BigDecimal.ONE, cumulativeQty, at
        );
    }

    private WorkingOrder only(WorkingOrderRegistry registry, String clientOrderId) {
        return registry.snapshot().stream()
                .filter(order -> clientOrderId.equals(order.clientOrderId()))
                .findFirst()
                .orElseThrow();
    }
}
