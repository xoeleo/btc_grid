package com.btcgrid.app;

import com.btcgrid.common.model.ProductCategory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

public record RebalanceSettings(
        boolean enabled,
        RebalanceMode mode,
        BigDecimal autoMinTotalBtc,
        BigDecimal autoBaseGridQty,
        BigDecimal autoInitialSteps,
        ProductCategory category,
        String symbol,
        String accountType,
        String baseCoin,
        String quoteCoin,
        List<RebalanceBand> bands,
        BigDecimal ratioThreshold,
        BigDecimal baseGridSpacing,
        BigDecimal gridRequoteShrinkFactor,
        BigDecimal baseGridQty,
        BigDecimal maxInitialSteps,
        BigDecimal maxOrderQty,
        BigDecimal maxInventoryQty,
        BigDecimal priceOffsetRatio,
        BigDecimal bandHysteresisRatio,
        Duration orderTtl,
        int confirmationSamples,
        String configFilePath,
        String stateFilePath
) {
    public RebalanceSettings(
            boolean enabled,
            ProductCategory category,
            String symbol,
            String accountType,
            String baseCoin,
            String quoteCoin,
            List<RebalanceBand> bands,
            BigDecimal ratioThreshold,
            BigDecimal baseGridSpacing,
            BigDecimal gridRequoteShrinkFactor,
            BigDecimal baseGridQty,
            BigDecimal maxInitialSteps,
            BigDecimal maxOrderQty,
            BigDecimal maxInventoryQty,
            BigDecimal priceOffsetRatio,
            BigDecimal bandHysteresisRatio,
            Duration orderTtl,
            int confirmationSamples,
            String configFilePath,
            String stateFilePath
    ) {
        this(enabled, RebalanceMode.PRICE_TARGET, new BigDecimal("7"), new BigDecimal("0.005"),
                new BigDecimal("5"), category, symbol, accountType,
                baseCoin, quoteCoin, bands, ratioThreshold, baseGridSpacing, gridRequoteShrinkFactor,
                baseGridQty, maxInitialSteps, maxOrderQty, maxInventoryQty, priceOffsetRatio,
                bandHysteresisRatio, orderTtl, confirmationSamples, configFilePath, stateFilePath);
    }
}
