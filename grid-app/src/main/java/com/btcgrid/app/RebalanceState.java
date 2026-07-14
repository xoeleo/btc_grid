package com.btcgrid.app;

import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RebalanceState(
        boolean enabled,
        String symbol,
        String status,
        BigDecimal targetRatio,
        BigDecimal currentRatio,
        BigDecimal baseBalance,
        BigDecimal contractPositionQty,
        BigDecimal netBaseQty,
        BigDecimal totalEquityUsdt,
        BigDecimal lastPrice,
        TradeSide activeSide,
        String activeClientOrderId,
        Instant activeOrderCreatedAt,
        WorkingOrder activeOrder,
        List<BigDecimal> ratioSamples,
        Instant updatedAt,
        String message
) {
    public static RebalanceState initial(boolean enabled, String symbol) {
        return new RebalanceState(
                enabled,
                symbol,
                "IDLE",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                List.of(),
                Instant.now(),
                ""
        );
    }
}
