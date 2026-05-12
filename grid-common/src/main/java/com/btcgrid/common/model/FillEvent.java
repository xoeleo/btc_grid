package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record FillEvent(
        String executionId,
        String symbol,
        String clientOrderId,
        TradeSide side,
        BigDecimal fillQty,
        BigDecimal fillPrice,
        Instant fillTime,
        BigDecimal fillValue,
        BigDecimal fee,
        String feeCurrency
) {
    public FillEvent(
            String executionId,
            String symbol,
            String clientOrderId,
            TradeSide side,
            BigDecimal fillQty,
            BigDecimal fillPrice,
            Instant fillTime
    ) {
        this(
                executionId,
                symbol,
                clientOrderId,
                side,
                fillQty,
                fillPrice,
                fillTime,
                value(fillQty, fillPrice),
                BigDecimal.ZERO,
                "USDT"
        );
    }

    private static BigDecimal value(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return BigDecimal.ZERO;
        }
        return qty.multiply(price);
    }
}
