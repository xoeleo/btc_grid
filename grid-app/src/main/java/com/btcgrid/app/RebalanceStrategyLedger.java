package com.btcgrid.app;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;

/** Every execution affects accounting; one exchange order advances the grid reference once. */
final class RebalanceStrategyLedger extends InMemoryStrategyLedger {
    private String referenceOrderId;
    RebalanceStrategyLedger(String symbol) { super(symbol); }
    synchronized void restoreReferenceOrder(String orderId) { referenceOrderId = orderId; }
    @Override public synchronized void applyFill(FillEvent fill) {
        StrategyLedgerSnapshot before = snapshot();
        boolean manual = RebalanceManualAdjustment.isManualOrder(fill.clientOrderId());
        boolean sameOrder = fill.clientOrderId() != null && fill.clientOrderId().equals(referenceOrderId)
                && fill.side() == before.lastFillSide();
        super.applyFill(fill);
        StrategyLedgerSnapshot after = snapshot();
        if (after.netQty().signum() != 0 && (sameOrder || manual)) {
            super.restore(new StrategyLedgerSnapshot(after.symbol(),after.netQty(),after.avgEntryPrice(),
                    after.realizedPnl(),after.floatingPnl(),before.previousFillPrice(),
                    manual ? before.lastFillPrice() : after.lastFillPrice(),
                    manual ? before.lastFillSide() : after.lastFillSide(),before.lastFillZone(),
                    before.consecutiveBuyFills(),before.consecutiveSellFills(),after.inventoryMode(),after.updatedAt()));
        }
        if (after.netQty().signum() == 0) referenceOrderId = null;
        else if (!manual) referenceOrderId = fill.clientOrderId();
    }
}
