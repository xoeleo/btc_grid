package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderUpdateEvent(
        String symbol,
        String clientOrderId,
        String exchangeOrderId,
        OrderStatus status,
        TradeSide side,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal cumExecQty,
        Instant updatedAt
) {
}
