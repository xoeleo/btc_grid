package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.OrderIntent;

public interface BybitTradeGateway {

    void submit(OrderIntent orderIntent);

    void cancel(String symbol, String clientOrderId);
}
