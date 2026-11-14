package com.btcgrid.app;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Price levels are additive percentages of the original Cycle anchor, never compounded. */
final class DipBands {
    final BigDecimal anchor, interval;
    int deepest;

    DipBands(BigDecimal anchor, BigDecimal interval) {
        if (anchor.signum() <= 0 || interval.signum() <= 0 || interval.compareTo(BigDecimal.ONE) >= 0)
            throw new IllegalArgumentException("Positive anchor and interval in (0, 1) required");
        this.anchor = anchor;
        this.interval = interval;
    }

    int reached(BigDecimal price) {
        if (price.signum() <= 0) throw new IllegalArgumentException("Nonpositive price");
        return Math.max(deepest, anchor.subtract(price).max(BigDecimal.ZERO)
                .divide(anchor.multiply(interval), 0, RoundingMode.FLOOR).intValueExact());
    }

    BigDecimal node() { return anchor.multiply(BigDecimal.ONE.subtract(interval.multiply(BigDecimal.valueOf(deepest)))); }
    BigDecimal nextNode() { return anchor.multiply(BigDecimal.ONE.subtract(interval.multiply(BigDecimal.valueOf(deepest + 1L)))); }
    BigDecimal recovery() { return anchor.multiply(BigDecimal.ONE.subtract(interval.multiply(BigDecimal.valueOf(deepest)).divide(new BigDecimal("2")))); }
}
