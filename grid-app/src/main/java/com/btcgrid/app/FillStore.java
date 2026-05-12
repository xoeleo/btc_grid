package com.btcgrid.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface FillStore {

    void save(FillRecord record);

    List<FillRecord> recent(int limit);

    List<FillRecord> recentCycles(int limit);

    List<FillRecord> cycleFills(String cycleExecutionId);

    CurrentCycleSummary currentCycle(String symbol, Instant now, BigDecimal markPrice);

    FillRecord latestTradingFill(String symbol);

    Set<String> filledClientOrderIds(String symbol, Collection<String> clientOrderIds);
}
