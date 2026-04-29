package com.btcgrid.app;

import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import java.time.Instant;
import java.util.List;

public record RuntimeStatusSnapshot(
        Instant generatedAt,
        String symbol,
        StrategyRuntimeState runtimeState,
        StrategyLedgerSnapshot ledgerSnapshot,
        MarketSnapshot latestMarketSnapshot,
        int processedExecutionCount,
        List<WorkingOrder> workingOrders
) {
}
