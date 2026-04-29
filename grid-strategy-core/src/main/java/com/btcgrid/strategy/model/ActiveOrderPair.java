package com.btcgrid.strategy.model;

import com.btcgrid.common.model.OrderIntent;
import java.time.Instant;

public record ActiveOrderPair(
        String pairId,
        OrderIntent buyOrder,
        OrderIntent sellOrder,
        Instant createdAt
) {
    public boolean contains(String clientOrderId) {
        return (buyOrder != null && buyOrder.clientOrderId().equals(clientOrderId))
                || (sellOrder != null && sellOrder.clientOrderId().equals(clientOrderId));
    }

    public OrderIntent oppositeOf(String clientOrderId) {
        if (buyOrder != null && buyOrder.clientOrderId().equals(clientOrderId)) {
            return sellOrder;
        }
        if (sellOrder != null && sellOrder.clientOrderId().equals(clientOrderId)) {
            return buyOrder;
        }
        return null;
    }
}
