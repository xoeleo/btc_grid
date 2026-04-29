package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.WorkingOrder;
import java.util.List;

public interface BybitOrderQueryGateway {

    List<WorkingOrder> fetchOpenOrders(String symbol);
}
