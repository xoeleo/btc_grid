package com.btcgrid.app;

import java.math.BigDecimal;
import java.time.Instant;

/** Durable opt-in continuation; the original end/fills remain immutable. */
public record RebalanceCycleContinuation(
        String endExecutionId, String symbol, Instant cycleStartedAt, Instant pausedAt, Instant resumedAt,
        BigDecimal referencePrice, BigDecimal targetRatio, BigDecimal lowPrice,
        BigDecimal highPrice, boolean targetRearmed, boolean ready
) {
    public boolean contains(BigDecimal price) {
        return price != null && price.compareTo(lowPrice) >= 0 && price.compareTo(highPrice) <= 0;
    }
    public RebalanceCycleContinuation verified() {
        return new RebalanceCycleContinuation(endExecutionId,symbol,cycleStartedAt,pausedAt,resumedAt,
                referencePrice,targetRatio,lowPrice,highPrice,targetRearmed,true);
    }
    public RebalanceCycleContinuation rearmed() {
        return new RebalanceCycleContinuation(endExecutionId, symbol, cycleStartedAt, pausedAt, resumedAt,
                referencePrice, targetRatio, lowPrice, highPrice, true, ready);
    }
}
