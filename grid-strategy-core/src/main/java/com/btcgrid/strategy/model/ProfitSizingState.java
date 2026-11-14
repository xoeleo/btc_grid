package com.btcgrid.strategy.model;

import java.math.BigDecimal;

public record ProfitSizingState(
        BigDecimal pendingReduceCarryQty,
        BigDecimal cumulativeExtraReduceQty,
        BigDecimal pendingAddCarryQty,
        BigDecimal cumulativeExtraAddQty,
        BigDecimal cumulativePermanentReduceQty,
        BigDecimal permanentReduceTargetQty
) {
    public ProfitSizingState(
            BigDecimal pendingReduceCarryQty,
            BigDecimal cumulativeExtraReduceQty,
            BigDecimal pendingAddCarryQty,
            BigDecimal cumulativeExtraAddQty
    ) {
        this(
                pendingReduceCarryQty,
                cumulativeExtraReduceQty,
                pendingAddCarryQty,
                cumulativeExtraAddQty,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    public ProfitSizingState {
        pendingReduceCarryQty = nonNegative(pendingReduceCarryQty);
        cumulativeExtraReduceQty = nonNegative(cumulativeExtraReduceQty);
        pendingAddCarryQty = nonNegative(pendingAddCarryQty);
        cumulativeExtraAddQty = nonNegative(cumulativeExtraAddQty);
        cumulativePermanentReduceQty = nonNegative(cumulativePermanentReduceQty);
        permanentReduceTargetQty = nonNegative(permanentReduceTargetQty);
    }

    public static ProfitSizingState empty() {
        return new ProfitSizingState(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    public BigDecimal releasableQty() {
        return cumulativeExtraReduceQty
                .subtract(cumulativePermanentReduceQty)
                .subtract(cumulativeExtraAddQty)
                .max(BigDecimal.ZERO);
    }

    public BigDecimal permanentReduceRemainingQty() {
        return permanentReduceTargetQty.subtract(cumulativePermanentReduceQty).max(BigDecimal.ZERO);
    }

    public ProfitSizingState withPermanentReduceTarget(BigDecimal targetQty) {
        return new ProfitSizingState(
                pendingReduceCarryQty,
                cumulativeExtraReduceQty,
                pendingAddCarryQty,
                cumulativeExtraAddQty,
                cumulativePermanentReduceQty,
                targetQty
        );
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
