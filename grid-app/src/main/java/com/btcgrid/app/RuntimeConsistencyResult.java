package com.btcgrid.app;

import com.btcgrid.strategy.model.StrategyRuntimeState;

public record RuntimeConsistencyResult(
        StrategyRuntimeState runtimeState,
        boolean corrected
) {
}
