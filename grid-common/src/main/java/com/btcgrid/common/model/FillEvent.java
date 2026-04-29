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
        Instant fillTime
) {
}
