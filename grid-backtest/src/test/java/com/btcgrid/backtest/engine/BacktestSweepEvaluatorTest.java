package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BacktestSweepEvaluatorTest {

    @Test
    void ranksByConfiguredScoreAndAppliesFilters() {
        BacktestSweepEvaluator evaluator = new BacktestSweepEvaluator();
        List<BacktestSweepOutcome> ranked = evaluator.rank(
                List.of(
                        outcome("case-001", "10", "8", "0.60", "0.30"),
                        outcome("case-002", "9", "3", "0.40", "0.20"),
                        outcome("case-003", "12", "10", "0.80", "0.90")
                ),
                new BacktestSweepSelection(
                        BacktestSweepScoreMode.NET_PNL_MINUS_HALF_DRAWDOWN,
                        new BigDecimal("8"),
                        new BigDecimal("9"),
                        new BigDecimal("0.35"),
                        new BigDecimal("0.50")
                )
        );

        assertEquals(2, ranked.size());
        assertEquals("case-002", ranked.get(0).caseId());
        assertEquals("case-001", ranked.get(1).caseId());
    }

    private BacktestSweepOutcome outcome(
            String caseId,
            String netPnl,
            String maxDrawdown,
            String winRate,
            String peakInventoryQty
    ) {
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        return new BacktestSweepOutcome(
                caseId,
                Map.of(),
                new BacktestResult(
                        now,
                        now.plusSeconds(1),
                        1,
                        1,
                        0,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal(netPnl),
                        new BigDecimal(netPnl),
                        new BigDecimal(maxDrawdown),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0,
                        0,
                        0,
                        new BigDecimal(winRate),
                        BigDecimal.ZERO,
                        new BigDecimal(peakInventoryQty),
                        BigDecimal.ZERO,
                        GridState.GRID_ACTIVE,
                        InventoryMode.NORMAL,
                        0,
                        List.of(),
                        null,
                        List.of(),
                        List.of()
                )
        );
    }
}
