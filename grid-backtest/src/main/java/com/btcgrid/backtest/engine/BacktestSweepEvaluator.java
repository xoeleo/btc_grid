package com.btcgrid.backtest.engine;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public class BacktestSweepEvaluator {

    public List<BacktestSweepOutcome> rank(List<BacktestSweepOutcome> outcomes, BacktestSweepSelection selection) {
        return outcomes.stream()
                .filter(outcome -> matches(outcome.result(), selection))
                .sorted(Comparator.comparing((BacktestSweepOutcome outcome) -> score(outcome.result(), selection.scoreMode()))
                        .reversed())
                .toList();
    }

    public BigDecimal score(BacktestResult result, BacktestSweepScoreMode scoreMode) {
        return switch (scoreMode) {
            case NET_PNL -> result.netPnl();
            case NET_PNL_MINUS_DRAWDOWN -> result.netPnl().subtract(result.maxDrawdown());
            case NET_PNL_MINUS_HALF_DRAWDOWN -> result.netPnl().subtract(result.maxDrawdown().multiply(new BigDecimal("0.5")));
            case PROFIT_FACTOR -> result.profitFactor();
            case WIN_RATE -> result.winRate();
        };
    }

    private boolean matches(BacktestResult result, BacktestSweepSelection selection) {
        if (selection.minNetPnl() != null && result.netPnl().compareTo(selection.minNetPnl()) < 0) {
            return false;
        }
        if (selection.maxDrawdown() != null && result.maxDrawdown().compareTo(selection.maxDrawdown()) > 0) {
            return false;
        }
        if (selection.minWinRate() != null && result.winRate().compareTo(selection.minWinRate()) < 0) {
            return false;
        }
        return selection.maxPeakInventoryQty() == null
                || result.peakInventoryQty().compareTo(selection.maxPeakInventoryQty()) <= 0;
    }
}
