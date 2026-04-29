package com.btcgrid.common.config;

import java.math.BigDecimal;

public final class SpacingLadder {

    private SpacingLadder() {
    }

    public static BigDecimal multiplier(int consecutiveSameDirectionFills) {
        if (consecutiveSameDirectionFills >= 3) {
            return new BigDecimal("2.0");
        }
        if (consecutiveSameDirectionFills == 2) {
            return new BigDecimal("1.5");
        }
        if (consecutiveSameDirectionFills == 1) {
            return new BigDecimal("1.2");
        }
        return BigDecimal.ONE;
    }
}
