package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryExecutionSimulatorTest {

    @Test
    void fillsLimitOrdersWhenPriceCrosses() {
        InMemoryExecutionSimulator simulator = new InMemoryExecutionSimulator(
                new BacktestExecutionConfig(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        new BigDecimal("0.00000001"),
                        BigDecimal.ONE,
                        BigDecimal.ONE
                )
        );
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        simulator.apply(order("buy-1", TradeSide.BUY, OrderType.LIMIT, "0.01", "70000"), now);
        simulator.apply(order("sell-1", TradeSide.SELL, OrderType.LIMIT, "0.02", "71000"), now);

        List<FillEvent> fills = simulator.simulate(bar("69950", "70020", "69900", now.plusSeconds(60), "100"), simulator.openOrders());
        fills = append(fills, simulator.simulate(bar("71050", "71100", "70980", now.plusSeconds(120), "100"), simulator.openOrders()));

        assertEquals(2, fills.size());
        assertEquals("buy-1", fills.get(0).clientOrderId());
        assertEquals(new BigDecimal("70000"), fills.get(0).fillPrice());
        assertEquals("sell-1", fills.get(1).clientOrderId());
        assertEquals(new BigDecimal("71000"), fills.get(1).fillPrice());
        assertTrue(simulator.openOrders().isEmpty());
    }

    @Test
    void canceledOrdersAreExcludedFromOpenOrdersAndDoNotFill() {
        InMemoryExecutionSimulator simulator = new InMemoryExecutionSimulator();
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        simulator.apply(order("buy-2", TradeSide.BUY, OrderType.LIMIT, "0.01", "70000"), now);
        simulator.cancel("buy-2");

        List<FillEvent> fills = simulator.simulate(bar("69900", "70000", "69850", now.plusSeconds(60), "100"), simulator.openOrders());

        assertTrue(simulator.openOrders().isEmpty());
        assertTrue(fills.isEmpty());
    }

    @Test
    void marketOrdersFillImmediatelyAtSnapshotPrice() {
        InMemoryExecutionSimulator simulator = new InMemoryExecutionSimulator(
                new BacktestExecutionConfig(
                        new BigDecimal("0.0002"),
                        new BigDecimal("0.00055"),
                        new BigDecimal("5"),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        new BigDecimal("0.00000001"),
                        BigDecimal.ONE,
                        BigDecimal.ONE
                )
        );
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        simulator.apply(order("mkt-1", TradeSide.BUY, OrderType.MARKET, "0.03", null), now);

        List<FillEvent> fills = simulator.simulate(bar("70555.5", "70600", "70500", now.plusSeconds(5), "100"), simulator.openOrders());

        assertEquals(1, fills.size());
        assertEquals("mkt-1", fills.get(0).clientOrderId());
        assertEquals(new BigDecimal("70590.77775000"), fills.get(0).fillPrice());
        assertEquals(new BigDecimal("1.16474783"), simulator.feeFor(fills.get(0)));
        assertTrue(simulator.openOrders().isEmpty());
    }

    @Test
    void limitOrdersCanFillAcrossMultipleTicks() {
        InMemoryExecutionSimulator simulator = new InMemoryExecutionSimulator(
                new BacktestExecutionConfig(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("0.50"),
                        BigDecimal.ONE,
                        new BigDecimal("0.00000001"),
                        BigDecimal.ONE,
                        BigDecimal.ONE
                )
        );
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        simulator.apply(order("buy-partial", TradeSide.BUY, OrderType.LIMIT, "0.02", "70000"), now);

        List<FillEvent> firstFills = simulator.simulate(bar("69900", "70010", "69900", now.plusSeconds(60), "100"), simulator.openOrders());
        List<FillEvent> secondFills = simulator.simulate(bar("69850", "70010", "69850", now.plusSeconds(120), "100"), simulator.openOrders());

        assertEquals(1, firstFills.size());
        assertEquals(new BigDecimal("0.01000000"), firstFills.get(0).fillQty());
        assertEquals(1, secondFills.size());
        assertEquals(new BigDecimal("0.01000000"), secondFills.get(0).fillQty());
        assertTrue(simulator.openOrders().isEmpty());
    }

    @Test
    void candleVolumeParticipationCapsTotalFillsPerBar() {
        InMemoryExecutionSimulator simulator = new InMemoryExecutionSimulator(
                new BacktestExecutionConfig(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        new BigDecimal("0.00000001"),
                        new BigDecimal("0.10"),
                        BigDecimal.ONE
                )
        );
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        simulator.apply(order("buy-cap", TradeSide.BUY, OrderType.LIMIT, "0.03", "70000"), now);

        List<FillEvent> fills = simulator.simulate(bar("69900", "70020", "69880", now.plusSeconds(60), "0.20"), simulator.openOrders());

        assertEquals(1, fills.size());
        assertEquals(0, fills.get(0).fillQty().compareTo(new BigDecimal("0.020")));
    }

    private List<FillEvent> append(List<FillEvent> left, List<FillEvent> right) {
        java.util.ArrayList<FillEvent> combined = new java.util.ArrayList<>(left);
        combined.addAll(right);
        return combined;
    }

    private OrderIntent order(String id, TradeSide side, OrderType type, String qty, String price) {
        return new OrderIntent(
                "BTCUSDT",
                id,
                side,
                type,
                new BigDecimal(qty),
                price == null ? null : new BigDecimal(price),
                false,
                "test"
        );
    }

    private BacktestBar bar(String lastPrice, String high, String low, Instant timestamp, String volume) {
        return new BacktestBar(
                new MarketSnapshot(
                        "BTCUSDT",
                        timestamp,
                        new BigDecimal(lastPrice),
                        new IndicatorSnapshot(
                                timestamp,
                                new BigDecimal("70000"),
                                new BigDecimal("72000"),
                                new BigDecimal("70000"),
                                new BigDecimal("68000")
                        )
                ),
                new com.btcgrid.common.model.Candle(
                        "BTCUSDT",
                        timestamp.minusSeconds(14400),
                        timestamp,
                        new BigDecimal(lastPrice),
                        new BigDecimal(high),
                        new BigDecimal(low),
                        new BigDecimal(lastPrice),
                        new BigDecimal(volume)
                )
        );
    }
}
