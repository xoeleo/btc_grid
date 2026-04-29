package com.btcgrid.backtest.engine;

import java.util.stream.Stream;

public interface BacktestMarketDataFeed {

    Stream<BacktestBar> stream();
}
