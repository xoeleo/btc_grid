package com.btcgrid.ledger.service;

import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.ClientOrderIds;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

public class InMemoryStrategyLedger implements StrategyLedger {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final String symbol;
    private BigDecimal netQty = BigDecimal.ZERO;
    private BigDecimal avgEntryPrice = BigDecimal.ZERO;
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    private BigDecimal floatingPnl = BigDecimal.ZERO;
    private BigDecimal previousFillPrice = BigDecimal.ZERO;
    private BigDecimal lastFillPrice = BigDecimal.ZERO;
    private TradeSide lastFillSide;
    private BandZone lastFillZone = BandZone.INSIDE_BAND;
    private int consecutiveBuyFills;
    private int consecutiveSellFills;
    private InventoryMode inventoryMode = InventoryMode.NORMAL;
    private Instant updatedAt = Instant.EPOCH;

    public InMemoryStrategyLedger(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public synchronized StrategyLedgerSnapshot snapshot() {
        return new StrategyLedgerSnapshot(
                symbol,
                netQty,
                avgEntryPrice,
                realizedPnl,
                floatingPnl,
                previousFillPrice,
                lastFillPrice,
                lastFillSide,
                lastFillZone,
                consecutiveBuyFills,
                consecutiveSellFills,
                inventoryMode,
                updatedAt
        );
    }

    @Override
    public synchronized void restore(StrategyLedgerSnapshot snapshot) {
        if (!symbol.equals(snapshot.symbol())) {
            throw new IllegalArgumentException("Snapshot symbol mismatch. Expected " + symbol + " but got " + snapshot.symbol());
        }
        netQty = snapshot.netQty();
        avgEntryPrice = snapshot.avgEntryPrice();
        realizedPnl = snapshot.realizedPnl();
        floatingPnl = snapshot.floatingPnl();
        previousFillPrice = snapshot.previousFillPrice();
        lastFillPrice = snapshot.lastFillPrice();
        lastFillSide = snapshot.lastFillSide();
        lastFillZone = snapshot.lastFillZone();
        consecutiveBuyFills = snapshot.consecutiveBuyFills();
        consecutiveSellFills = snapshot.consecutiveSellFills();
        inventoryMode = snapshot.inventoryMode();
        updatedAt = snapshot.updatedAt();
    }

    @Override
    public synchronized void applyFill(FillEvent fillEvent) {
        boolean initialFill = ClientOrderIds.isInitialPositionOrder(fillEvent.clientOrderId());
        BigDecimal signedQty = fillEvent.netQtyDelta();
        BigDecimal fillAbsQty = signedQty.abs();
        BigDecimal newNetQty = netQty.add(signedQty, MATH_CONTEXT);

        if (netQty.signum() == 0 || Integer.signum(netQty.signum()) == Integer.signum(signedQty.signum())) {
            BigDecimal currentNotional = avgEntryPrice.multiply(netQty.abs(), MATH_CONTEXT);
            BigDecimal fillNotional = fillEvent.fillPrice().multiply(fillEvent.fillQty(), MATH_CONTEXT);
            BigDecimal newAbsQty = netQty.abs().add(fillAbsQty, MATH_CONTEXT);
            if (newAbsQty.signum() > 0) {
                avgEntryPrice = currentNotional.add(fillNotional, MATH_CONTEXT).divide(newAbsQty, MATH_CONTEXT);
            } else {
                avgEntryPrice = BigDecimal.ZERO;
            }
        } else {
            BigDecimal closedQty = fillAbsQty.min(netQty.abs());
            BigDecimal pnlPerUnit = fillEvent.side() == TradeSide.BUY
                    ? avgEntryPrice.subtract(fillEvent.fillPrice(), MATH_CONTEXT)
                    : fillEvent.fillPrice().subtract(avgEntryPrice, MATH_CONTEXT);
            realizedPnl = realizedPnl.add(pnlPerUnit.multiply(closedQty, MATH_CONTEXT), MATH_CONTEXT);

            if (newNetQty.signum() == 0) {
                avgEntryPrice = BigDecimal.ZERO;
            } else if (Integer.signum(newNetQty.signum()) != Integer.signum(netQty.signum())) {
                avgEntryPrice = fillEvent.fillPrice();
            }
        }

        netQty = newNetQty;
        updatedAt = fillEvent.fillTime();
        if (newNetQty.signum() == 0) {
            previousFillPrice = BigDecimal.ZERO;
            lastFillPrice = BigDecimal.ZERO;
            lastFillSide = null;
            consecutiveBuyFills = 0;
            consecutiveSellFills = 0;
        } else {
            previousFillPrice = initialFill ? BigDecimal.ZERO : lastFillPrice;
            lastFillPrice = fillEvent.fillPrice();
            lastFillSide = fillEvent.side();

            if (initialFill) {
                consecutiveBuyFills = 0;
                consecutiveSellFills = 0;
            } else if (fillEvent.side() == TradeSide.BUY) {
                consecutiveBuyFills += 1;
                consecutiveSellFills = 0;
            } else {
                consecutiveSellFills += 1;
                consecutiveBuyFills = 0;
            }
        }
    }

    @Override
    public synchronized void markToMarket(MarketSnapshot marketSnapshot) {
        if (netQty.signum() == 0) {
            floatingPnl = BigDecimal.ZERO;
        } else {
            BigDecimal pnlPerUnit = marketSnapshot.markPrice().subtract(avgEntryPrice, MATH_CONTEXT);
            floatingPnl = netQty.multiply(pnlPerUnit, MATH_CONTEXT);
        }
        lastFillZone = marketSnapshot.indicators().zoneOf(lastFillPrice.signum() == 0 ? marketSnapshot.lastPrice() : lastFillPrice);
        updatedAt = marketSnapshot.timestamp();
    }

    @Override
    public synchronized void switchInventoryMode(InventoryMode inventoryMode) {
        this.inventoryMode = inventoryMode;
    }
}
