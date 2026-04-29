package com.btcgrid.backtest.engine;

public enum BacktestSweepScoreMode {
    NET_PNL,
    NET_PNL_MINUS_DRAWDOWN,
    NET_PNL_MINUS_HALF_DRAWDOWN,
    PROFIT_FACTOR,
    WIN_RATE
}
