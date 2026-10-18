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

    @Test
    void includesCycleMarketPnlAndBeijingExecutionTimeWithoutOrderId() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        FillEvent execution = new FillEvent("a", "BTCUSDT", "RBAL_1", TradeSide.BUY,
                new BigDecimal("0.018"), new BigDecimal("81089.2"),
                Instant.parse("2026-10-17T21:29:59.623Z"));

        notifier.accept(execution, new BigDecimal("0.006"), false,
                () -> new BigDecimal("123.456"), "USDT");

        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue(message.contains("当前 Cycle 市场价盈亏：+123.46 USDT"));
        assertTrue(message.contains("成交时间（北京时间）：2026-10-18 05:29:59.623"));
        assertFalse(message.contains("RBAL_1"));
        assertFalse(message.contains("订单："));
        assertFalse(message.contains("2026-10-17T21:29:59.623Z"));
    }

    @Test
    void distinguishesLossZeroAndUnavailableCyclePnl() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        notifier.accept(fill("a", "RBAL_1", "0.020", TradeSide.SELL), GRID, false,
                () -> new BigDecimal("-4.2"), "USDT");
        notifier.accept(fill("b", "RBAL_2", "0.020", TradeSide.BUY), GRID, false,
                () -> BigDecimal.ZERO, "USDC");
        notifier.accept(fill("c", "RBAL_3", "0.020", TradeSide.BUY), GRID, false,
                () -> null, "USDT");

        assertTrue(messages.get(0).contains("当前 Cycle 市场价盈亏：-4.20 USDT"));
        assertTrue(messages.get(1).contains("当前 Cycle 市场价盈亏：0.00 USDC"));
        assertTrue(messages.get(2).contains("当前 Cycle 市场价盈亏：暂不可用"));
    }

    @Test
    void queriesCyclePnlOnlyForANewLiveNotification() {
        List<String> messages = new ArrayList<>();
        RebalanceFillNotifier notifier = new RebalanceFillNotifier(messages::add);
        java.util.function.Supplier<BigDecimal> unexpected = () -> {
            fail("No cycle query for partial, duplicate, historical or already-notified fills");
            return null;
        };
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, false, unexpected, "USDT");
        notifier.accept(fill("a", "RBAL_1", "0.010", TradeSide.BUY), GRID, false, unexpected, "USDT");
        notifier.accept(fill("b", "RBAL_2", "0.020", TradeSide.BUY), GRID, true, unexpected, "USDT");
        notifier.accept(fill("c", "RBAL_2", "0.005", TradeSide.BUY), GRID, false, unexpected, "USDT");
        notifier.accept(fill("d", "RBAL_1", "0.005", TradeSide.BUY), GRID, false,
                () -> BigDecimal.ONE, "USDT");
        notifier.accept(fill("e", "RBAL_1", "0.005", TradeSide.BUY), GRID, false, unexpected, "USDT");

        assertEquals(1, messages.size());
    }

    private static FillEvent fill(String execution, String order, String qty, TradeSide side) {
        return new FillEvent(execution, "BTCUSDT", order, side, new BigDecimal(qty),
                new BigDecimal("100000"), Instant.parse("2026-09-11T00:00:00Z"));
    }
}
