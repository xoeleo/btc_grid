package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record InstrumentSpec(
        String symbol,
        BigDecimal qtyStep,
        BigDecimal minOrderQty,
        BigDecimal priceTick,
        BigDecimal minPrice,
        BigDecimal minNotionalValue
) {
    public InstrumentSpec(
            String symbol,
            BigDecimal qtyStep,
            BigDecimal minOrderQty,
            BigDecimal priceTick,
            BigDecimal minPrice
    ) {
        this(symbol, qtyStep, minOrderQty, priceTick, minPrice, BigDecimal.ZERO);
    }

    public BigDecimal roundQty(BigDecimal qty) {
        if (qtyStep.signum() <= 0) {
            return qty;
        }
        return qty.divide(qtyStep, 0, RoundingMode.HALF_UP).multiply(qtyStep);
    }

    public BigDecimal roundQtyUp(BigDecimal qty) {
        if (qtyStep.signum() <= 0) {
            return qty;
        }
        return qty.divide(qtyStep, 0, RoundingMode.CEILING).multiply(qtyStep);
    }

    public BigDecimal normalizeQty(BigDecimal qty) {
        BigDecimal rounded = roundQty(qty);
        return rounded.compareTo(minOrderQty) < 0 ? minOrderQty : rounded;
    }

    public BigDecimal roundPrice(BigDecimal price) {
        if (priceTick.signum() <= 0) {
            return price;
        }
        BigDecimal rounded = price.divide(priceTick, 0, RoundingMode.HALF_UP).multiply(priceTick);
        return rounded.compareTo(minPrice) < 0 ? minPrice : rounded;
    }
}
