package com.btcgrid.app;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RebalanceFillNotifierTest {
    private static final BigDecimal GRID = new BigDecimal("0.005");

    @Test
    void accumulatesPartialFillsAndNotifiesExactlyAtThresholdOnlyOnce() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, false);
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, false);
        notifier.accept(fill("b", "RBAL_1", "0.004", TradeSide.BUY), GRID, false);
        assertTrue(messages.isEmpty());
        notifier.accept(fill("c", "RBAL_1", "0.001", TradeSide.BUY), GRID, false);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("0.015 BTC"));
        notifier.accept(fill("d", "RBAL_1", "0.005", TradeSide.BUY), GRID, false);
        assertEquals(1, messages.size());
    }

    @Test
    void separatesOrdersAndIncludesLargeSell() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, false);
        notifier.accept(fill("b", "RBAL_2", "0.010", TradeSide.BUY), GRID, false);
        assertTrue(messages.isEmpty());
        notifier.accept(fill("c", "RBAL_3", "0.020", TradeSide.SELL), GRID, false);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("SELL"));
    }

    @Test
    void restoresPartialQuantityWithoutReplayingHistoricalNotifications() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, true);
        notifier.accept(fill("b", "RBAL_2", "0.020", TradeSide.BUY), GRID, true);
        assertTrue(messages.isEmpty());
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, false);
        notifier.accept(fill("c", "RBAL_2", "0.005", TradeSide.BUY), GRID, false);
        assertTrue(messages.isEmpty());
        notifier.accept(fill("d", "RBAL_1", "0.005", TradeSide.BUY), GRID, false);
        assertEquals(1, messages.size());
    }

    @Test
    void ignoresOtherStrategiesAndInvalidQuantities() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        notifier.accept(fill("a", "GRID_1", "1", TradeSide.BUY), GRID, false);
        notifier.accept(fill("b", "RBAL_1", "0", TradeSide.BUY), GRID, false);
        notifier.accept(fill("c", "RBAL_1", "1", TradeSide.BUY), BigDecimal.ZERO, false);
        assertTrue(messages.isEmpty());
    }

    private static FillEvent fill(String execution, String order, String qty, TradeSide side) {
        return new FillEvent(execution, "BTCUSDT", order, side, new BigDecimal(qty),
                new BigDecimal("100000"), Instant.parse("2026-09-11T00:00:00Z"));
    }
}
