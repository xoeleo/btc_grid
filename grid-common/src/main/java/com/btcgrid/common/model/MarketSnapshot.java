package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketSnapshot(
        String symbol,
        Instant timestamp,
        BigDecimal lastPrice,
        BigDecimal markPrice,
        IndicatorSnapshot indicators
) {
    public MarketSnapshot(String symbol, Instant timestamp, BigDecimal lastPrice, IndicatorSnapshot indicators) {
        this(symbol, timestamp, lastPrice, lastPrice, indicators);
    }
}
