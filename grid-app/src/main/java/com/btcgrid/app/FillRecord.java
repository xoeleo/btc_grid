package com.btcgrid.app;

import java.math.BigDecimal;
import java.time.Instant;

public record FillRecord(
        Instant time,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal value,
        BigDecimal fee,
        String feeCurrency,
        BigDecimal cumulativeQty,
        BigDecimal cumulativeValue,
        BigDecimal cumulativeFee,
        BigDecimal netQty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal floatingPnlAtFill,
        BigDecimal totalPnlAtFill,
        String clientOrderId,
        String executionId,
        String configJson
) {
    public FillRecord(
            Instant time,
            String symbol,
            String side,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal value,
            BigDecimal fee,
            String feeCurrency,
            BigDecimal cumulativeQty,
            BigDecimal cumulativeValue,
            BigDecimal cumulativeFee,
            BigDecimal netQty,
            BigDecimal avgEntryPrice,
            BigDecimal realizedPnl,
            BigDecimal floatingPnlAtFill,
            BigDecimal totalPnlAtFill,
            String clientOrderId,
            String executionId
    ) {
        this(
                time,
                symbol,
                side,
                qty,
                price,
                value,
                fee,
                feeCurrency,
                cumulativeQty,
                cumulativeValue,
                cumulativeFee,
                netQty,
                avgEntryPrice,
                realizedPnl,
                floatingPnlAtFill,
                totalPnlAtFill,
                clientOrderId,
                executionId,
                null
        );
    }

    public FillRecord(
            Instant time,
            String symbol,
            String side,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal netQty,
            BigDecimal avgEntryPrice,
            BigDecimal realizedPnl,
            BigDecimal floatingPnlAtFill,
            BigDecimal totalPnlAtFill,
            String clientOrderId,
            String executionId
    ) {
        this(
                time,
                symbol,
                side,
                qty,
                price,
                qty == null || price == null ? BigDecimal.ZERO : qty.multiply(price),
                BigDecimal.ZERO,
                "USDT",
                netQty,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                netQty,
                avgEntryPrice,
                realizedPnl,
                floatingPnlAtFill,
                totalPnlAtFill,
                clientOrderId,
                executionId,
                null
        );
    }
}
