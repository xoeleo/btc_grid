package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Candle(
        String symbol,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}
