package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import java.math.BigDecimal;
import java.time.Instant;

public record BacktestEquityPoint(
        Instant timestamp,
        BigDecimal lastPrice,
        BigDecimal netQty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal floatingPnl,
        BigDecimal totalFees,
        BigDecimal totalPnl,
        BigDecimal netPnl,
        GridState gridState,
        InventoryMode inventoryMode,
        int openOrders
) {
}
