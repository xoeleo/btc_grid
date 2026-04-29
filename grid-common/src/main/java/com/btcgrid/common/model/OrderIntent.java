package com.btcgrid.common.model;

import java.math.BigDecimal;

public record OrderIntent(
        String symbol,
        String clientOrderId,
        TradeSide side,
        OrderType type,
        BigDecimal qty,
        BigDecimal price,
        boolean reduceOnly,
        String reason
) {
    public boolean isMarket() {
        return type == OrderType.MARKET;
    }
}
