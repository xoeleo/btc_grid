package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.WorkingOrder;
import java.time.Instant;
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
        List<WorkingOrder> remoteOpenOrders = enabled ? orderQueryGateway.fetchOpenOrders(symbol) : List.of();
        Map<String, WorkingOrder> merged = new LinkedHashMap<>();
        for (WorkingOrder remote : remoteOpenOrders) {
            merged.put(remote.clientOrderId(), remote);
        }
        Instant now = Instant.now();
        for (WorkingOrder local : localTrackedOrders) {
            merged.putIfAbsent(local.clientOrderId(), local.withStatus(OrderStatus.CANCELED, now, local.exchangeOrderId()));
        }
        return new ArrayList<>(merged.values());
    }
}
