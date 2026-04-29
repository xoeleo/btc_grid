package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BacktestResult(
        Instant startTime,
        Instant endTime,
        long snapshotsProcessed,
        long ordersPlaced,
        long ordersCanceled,
        long fillsProcessed,
        BigDecimal finalNetQty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal floatingPnl,
        BigDecimal totalFees,
        BigDecimal totalPnl,
        BigDecimal netPnl,
        BigDecimal maxDrawdown,
        BigDecimal grossRealizedProfit,
        BigDecimal grossRealizedLoss,
        int winningCloseCount,
        int losingCloseCount,
        int breakevenCloseCount,
        BigDecimal winRate,
        BigDecimal profitFactor,
        BigDecimal peakInventoryQty,
        BigDecimal feeToGrossPnlRatio,
        GridState finalState,
        InventoryMode inventoryMode,
        int openOrders,
        List<CompletedCycle> completedCycles,
        CompletedCycle openCycle,
        List<BacktestEvent> events,
        List<BacktestEquityPoint> equityCurve
) {
}
