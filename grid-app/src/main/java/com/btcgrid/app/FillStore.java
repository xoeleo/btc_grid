package com.btcgrid.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import com.btcgrid.strategy.model.ProfitSizingState;

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

    FundingFeePage currentCycleFundingFeesPage(String symbol, int limit, int offset);

    CurrentCycleFillPage currentCycleFillsPage(
            String symbol,
            int limit,
            int offset,
            BigDecimal minPrice,
            BigDecimal maxPrice
    );

    default CurrentCycleFillPage currentCycleFillsPage(
            String symbol,
            int limit,
            int offset,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            boolean maxPriceExclusive
    ) {
        return currentCycleFillsPage(symbol, limit, offset, minPrice, maxPrice);
    }

    CurrentCycleSummary currentCycle(String symbol, Instant now, BigDecimal markPrice);

    ProfitSizingState profitSizingState(String symbol);

    CurrentCycleSummary latestOpenCycle(Instant now, BigDecimal markPrice);

    FillRecord latestTradingFill(String symbol);

    Set<String> filledClientOrderIds(String symbol, Collection<String> clientOrderIds);
}
