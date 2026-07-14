package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record FundingFeeEvent(
        String executionId,
        String symbol,
        Instant time,
        BigDecimal fee,
        String feeCurrency
) {
}
