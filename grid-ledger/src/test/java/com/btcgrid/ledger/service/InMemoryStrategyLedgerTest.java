package com.btcgrid.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryStrategyLedgerTest {

    @Test
    void initialFillDoesNotStartConsecutiveSequence() {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("BTCUSDT");

        ledger.applyFill(fill("BTCUSDT_INIT_1", TradeSide.BUY, "1", "100", Instant.parse("2026-01-01T00:00:00Z")));
        StrategyLedgerSnapshot afterInit = ledger.snapshot();
        assertEquals(new BigDecimal("0"), afterInit.previousFillPrice());
        assertEquals(new BigDecimal("100"), afterInit.lastFillPrice());
        assertEquals(0, afterInit.consecutiveBuyFills());
        assertEquals(0, afterInit.consecutiveSellFills());

        ledger.applyFill(fill("PAIR_BUY_1", TradeSide.BUY, "1", "110", Instant.parse("2026-01-01T00:05:00Z")));
        StrategyLedgerSnapshot afterFirstFollow = ledger.snapshot();
        assertEquals(new BigDecimal("100"), afterFirstFollow.previousFillPrice());
        assertEquals(new BigDecimal("110"), afterFirstFollow.lastFillPrice());
        assertEquals(1, afterFirstFollow.consecutiveBuyFills());

        ledger.applyFill(fill("PAIR_BUY_2", TradeSide.BUY, "1", "120", Instant.parse("2026-01-01T00:10:00Z")));
        StrategyLedgerSnapshot afterSecondFollow = ledger.snapshot();
        assertEquals(new BigDecimal("110"), afterSecondFollow.previousFillPrice());
        assertEquals(new BigDecimal("120"), afterSecondFollow.lastFillPrice());
        assertEquals(2, afterSecondFollow.consecutiveBuyFills());
    }

    @Test
    void flatPositionResetsPreviousLegState() {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("BTCUSDT");

        ledger.applyFill(fill("BTCUSDT_INIT_1", TradeSide.BUY, "1", "100", Instant.parse("2026-01-01T00:00:00Z")));
        ledger.applyFill(fill("PAIR_BUY_1", TradeSide.BUY, "1", "110", Instant.parse("2026-01-01T00:05:00Z")));
        ledger.applyFill(fill("PAIR_SELL_1", TradeSide.SELL, "2", "120", Instant.parse("2026-01-01T00:10:00Z")));

        StrategyLedgerSnapshot flatSnapshot = ledger.snapshot();
        assertEquals(BigDecimal.ZERO, flatSnapshot.netQty());
        assertEquals(BigDecimal.ZERO, flatSnapshot.previousFillPrice());
        assertEquals(BigDecimal.ZERO, flatSnapshot.lastFillPrice());
        assertNull(flatSnapshot.lastFillSide());
        assertEquals(0, flatSnapshot.consecutiveBuyFills());
        assertEquals(0, flatSnapshot.consecutiveSellFills());
    }

    private FillEvent fill(String clientOrderId, TradeSide side, String qty, String price, Instant time) {
        return new FillEvent(
                clientOrderId + "-exec",
                "BTCUSDT",
                clientOrderId,
                side,
                new BigDecimal(qty),
                new BigDecimal(price),
                time
        );
    }
}
