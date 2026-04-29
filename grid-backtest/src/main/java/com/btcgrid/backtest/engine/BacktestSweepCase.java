package com.btcgrid.backtest.engine;

import java.util.Map;

public record BacktestSweepCase(
        String caseId,
        Map<String, String> options
) {
}
