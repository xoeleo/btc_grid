package com.btcgrid.app;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.strategy.model.ActiveOrderPair;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivePairConsistencyServiceTest {

    @Test
    void keepsGridActiveWhenBothOrdersAreStillOpen() {
        ActivePairConsistencyService service = new ActivePairConsistencyService();
        StrategyRuntimeState runtimeState = runtimeState();
        List<WorkingOrder> workingOrders = List.of(
                openOrder("BUY-1", TradeSide.BUY),
                openOrder("SELL-1", TradeSide.SELL)
        );

        RuntimeConsistencyResult result = service.reconcile(runtimeState, workingOrders);

        assertFalse(result.corrected());
        assertEquals(GridState.GRID_ACTIVE, result.runtimeState().state());
    }

    @Test
    void downgradesToWaitingTriggerWhenPairIsIncomplete() {
        ActivePairConsistencyService service = new ActivePairConsistencyService();
        StrategyRuntimeState runtimeState = runtimeState();
        List<WorkingOrder> workingOrders = List.of(openOrder("BUY-1", TradeSide.BUY));

        RuntimeConsistencyResult result = service.reconcile(runtimeState, workingOrders);

        assertTrue(result.corrected());
        assertEquals(GridState.WAITING_TRIGGER, result.runtimeState().state());
        assertNull(result.runtimeState().activeOrderPair());
    }

    private StrategyRuntimeState runtimeState() {
        return new StrategyRuntimeState(
                GridState.GRID_ACTIVE,
                new ActiveOrderPair(
                        "PAIR-1",
                        new OrderIntent("BTCUSDT", "BUY-1", TradeSide.BUY, OrderType.LIMIT, new BigDecimal("0.01"), new BigDecimal("70000"), false, "buy"),
                        new OrderIntent("BTCUSDT", "SELL-1", TradeSide.SELL, OrderType.LIMIT, new BigDecimal("0.01"), new BigDecimal("70100"), false, "sell"),
                        Instant.now()
                ),
                Instant.now()
        );
    }

    private WorkingOrder openOrder(String clientOrderId, TradeSide side) {
        return new WorkingOrder(
                "BTCUSDT",
                clientOrderId,
                clientOrderId + "-EX",
                side,
                OrderType.LIMIT,
                new BigDecimal("70000"),
                new BigDecimal("0.01"),
                false,
                OrderStatus.NEW,
                Instant.now(),
                Instant.now()
        );
    }
}
