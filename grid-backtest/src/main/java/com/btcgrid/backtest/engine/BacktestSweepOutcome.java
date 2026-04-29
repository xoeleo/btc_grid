package com.btcgrid.backtest.engine;

import java.util.Map;

public record BacktestSweepOutcome(
        String caseId,
        Map<String, String> options,
        BacktestResult result
) {
}
