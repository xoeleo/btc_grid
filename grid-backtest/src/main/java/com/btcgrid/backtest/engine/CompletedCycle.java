package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record CompletedCycle(
        int cycleId,
        Instant startTime,
        Instant endTime,
        Duration duration,
        TradeSide entrySide,
        BigDecimal entryPrice,
        TradeSide exitSide,
        BigDecimal exitPrice,
        BigDecimal maxInventoryQty,
        BigDecimal maxInventoryUsdt,
        BigDecimal maxLossPnl,
        BigDecimal realizedPnl,
        int fillCount
) {
}
