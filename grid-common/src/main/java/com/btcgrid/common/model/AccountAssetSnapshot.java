package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountAssetSnapshot(
        Instant timestamp,
        String accountType,
        String baseCoin,
        String quoteCoin,
        BigDecimal baseWalletBalance,
        BigDecimal quoteWalletBalance,
        BigDecimal contractPositionQty,
        BigDecimal netBaseQty,
        BigDecimal totalEquityUsdt
) {
    public BigDecimal baseValue(BigDecimal basePrice) {
        if (netBaseQty == null || basePrice == null) {
            return BigDecimal.ZERO;
        }
        return netBaseQty.multiply(basePrice);
    }
}
