package com.btcgrid.app;

import java.math.BigDecimal;
import java.time.Instant;

public record CurrentCycleSummary(
        String symbol,
        Instant startedAt,
        Instant latestFillAt,
        Instant generatedAt,
        long durationSeconds,
        int buyCount,
        int sellCount,
        int fillCount,
        BigDecimal buyQty,
        BigDecimal sellQty,
        BigDecimal cumulativeQty,
        BigDecimal cumulativeValue,
        BigDecimal cumulativeFee,
        BigDecimal cumulativeTradingFee,
        BigDecimal cumulativeFundingFee,
        BigDecimal profitableReduceQty,
        BigDecimal profitReduceBoostExtraQty,
        BigDecimal startRatio,
        BigDecimal startPrice,
        BigDecimal latestFillPrice,
        BigDecimal marketPrice,
        String entryDirection,
        BigDecimal fillPricePnl,
        BigDecimal markPricePnl
) {
}
