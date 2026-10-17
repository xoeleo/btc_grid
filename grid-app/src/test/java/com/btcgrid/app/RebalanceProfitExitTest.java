package com.btcgrid.app;

import com.btcgrid.bybit.adapter.*;
import com.btcgrid.common.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RebalanceProfitExitTest {
    static final Instant START = Instant.parse("2026-09-20T00:00:00Z");
    static BigDecimal d(String value) { return new BigDecimal(value); }
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test void profitableShortLimitReservesAllFeesAndRoundsDown() {
        BigDecimal price = RebalanceProfitExit.limitPrice(d("-0.375"), d("-31211.8003"), d("37.99978399"), d("0.001"), d("1"), d("0.1"));
        BigDecimal pnl = d("-0.375").multiply(price).add(d("31211.8003")).subtract(d("37.99978399"))
                .subtract(d("0.375").multiply(price).multiply(d("0.001")));
        assertTrue(pnl.compareTo(BigDecimal.ONE) >= 0);
        assertTrue(pnl.compareTo(d("1.04")) < 0);
    }
    @Test void profitableLongLimitReservesFeesAndRoundsUp() {
        BigDecimal price = RebalanceProfitExit.limitPrice(d("0.375"), d("30000"), d("38"), d("0.001"), d("1"), d("0.1"));
        BigDecimal pnl = d("0.375").multiply(price).subtract(d("30000")).subtract(d("38"))
                .subtract(d("0.375").multiply(price).multiply(d("0.001")));
        assertTrue(pnl.compareTo(BigDecimal.ONE) >= 0);
        assertTrue(pnl.compareTo(d("1.04")) < 0);
    }
    @Test void lossOrExactlyZeroKeepsGridRunningButControlRemainsArmed() throws Exception {
        Fixture f = new Fixture(); f.pnl = d("-1000");
        assertFalse(f.poll()); assertTrue(f.exit.armed()); assertFalse(f.exit.frozen());
        f.pnl = BigDecimal.ZERO; assertFalse(f.poll()); assertTrue(f.submitted.isEmpty());
    }
    @Test void triggerUsesMarketPnlNotFeeAdjustedThresholdAndClosesCycleInventoryInChunks() throws Exception {
        Fixture f = new Fixture(); f.pnl = d("0.01");
        assertTrue(f.poll()); assertTrue(f.exit.frozen());
        assertEquals(1, f.submitted.size());
        OrderIntent order = f.submitted.get(0);
        assertEquals(TradeSide.BUY, order.side()); assertEquals(OrderType.LIMIT, order.type());
        assertEquals(0, d("0.1").compareTo(order.qty())); assertFalse(order.reduceOnly());
        assertTrue(order.price().compareTo(d("90000")) < 0);
    }
    @Test void mismatchedOrAbsentCycleNeverStartsOrClosesAnything() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.exit.poll(f.market(), START.plusSeconds(1)));
        assertTrue(f.exit.poll(f.market(), null));
        assertTrue(f.submitted.isEmpty());
    }
    @Test void cancellationAckAndMissingHistoryCannotReleaseCloseOrder() throws Exception {
        Fixture f = new Fixture(); WorkingOrder grid = f.order("RBAL_grid", OrderStatus.NEW, d("0.005"), d("90000"));
        f.open.add(grid); f.registry.trackRemote(grid);
        assertTrue(f.poll()); assertEquals(List.of("RBAL_grid"), f.canceled); assertTrue(f.submitted.isEmpty());
        f.open.clear(); assertTrue(f.poll()); assertTrue(f.submitted.isEmpty());
        f.evidence.put("RBAL_grid", new RebalanceProfitExit.Evidence(grid.withStatus(OrderStatus.CANCELED, Instant.now(), "remote"), BigDecimal.ZERO, List.of()));
        assertTrue(f.poll()); assertEquals(1, f.submitted.size());
    }
    @Test void terminalOrderWithIncompleteExecutionsBlocksExit() throws Exception {
        Fixture f = new Fixture(); WorkingOrder grid = f.order("RBAL_grid", OrderStatus.NEW, d("0.005"), d("90000"));
        f.open.add(grid); f.poll(); f.open.clear();
        f.evidence.put("RBAL_grid", new RebalanceProfitExit.Evidence(grid.withStatus(OrderStatus.CANCELED, Instant.now(), "remote"), d("0.002"), List.of()));
        f.poll(); assertTrue(f.submitted.isEmpty());
    }
    @Test void submissionTimeoutAndRestartNeverDuplicateUnknownOrder() throws Exception {
        Fixture f = new Fixture(); f.failSubmit = true;
        assertThrows(IllegalStateException.class, f::poll);
        assertEquals(1, f.submitted.size());
        f.recreate(); f.poll(); assertEquals(1, f.submitted.size()); assertTrue(f.exit.frozen());
    }
    @Test void partialFillDoesNotSubmitSecondOrderAndFundingInvalidatesUnsafeLimit() throws Exception {
        Fixture f = new Fixture(); f.poll(); OrderIntent intent = f.submitted.get(0);
        FillEvent partial = new FillEvent("exec1", "BTCUSDT", intent.clientOrderId(), TradeSide.BUY,
                d("0.04"), intent.price(), Instant.now(), d("0.04").multiply(intent.price()), d("2"), "USDT");
        WorkingOrder working = f.order(intent.clientOrderId(), OrderStatus.NEW, intent.qty(), intent.price());
        f.open.add(working); f.evidence.put(intent.clientOrderId(), new RebalanceProfitExit.Evidence(working, d("0.04"), List.of(partial)));
        f.poll(); assertEquals(1, f.submitted.size()); assertEquals(d("-0.335"), f.qty);
        f.fees = f.fees.add(d("100")); f.poll(); assertTrue(f.canceled.contains(intent.clientOrderId()));
    }
    @Test void fullExecutionEndsOnlyOwnCycleAndRemainsDisabledAfterRestart() throws Exception {
        Fixture f = new Fixture(); f.qty = d("-0.05"); f.value = d("-4500"); f.poll();
        OrderIntent intent = f.submitted.get(0);
        FillEvent fill = new FillEvent("done", "BTCUSDT", intent.clientOrderId(), TradeSide.BUY,
                intent.qty(), intent.price(), Instant.now(), intent.qty().multiply(intent.price()), d("1"), "USDT");
        f.evidence.put(intent.clientOrderId(), new RebalanceProfitExit.Evidence(
                f.order(intent.clientOrderId(), OrderStatus.FILLED, intent.qty(), intent.price()), intent.qty(), List.of(fill)));
        f.poll(); assertTrue(f.exit.complete()); assertEquals(0, f.qty.signum());
        assertTrue(f.value.negate().subtract(f.fees).signum() > 0);
        f.recreate(); assertTrue(f.exit.poll(f.market(), null)); assertEquals(1, f.submitted.size());
    }
    @Test void corruptControlFailsClosed() throws Exception {
        Fixture f = new Fixture(); Files.writeString(f.path, "{}");
        assertThrows(IllegalStateException.class, f::recreate);
    }
    @Test void manualCloseFreezesImmediatelyUsesOppositeQuoteAndWaitsForConfirmedFill() throws Exception {
        Fixture f = new Fixture(); f.pnl = d("-1000"); f.qty = d("-0.05");
        f.exit.armManual(START);
        assertTrue(f.exit.frozen());
        f.recreate();
        assertTrue(f.poll());
        OrderIntent intent = f.submitted.get(0);
        assertEquals(TradeSide.BUY, intent.side());
        assertEquals(d("83000.1"), intent.price());
        assertEquals(0, d("0.05").compareTo(intent.qty()));
        assertFalse(f.exit.complete());
        FillEvent fill = new FillEvent("manual-fill", "BTCUSDT", intent.clientOrderId(), TradeSide.BUY,
                intent.qty(), intent.price(), Instant.now(), intent.qty().multiply(intent.price()), d("1"), "USDT");
        f.evidence.put(intent.clientOrderId(), new RebalanceProfitExit.Evidence(
                f.order(intent.clientOrderId(), OrderStatus.FILLED, intent.qty(), intent.price()), intent.qty(), List.of(fill)));
        f.poll();
        assertTrue(f.exit.complete());
        assertEquals(0, f.qty.signum());
        assertTrue(f.exit.releaseCompletedManual());
        assertFalse(Files.exists(f.path));
        assertFalse(f.exit.armed());
    }

    static class Fixture {
        final Path path;
        BigDecimal qty = d("-0.375"), value = d("-31211.8003"), fees = d("37.99978399"), pnl = d("5");
        boolean failSubmit;
        final List<OrderIntent> submitted = new ArrayList<>();
        final List<String> canceled = new ArrayList<>();
        final List<WorkingOrder> open = new ArrayList<>();
        final Map<String, RebalanceProfitExit.Evidence> evidence = new HashMap<>();
        final Set<String> applied = new HashSet<>();
        final WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RebalanceProfitExit exit;
        Fixture() throws Exception {
            path = Files.createTempDirectory("profit-exit-test").resolve("control.json");
            RebalanceProfitExit.Control c = new RebalanceProfitExit.Control(); c.cycleStartMillis = START.toEpochMilli();
            JSON.writeValue(path.toFile(), c); recreate();
        }
        void recreate() throws Exception {
            RebalanceSettings settings = new RebalanceSettings(true, ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                    List.of(new RebalanceBand(d("80000"), d("0.70"))), d("0.01"), d("0.001"), d("0.85"), d("0.005"),
                    d("5"), d("0.1"), d("5"), d("0.001"), d("0.001"), Duration.ofMinutes(15), 1, "unused", "unused");
            exit = new RebalanceProfitExit(path, settings, new InstrumentSpec("BTCUSDT", d("0.001"), d("0.001"), d("0.1"), d("0.1")),
                    new RebalanceProfitExit.Exchange() {
                        public List<WorkingOrder> openOrders() { return List.copyOf(open); }
                        public RebalanceProfitExit.Evidence evidence(String id) { return evidence.get(id); }
                        public BigDecimal bestOppositePrice(TradeSide side) { return side == TradeSide.BUY ? d("83000.1") : d("82999.9"); }
                    }, new BybitTradeGateway() {
                        public BybitOrderSubmission submit(OrderIntent intent) {
                            submitted.add(intent); if (failSubmit) throw new IllegalStateException("timeout");
                            return new BybitOrderSubmission("remote", intent.clientOrderId());
                        }
                        public void cancel(String symbol, String id) { canceled.add(id); }
                    }, registry, price -> cycle(), fill -> {
                        if (!applied.add(fill.executionId())) return;
                        BigDecimal signed = fill.side() == TradeSide.BUY ? fill.fillQty() : fill.fillQty().negate();
                        qty = qty.add(signed); value = value.add(signed.multiply(fill.fillPrice())); fees = fees.add(fill.fee());
                    }, new EventLogger() { public void info(String t, Map<String,Object> p) {} public void warn(String t, Map<String,Object> p) {} public void error(String t, Map<String,Object> p) {} });
        }
        CurrentCycleSummary cycle() {
            Map<String,Object> data = new HashMap<>(); data.put("symbol", "RBAL:BTCUSDT"); data.put("startedAt", START.toString());
            data.put("cumulativeQty", qty); data.put("cumulativeValue", value); data.put("cumulativeFee", fees); data.put("markPricePnl", pnl);
            return JSON.convertValue(data, CurrentCycleSummary.class);
        }
        MarketSnapshot market() { return new MarketSnapshot("BTCUSDT", Instant.now(), d("83000"), d("83000"), null); }
        boolean poll() { return exit.poll(market(), START); }
        WorkingOrder order(String id, OrderStatus status, BigDecimal q, BigDecimal price) {
            return new WorkingOrder("BTCUSDT", id, "remote", TradeSide.BUY, OrderType.LIMIT, price, q, false, status, Instant.now(), Instant.now());
        }
    }
}
