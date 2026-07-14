package com.btcgrid.app;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.strategy.model.ActiveOrderPair;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ActivePairConsistencyService {

    public RuntimeConsistencyResult reconcile(StrategyRuntimeState runtimeState, Collection<WorkingOrder> trackedWorkingOrders) {
        if (runtimeState == null) {
            return new RuntimeConsistencyResult(StrategyRuntimeState.initial(), true);
        }

        ActiveOrderPair activePair = runtimeState.activeOrderPair();
        if (activePair == null) {
            if (runtimeState.state() == GridState.OPENING_FIRST_POSITION && noOpenOrders(trackedWorkingOrders)) {
                return new RuntimeConsistencyResult(StrategyRuntimeState.initial(), true);
            }
            if (runtimeState.state() == GridState.GRID_ACTIVE || runtimeState.state() == GridState.REQUOTE) {
                return new RuntimeConsistencyResult(runtimeState.withState(GridState.WAITING_TRIGGER), true);
            }
            return new RuntimeConsistencyResult(runtimeState, false);
        }

        Map<String, WorkingOrder> byClientOrderId = trackedWorkingOrders.stream()
                .collect(Collectors.toMap(WorkingOrder::clientOrderId, Function.identity(), (left, right) -> right));

        WorkingOrder buy = activePair.buyOrder() == null
                ? null
                : byClientOrderId.get(activePair.buyOrder().clientOrderId());
        WorkingOrder sell = activePair.sellOrder() == null
                ? null
                : byClientOrderId.get(activePair.sellOrder().clientOrderId());
        boolean buyOpen = activePair.buyOrder() == null || isOpen(buy);
        boolean sellOpen = activePair.sellOrder() == null || isOpen(sell);

        if (buyOpen && sellOpen) {
            return new RuntimeConsistencyResult(runtimeState, false);
        }

        StrategyRuntimeState corrected = new StrategyRuntimeState(
                GridState.WAITING_TRIGGER,
                null,
                runtimeState.lastTriggerTime()
        );
        return new RuntimeConsistencyResult(corrected, true);
    }

    private boolean isOpen(WorkingOrder order) {
        return order != null && order.status() == OrderStatus.NEW;
    }

    private boolean noOpenOrders(Collection<WorkingOrder> orders) {
        return orders == null || orders.stream().noneMatch(this::isOpen);
    }
}
