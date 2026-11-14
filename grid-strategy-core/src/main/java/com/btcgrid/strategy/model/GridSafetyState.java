package com.btcgrid.strategy.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Restart-safe admission and operator-stop evidence. Missing legacy evidence is not trusted. */
public record GridSafetyState(
        boolean stopRequested,
        String flattenClientOrderId,
        Instant executionsThrough,
        boolean ledgerTrusted,
        Map<String, BigDecimal> accountedExecutionQty
) {
    public GridSafetyState {
        accountedExecutionQty = accountedExecutionQty == null ? Map.of() : Map.copyOf(accountedExecutionQty);
    }
}
