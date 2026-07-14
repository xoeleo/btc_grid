package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record WorkingOrder(
        String symbol,
        String clientOrderId,
        String exchangeOrderId,
        TradeSide side,
        OrderType orderType,
        BigDecimal price,
        BigDecimal qty,
        boolean reduceOnly,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        String reason
) {
    public WorkingOrder(
            String symbol,
            String clientOrderId,
            String exchangeOrderId,
            TradeSide side,
            OrderType orderType,
            BigDecimal price,
            BigDecimal qty,
            boolean reduceOnly,
            OrderStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                symbol,
                clientOrderId,
                exchangeOrderId,
                side,
                orderType,
                price,
                qty,
                reduceOnly,
                status,
                createdAt,
                updatedAt,
                null
        );
    }

    public WorkingOrder withStatus(OrderStatus nextStatus, Instant nextUpdatedAt, String nextExchangeOrderId) {
        return new WorkingOrder(
                symbol,
                clientOrderId,
                nextExchangeOrderId == null ? exchangeOrderId : nextExchangeOrderId,
                side,
                orderType,
                price,
                qty,
                reduceOnly,
                nextStatus,
                createdAt,
                nextUpdatedAt,
                reason
        );
    }
}
