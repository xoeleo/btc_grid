package com.btcgrid.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface FillStore {

    void save(FillRecord record);

    List<FillRecord> recent(int limit);

    List<FillRecord> recent(String symbol, int limit);

    List<FillRecord> recent(String symbol, int limit, int offset);

    List<FillRecord> recentTrading(String symbol, int limit, int offset);

    List<FillRecord> recentCycles(int limit);

    List<FillRecord> recentCycles(String symbol, int limit);

    List<FillRecord> recentCycles(String symbol, int limit, int offset);

    List<FillRecord> cycleFills(String cycleExecutionId);

    CycleFillPage cycleFillsPage(String cycleExecutionId, int limit, int offset);

    List<FillRecord> currentCycleFills(String symbol);

    CurrentCycleSummary currentCycle(String symbol, Instant now, BigDecimal markPrice);

    CurrentCycleSummary latestOpenCycle(Instant now, BigDecimal markPrice);

    FillRecord latestTradingFill(String symbol);

    Set<String> filledClientOrderIds(String symbol, Collection<String> clientOrderIds);
}
