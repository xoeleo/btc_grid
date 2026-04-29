package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.Candle;
import com.btcgrid.common.model.MarketSnapshot;

public record BacktestBar(
        MarketSnapshot snapshot,
        Candle candle
) {
}
