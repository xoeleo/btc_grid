package com.btcgrid.app;

import com.btcgrid.common.model.MarketSnapshot;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class RuntimeStatusPublisher {

    private final RuntimeStatusProvider runtimeStatusProvider;
    private final StatusStore statusStore;

    public RuntimeStatusPublisher(
            String symbol,
            com.btcgrid.strategy.service.GridStrategyEngine strategyEngine,
            com.btcgrid.ledger.service.StrategyLedger strategyLedger,
            WorkingOrderRegistry workingOrderRegistry,
            StatusStore statusStore,
            AtomicReference<MarketSnapshot> latestMarketSnapshot,
            Set<String> processedExecutionIds
    ) {
        this.runtimeStatusProvider = new RuntimeStatusProvider(
                symbol,
                strategyEngine,
                strategyLedger,
                workingOrderRegistry,
                latestMarketSnapshot,
                processedExecutionIds
        );
        this.statusStore = statusStore;
    }

    public void publish() {
        statusStore.save(runtimeStatusProvider.snapshot());
    }
}
