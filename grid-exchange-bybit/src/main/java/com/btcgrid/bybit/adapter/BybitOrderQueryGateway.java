package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.common.model.FillEvent;
import java.util.List;
import java.time.Instant;
import java.util.Set;

public interface BybitOrderQueryGateway {

    List<WorkingOrder> fetchOpenOrders(String symbol);

    default List<WorkingOrder> fetchRecentOrders(String symbol, Set<String> clientOrderIds) {
        return List.of();
    }

    /** Production implementations must query this entire range or throw; never return partial pages. */
    default List<FillEvent> fetchExecutions(String symbol, Set<String> clientOrderIds, Instant startInclusive, Instant endInclusive) {
        throw new UnsupportedOperationException("Gateway must implement complete bounded execution recovery");
    }

    List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds);
}
