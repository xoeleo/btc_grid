package com.btcgrid.strategy.model;

import com.btcgrid.common.model.GridState;
import java.time.Instant;

public record StrategyRuntimeState(
        GridState state,
        ActiveOrderPair activeOrderPair,
        Instant lastTriggerTime
) {
    public static StrategyRuntimeState initial() {
        return new StrategyRuntimeState(GridState.INIT, null, Instant.EPOCH);
    }

    public StrategyRuntimeState withState(GridState newState) {
        return new StrategyRuntimeState(newState, activeOrderPair, lastTriggerTime);
    }

    public StrategyRuntimeState withActiveOrderPair(ActiveOrderPair pair) {
        return new StrategyRuntimeState(state, pair, lastTriggerTime);
    }

    public StrategyRuntimeState withLastTriggerTime(Instant instant) {
        return new StrategyRuntimeState(state, activeOrderPair, instant);
    }
}
