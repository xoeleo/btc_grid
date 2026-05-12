package com.btcgrid.app;

import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.GridStrategyEngine;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RecoveryBootstrap {

    private final StateStore stateStore;
    private final EventLogger eventLogger;

    public RecoveryBootstrap(StateStore stateStore) {
        this(stateStore, null);
    }

    public RecoveryBootstrap(StateStore stateStore, EventLogger eventLogger) {
        this.stateStore = stateStore;
        this.eventLogger = eventLogger;
    }

    public RecoveryState restore(GridStrategyEngine strategyEngine, StrategyLedger strategyLedger) {
        return stateStore.load()
                .map(snapshot -> restoreSnapshot(snapshot, strategyEngine, strategyLedger))
                .orElseGet(() -> new RecoveryState(ConcurrentHashMap.newKeySet(), List.of()));
    }

    private RecoveryState restoreSnapshot(
            StrategyStateSnapshot snapshot,
            GridStrategyEngine strategyEngine,
            StrategyLedger strategyLedger
    ) {
        String expectedSymbol = strategyLedger.snapshot().symbol();
        String snapshotSymbol = snapshot.ledgerSnapshot().symbol();
        if (!expectedSymbol.equalsIgnoreCase(snapshotSymbol)) {
            if (eventLogger != null) {
                eventLogger.warn("startup.state.ignored", java.util.Map.of(
                        "reason", "symbol mismatch",
                        "expectedSymbol", expectedSymbol,
                        "snapshotSymbol", snapshotSymbol
                ));
            }
            return new RecoveryState(ConcurrentHashMap.newKeySet(), List.of());
        }
        strategyLedger.restore(snapshot.ledgerSnapshot());
        strategyEngine.restore(snapshot.runtimeState(), snapshot.activeSpacing());
        Set<String> executionIds = ConcurrentHashMap.newKeySet();
        executionIds.addAll(snapshot.processedExecutionIds());
        return new RecoveryState(
                executionIds,
                snapshot.trackedWorkingOrders() == null ? List.of() : snapshot.trackedWorkingOrders()
        );
    }
}
