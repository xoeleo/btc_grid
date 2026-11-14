package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.WorkingOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StartupReconciliationService {

    private final BybitOrderQueryGateway orderQueryGateway;
    private final boolean enabled;

    public StartupReconciliationService(BybitOrderQueryGateway orderQueryGateway) {
        this(orderQueryGateway, true);
    }

    public StartupReconciliationService(BybitOrderQueryGateway orderQueryGateway, boolean enabled) {
        this.orderQueryGateway = orderQueryGateway;
        this.enabled = enabled;
    }

    public List<WorkingOrder> reconcile(String symbol, Collection<WorkingOrder> localTrackedOrders) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for startup reconciliation");
        }
        if (localTrackedOrders == null) {
            throw new IllegalArgumentException("local tracked orders are required for startup reconciliation");
        }

        Map<String, WorkingOrder> merged = new LinkedHashMap<>();
        for (WorkingOrder local : localTrackedOrders) {
            if (local == null || local.clientOrderId() == null || local.clientOrderId().isBlank()) {
                throw new IllegalStateException("local tracked order is missing clientOrderId");
            }
            merged.put(local.clientOrderId(), local);
        }

        // With credentials disabled, startup reconciliation is intentionally a
        // no-op. In particular, do not infer cancellation from the absence of
        // a remote query result.
        if (!enabled) {
            return new ArrayList<>(merged.values());
        }

        List<WorkingOrder> remoteOpenOrders = orderQueryGateway.fetchOpenOrders(symbol);
        if (remoteOpenOrders == null) {
            throw new IllegalStateException("startup open-order query returned no result");
        }
        for (WorkingOrder remote : remoteOpenOrders) {
            if (!isStrategyOrder(remote)
                    || remote.symbol() == null
                    || !symbol.equalsIgnoreCase(remote.symbol())) {
                // The account can contain orders for other symbols or other
                // clients. Never import those into this strategy's registry.
                continue;
            }
            WorkingOrder local = merged.get(remote.clientOrderId());
            if (local == null || !isTerminal(local.status())) {
                // A remote open order is authoritative only for an order that
                // is not already terminal in the persisted local state. A
                // terminal local record must not be resurrected by a stale or
                // inconsistent open-order response.
                merged.put(remote.clientOrderId(), new WorkingOrder(remote.symbol(), remote.clientOrderId(), remote.exchangeOrderId(),
                        remote.side(), remote.orderType(), remote.price(), remote.qty(), remote.reduceOnly(), remote.status(),
                        local == null || local.createdAt() == null ? remote.createdAt() : local.createdAt(), remote.updatedAt(),
                        local == null ? "unattributed-startup-order" : local.reason(),
                        local == null || local.cumulativeExecutedQty() == null ? remote.cumulativeExecutedQty()
                                : remote.cumulativeExecutedQty() == null ? local.cumulativeExecutedQty()
                                : remote.cumulativeExecutedQty().max(local.cumulativeExecutedQty()),
                        local != null && local.cancellationRequested()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private boolean isStrategyOrder(WorkingOrder order) {
        if (order == null || order.clientOrderId() == null) {
            return false;
        }
        String clientOrderId = order.clientOrderId();
        return clientOrderId.startsWith("GRID_") || clientOrderId.startsWith("MANUAL_FLATTEN_");
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED
                || status == OrderStatus.EXPIRED;
    }
}
