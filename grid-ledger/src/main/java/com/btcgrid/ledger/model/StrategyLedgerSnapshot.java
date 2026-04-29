package com.btcgrid.ledger.model;

import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.time.Instant;

public record StrategyLedgerSnapshot(
        String symbol,
        BigDecimal netQty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal floatingPnl,
        BigDecimal previousFillPrice,
        BigDecimal lastFillPrice,
        TradeSide lastFillSide,
        BandZone lastFillZone,
        int consecutiveBuyFills,
        int consecutiveSellFills,
        InventoryMode inventoryMode,
        Instant updatedAt
) {
}
