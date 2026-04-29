package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.OrderIntent;
import java.util.List;

public interface ExecutionSimulator {

    List<FillEvent> simulate(BacktestBar bar, List<OrderIntent> workingOrders);
}
