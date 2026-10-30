package com.btcgrid.strategy.model;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record StrategyStateSnapshot(
        StrategyLedgerSnapshot ledgerSnapshot,
        StrategyRuntimeState runtimeState,
        BigDecimal activeSpacing,
        Set<String> processedExecutionIds,
        List<WorkingOrder> trackedWorkingOrders,
        GridStrategyConfig cycleConfig,
        GridSafetyState safetyState
) {
    public StrategyStateSnapshot(
            StrategyLedgerSnapshot ledgerSnapshot, StrategyRuntimeState runtimeState,
            BigDecimal activeSpacing, Set<String> processedExecutionIds,
            List<WorkingOrder> trackedWorkingOrders, GridStrategyConfig cycleConfig) {
        this(ledgerSnapshot, runtimeState, activeSpacing, processedExecutionIds, trackedWorkingOrders, cycleConfig, null);
    }
}
