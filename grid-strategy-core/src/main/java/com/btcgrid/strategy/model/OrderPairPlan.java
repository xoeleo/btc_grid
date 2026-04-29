package com.btcgrid.strategy.model;

import com.btcgrid.common.model.OrderIntent;

public record OrderPairPlan(
        OrderIntent buyOrder,
        OrderIntent sellOrder
) {
}
