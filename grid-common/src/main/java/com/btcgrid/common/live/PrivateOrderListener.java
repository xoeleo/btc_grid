package com.btcgrid.common.live;

import com.btcgrid.common.model.OrderUpdateEvent;

public interface PrivateOrderListener {

    void onOrderUpdate(OrderUpdateEvent orderUpdateEvent);
}
