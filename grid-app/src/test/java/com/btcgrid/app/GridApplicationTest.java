package com.btcgrid.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GridApplicationTest {

    @Test
    void estimatedCandidateOrderFeeCanTurnMarginalCycleProfitNegative() {
        BigDecimal result = GridApplication.afterEstimatedOrderFee(
                new BigDecimal("0.04028291"),
                new BigDecimal("64131.4"),
                new BigDecimal("0.006"),
                new BigDecimal("0.00016")
        );

        assertEquals(new BigDecimal("-0.021283234"), result);
    }

    @Test
    void estimatedCandidateOrderFeeKeepsClearlyProfitableCyclePositive() {
        BigDecimal result = GridApplication.afterEstimatedOrderFee(
                new BigDecimal("8.90136036"),
                new BigDecimal("64291.7"),
                new BigDecimal("0.008"),
                new BigDecimal("0.0002")
        );

        assertEquals(new BigDecimal("8.79849364"), result);
    }

    @Test
    void lossAddRequiresCycleLossToExceedOnePercent() {
        BigDecimal belowThreshold = GridApplication.lossRateAfterEstimatedOrderFee(
                new BigDecimal("-9"),
                new BigDecimal("1000"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                new BigDecimal("0.0002")
        );
        BigDecimal beyondThreshold = GridApplication.lossRateAfterEstimatedOrderFee(
                new BigDecimal("-11"),
                new BigDecimal("1000"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                new BigDecimal("0.0002")
        );

        assertFalse(belowThreshold.compareTo(new BigDecimal("-0.01")) < 0);
        assertTrue(beyondThreshold.compareTo(new BigDecimal("-0.01")) < 0);
    }

    @Test
    void recordsCycleEndAsSoonAsAppliedFillFlattensLedger() {
        assertTrue(LiveControlService.shouldRecordCycleEndAfterFill(BigDecimal.ZERO));
        assertTrue(LiveControlService.shouldRecordCycleEndAfterFill(new BigDecimal("0.000")));
        assertFalse(LiveControlService.shouldRecordCycleEndAfterFill(new BigDecimal("0.1")));
        assertFalse(LiveControlService.shouldRecordCycleEndAfterFill(null));
    }
}
