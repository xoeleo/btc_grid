package com.btcgrid.backtest.engine;

import java.math.BigDecimal;

public record BacktestExecutionConfig(
        BigDecimal makerFeeRate,
        BigDecimal takerFeeRate,
        BigDecimal marketSlippageBps,
        BigDecimal limitFillRatioPerTick,
        BigDecimal marketFillRatioPerTick,
        BigDecimal minFillQty,
        BigDecimal maxVolumeParticipationRate,
        BigDecimal minTouchFillRatio
) {
    public static BacktestExecutionConfig defaults() {
        return new BacktestExecutionConfig(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.00000001"),
                BigDecimal.ONE,
                BigDecimal.ONE
        );
    }
}
