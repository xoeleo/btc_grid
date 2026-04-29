package com.btcgrid.common.config;

import com.btcgrid.common.model.ProductCategory;
import java.math.BigDecimal;
import java.time.Duration;

public record GridStrategyConfig(
        ProductCategory category,
        String symbol,
        EntryDirection entryDirection,
        BigDecimal baseGridSpacing,
        BigDecimal gridRequoteShrinkFactor,
        BigDecimal baseGridQty,
        BigDecimal maxInitialSteps,
        BigDecimal maxOrderQty,
        BigDecimal maxInventoryQty,
        BigDecimal maxDirectionalInventoryQty,
        int maxConsecutiveSameSideFills,
        int emaLength4h,
        int bollLength4h,
        BigDecimal bollStdDevMultiplier,
        BigDecimal deinventoryPnlThresholdRate,
        BigDecimal deinventoryBoostSlope,
        BigDecimal deinventoryBoostCap,
        boolean classicReduceSizing,
        Duration pairOrderTimeout,
        Duration triggerCooldown
) {
}
