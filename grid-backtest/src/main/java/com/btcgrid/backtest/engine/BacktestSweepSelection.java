package com.btcgrid.backtest.engine;

import java.math.BigDecimal;

public record BacktestSweepSelection(
        BacktestSweepScoreMode scoreMode,
        BigDecimal minNetPnl,
        BigDecimal maxDrawdown,
        BigDecimal minWinRate,
        BigDecimal maxPeakInventoryQty
) {
    public static BacktestSweepSelection defaults() {
        return new BacktestSweepSelection(
                BacktestSweepScoreMode.NET_PNL,
                null,
                null,
                null,
                null
        );
    }
}
