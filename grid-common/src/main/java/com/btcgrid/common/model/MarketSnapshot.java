package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketSnapshot(
        String symbol,
        Instant timestamp,
        BigDecimal lastPrice,
        IndicatorSnapshot indicators
) {
}
