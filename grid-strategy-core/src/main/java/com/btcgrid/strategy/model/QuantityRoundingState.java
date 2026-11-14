package com.btcgrid.strategy.model;

import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.math.MathContext;

public record QuantityRoundingState(
        BigDecimal buyCarryQty,
        BigDecimal sellCarryQty
) {
    public QuantityRoundingState {
        buyCarryQty = nonNegative(buyCarryQty);
        sellCarryQty = nonNegative(sellCarryQty);
    }

    public static QuantityRoundingState empty() {
        return new QuantityRoundingState(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal carryQty(TradeSide side) {
        return side == TradeSide.SELL ? sellCarryQty : buyCarryQty;
    }

    public QuantityRoundingState afterFill(
            TradeSide side,
            BigDecimal rawQty,
            BigDecimal roundedQty,
            BigDecimal orderQty,
            BigDecimal fillQty
    ) {
        if (side == null || orderQty == null || orderQty.signum() <= 0
                || fillQty == null || fillQty.signum() <= 0) {
            return this;
        }
        BigDecimal ratio = fillQty.divide(orderQty, MathContext.DECIMAL64).min(BigDecimal.ONE);
        BigDecimal delta = nonNegative(rawQty)
                .subtract(nonNegative(roundedQty))
                .multiply(ratio, MathContext.DECIMAL64);
        if (side == TradeSide.SELL) {
            return new QuantityRoundingState(buyCarryQty, sellCarryQty.add(delta).max(BigDecimal.ZERO));
        }
        return new QuantityRoundingState(buyCarryQty.add(delta).max(BigDecimal.ZERO), sellCarryQty);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
