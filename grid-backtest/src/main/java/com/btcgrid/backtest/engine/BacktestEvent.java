package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.time.Instant;

public record BacktestEvent(
        Instant timestamp,
        Integer cycleId,
        String eventType,
        String clientOrderId,
        TradeSide side,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal amount,
        boolean reduceOnly,
        String reason,
        String tradeCategory,
        String orderContext,
        String qtyCalcDetail,
        BigDecimal bollUpper,
        BigDecimal bollMiddle,
        BigDecimal bollLower,
        GridState gridState,
        InventoryMode inventoryMode,
        BigDecimal netQty,
        BigDecimal positionAfterFill,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal realizedPnlDelta,
        BigDecimal floatingPnl,
        BigDecimal totalFees,
        BigDecimal totalPnl,
        BigDecimal netPnl,
        int openOrders,
        BigDecimal referencePrice,
        BigDecimal previousLegDistance,
        BigDecimal distanceMultiplier,
        BigDecimal baseSpacing,
        BigDecimal placementDistance,
        BigDecimal derivedSteps,
        BigDecimal effectiveSteps,
        Boolean minStepApplied
) {
}
