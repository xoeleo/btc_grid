package com.btcgrid.common.model;

import java.time.Instant;

public record ManagedOrder(
        OrderIntent intent,
        Instant createdAt,
        OrderStatus status
) {
    public ManagedOrder withStatus(OrderStatus newStatus) {
        return new ManagedOrder(intent, createdAt, newStatus);
    }
}
