package com.btcgrid.app;

import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.GridStrategyEngine;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class RuntimeStatusProvider {

    private final String symbol;
    private final GridStrategyEngine strategyEngine;
    private final StrategyLedger strategyLedger;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final AtomicReference<MarketSnapshot> latestMarketSnapshot;
    private final Set<String> processedExecutionIds;

    public RuntimeStatusProvider(
            String symbol,
            GridStrategyEngine strategyEngine,
            StrategyLedger strategyLedger,
            WorkingOrderRegistry workingOrderRegistry,
            AtomicReference<MarketSnapshot> latestMarketSnapshot,
            Set<String> processedExecutionIds
    ) {
        this.symbol = symbol;
        this.strategyEngine = strategyEngine;
        this.strategyLedger = strategyLedger;
        this.workingOrderRegistry = workingOrderRegistry;
        this.latestMarketSnapshot = latestMarketSnapshot;
        this.processedExecutionIds = processedExecutionIds;
    }

    public RuntimeStatusSnapshot snapshot() {
        return new RuntimeStatusSnapshot(
                Instant.now(),
                symbol,
                strategyEngine.runtimeState(),
                strategyLedger.snapshot(),
                latestMarketSnapshot.get(),
                processedExecutionIds.size(),
                List.copyOf(workingOrderRegistry.snapshot())
        );
    }
}
