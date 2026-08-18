package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.common.model.FillEvent;
import java.util.List;
import java.util.Set;

public interface BybitOrderQueryGateway {

    List<WorkingOrder> fetchOpenOrders(String symbol);

    default List<WorkingOrder> fetchRecentOrders(String symbol, Set<String> clientOrderIds) {
        return List.of();
    }

    List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds);
}
