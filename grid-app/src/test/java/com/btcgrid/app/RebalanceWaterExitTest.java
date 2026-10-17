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

class RebalanceWaterExitTest {
    static final Instant START = Instant.parse("2026-09-20T00:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test void losingAndExactlyBreakevenRunGridButProfitPausesBothSides() throws Exception {
        Fixture f = new Fixture();
        f.price = d("99000"); assertFalse(f.poll()); assertEquals("GRID", f.exit.phase());
        f.price = d("100000"); assertFalse(f.poll());
        f.price = d("100001"); assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase());
        assertTrue(f.submitted.isEmpty());
    }
    @Test void cancelAckUnknownHistoryAndIncompleteFillsNeverReleaseCloseOrder() throws Exception {
        Fixture f = new Fixture(); f.price = d("101000");
        WorkingOrder old = f.order("RBAL_grid", TradeSide.BUY, OrderStatus.NEW, d("0.006"), d("99000"));
        f.open.add(old); f.registry.trackRemote(old);
        assertTrue(f.poll()); assertEquals(List.of(old.clientOrderId()), f.canceled); assertTrue(f.submitted.isEmpty());
        f.open.clear(); f.poll(); assertTrue(f.submitted.isEmpty());
        f.evidence.put(old.clientOrderId(), new RebalanceProfitExit.Evidence(old.withStatus(OrderStatus.CANCELED, Instant.now(), ""), d("0.001"), List.of()));
        f.poll(); assertTrue(f.submitted.isEmpty());
        FillEvent fill = f.fill(old.clientOrderId(), TradeSide.BUY, d("0.001"), d("99000"));
        f.evidence.put(old.clientOrderId(), new RebalanceProfitExit.Evidence(old.withStatus(OrderStatus.CANCELED, Instant.now(), ""), d("0.001"), List.of(fill)));
        f.poll(); assertEquals(1, f.submitted.size());
        assertEquals(0, d("0.050").compareTo(f.submitted.get(0).qty()));
        assertEquals(0, d("0.101").compareTo(f.qty));
    }
    @Test void halfOnceThenDynamicWaterAndSecondExcursionHalvesAgain() throws Exception {
        Fixture f = new Fixture(); f.price = d("101000"); f.poll();
        OrderIntent first = f.submitted.get(0);
        assertEquals(TradeSide.SELL, first.side()); assertEquals(0, d("0.050").compareTo(first.qty()));
        assertFalse(first.reduceOnly());
        f.finish(first); f.price = d("100000"); f.poll(); assertEquals("PAUSED", f.exit.phase());
        BigDecimal waterAfter = RebalanceWaterExit.water(f.cycle());
        assertEquals(0, d("99000").compareTo(waterAfter));
        f.recreate(); f.poll(); assertEquals(1, f.submitted.size()); // halfDone survives restart
        f.price = d("98900"); assertFalse(f.poll()); assertEquals("GRID", f.exit.phase());
        f.price = d("99990"); assertTrue(f.poll());
        assertEquals(1, f.submitted.size()); assertEquals("PAUSED", f.exit.phase());
        f.price = d("100000"); f.poll(); assertEquals(1, f.submitted.size());
        f.price = d("100000.1"); f.poll();
        assertEquals(2, f.submitted.size()); assertEquals(0, d("0.025").compareTo(f.submitted.get(1).qty()));
    }
    @Test void twoPercentJumpsDirectlyToFullCloseAndEndsOnlyAfterConfirmedExecution() throws Exception {
        Fixture f = new Fixture(); f.price = d("102000"); assertTrue(f.poll());
        assertFalse(f.exit.complete()); OrderIntent all = f.submitted.get(0);
        assertEquals(0, f.qty.compareTo(all.qty()));
        f.price = d("99000"); f.poll(); assertEquals("EXITING", f.exit.phase()); // full exit committed
        f.finish(all); f.poll(); assertTrue(f.exit.complete()); assertEquals(0, f.qty.signum());
        f.recreate(); assertFalse(f.exit.poll(f.market(), null, false)); // allow next enabled monitor
        f.qty = d("0.1"); f.value = d("10000"); f.start = START.plusSeconds(100);
        f.price = d("99000"); assertFalse(f.exit.poll(f.market(), f.start, false));
        assertEquals("GRID", f.exit.phase());
    }
    @Test void shortUsesMirroredThresholdsAndBuysBackHalfThenAll() throws Exception {
        Fixture f = new Fixture(); f.qty = d("-0.1"); f.value = d("-10000");
        f.entryDirection = "SHORT";
        f.price = d("101000"); assertFalse(f.poll());
        f.price = d("99999"); assertTrue(f.poll()); assertTrue(f.submitted.isEmpty());
        f.price = d("99000"); f.poll(); OrderIntent half = f.submitted.get(0);
        assertEquals(TradeSide.BUY, half.side()); assertEquals(0, d("0.05").compareTo(half.qty()));
        f.finish(half); f.poll(); assertEquals("PAUSED", f.exit.phase());
        assertEquals(0, d("101000").compareTo(RebalanceWaterExit.water(f.cycle())));
        f.price = d("98980"); f.poll(); OrderIntent all = f.submitted.get(1);
        assertEquals(TradeSide.BUY, all.side()); assertEquals(0, d("0.05").compareTo(all.qty()));
        f.finish(all); f.poll(); assertTrue(f.exit.complete());
    }
    @Test void fundingChangesWaterAndCanResumeGridWithoutPriceChange() throws Exception {
        Fixture f = new Fixture(); f.price = d("100500"); f.poll(); assertEquals("PAUSED", f.exit.phase());
        f.fees = d("200"); assertFalse(f.poll()); assertEquals("GRID", f.exit.phase());
        assertEquals(0, d("102000").compareTo(RebalanceWaterExit.water(f.cycle())));
        f.price = d("103020"); f.poll(); assertEquals(1, f.submitted.size());
    }
    @Test void longHalfAndFullWaitUntilPriceIsStrictlyAboveCycleEntry() throws Exception {
        for (String trigger : List.of("101000", "102000")) {
            Fixture f = new Fixture(); f.startPrice = d("103000"); f.price = d(trigger);
            assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase());
            assertTrue(f.submitted.isEmpty());
            assertTrue(f.exit.message().contains("高于开单价"));
            f.recreate(); f.poll(); assertTrue(f.submitted.isEmpty());
            f.price = f.startPrice; f.poll(); assertTrue(f.submitted.isEmpty());
            f.price = d("103000.1"); f.poll();
            assertEquals("EXITING", f.exit.phase()); assertEquals(1, f.submitted.size());
            assertEquals(TradeSide.SELL, f.submitted.get(0).side());
            assertEquals(0, f.qty.compareTo(f.submitted.get(0).qty()));
        }
    }
    @Test void shortHalfAndFullWaitUntilPriceIsStrictlyBelowCycleEntry() throws Exception {
        for (String trigger : List.of("99000", "98000")) {
            Fixture f = new Fixture(); f.qty = d("-0.1"); f.value = d("-10000");
            f.entryDirection = "SHORT"; f.startPrice = d("97000"); f.price = d(trigger);
            assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase());
            assertTrue(f.submitted.isEmpty());
            f.recreate(); f.poll(); assertTrue(f.submitted.isEmpty());
            f.price = f.startPrice; f.poll(); assertTrue(f.submitted.isEmpty());
            f.price = d("96999.9"); f.poll();
            assertEquals("EXITING", f.exit.phase()); assertEquals(1, f.submitted.size());
            assertEquals(TradeSide.BUY, f.submitted.get(0).side());
            assertEquals(0, f.qty.abs().compareTo(f.submitted.get(0).qty()));
        }
    }
    @Test void favorableEntryPriceDoesNotReplaceWaterProfitThresholds() throws Exception {
        for (String direction : List.of("LONG", "SHORT")) {
            Fixture f = new Fixture(); f.entryDirection = direction;
            if ("SHORT".equals(direction)) { f.qty = f.qty.negate(); f.value = f.value.negate(); }
            f.price = d("LONG".equals(direction) ? "100999.9" : "99000.1");
            assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase());
            assertTrue(f.submitted.isEmpty());
            f.price = d("LONG".equals(direction) ? "101000" : "99000"); f.poll();
            assertEquals("HALF", f.exit.phase()); assertEquals(1, f.submitted.size());
            assertEquals(0, d("0.05").compareTo(f.submitted.get(0).qty()));
        }
    }
    @Test void missingOrInvalidEntryDetailsBlockAutomaticThresholdExits() throws Exception {
        for (BigDecimal entry : Arrays.asList(null, BigDecimal.ZERO, d("-1"))) {
            Fixture f = new Fixture(); f.startPrice = entry; f.price = d("102000");
            assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase()); assertTrue(f.submitted.isEmpty());
        }
        for (String direction : Arrays.asList(null, "UNKNOWN")) {
            Fixture f = new Fixture(); f.entryDirection = direction; f.price = d("102000");
            assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase()); assertTrue(f.submitted.isEmpty());
        }
    }
    @Test void nonpositiveDynamicWaterStillRequiresFavorableEntryPrice() throws Exception {
        Fixture f = new Fixture(); f.value = d("-100"); f.price = d("99999");
        assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase()); assertTrue(f.submitted.isEmpty());
        f.price = d("100000"); f.poll(); assertTrue(f.submitted.isEmpty());
        f.price = d("100000.1"); f.poll();
        assertEquals("EXITING", f.exit.phase()); assertEquals(1, f.submitted.size());
    }
    @Test void partialFillWaitsAndRequoteWaitsForTerminalEvidenceBeforeReplacement() throws Exception {
        Fixture f = new Fixture(); f.price = d("101000"); f.poll(); OrderIntent half = f.submitted.get(0);
        f.partial(half, d("0.02")); f.poll(); assertEquals(1, f.submitted.size());
        assertEquals(0, d("0.08").compareTo(f.qty));
        f.price = d("101100"); f.poll(); assertTrue(f.canceled.contains(half.clientOrderId()));
        assertEquals(1, f.submitted.size());
        WorkingOrder row = f.evidence.get(half.clientOrderId()).order();
        var ev = f.evidence.get(half.clientOrderId()); f.open.clear();
        f.evidence.put(half.clientOrderId(), new RebalanceProfitExit.Evidence(row.withStatus(OrderStatus.CANCELED, Instant.now(), ""), ev.executedQty(), ev.fills()));
        f.poll(); assertEquals(2, f.submitted.size());
        assertEquals(0, d("0.030").compareTo(f.submitted.get(1).qty()));
    }
    @Test void submissionTimeoutAndRestartNeverDuplicateUnknownOrder() throws Exception {
        Fixture f = new Fixture(); f.price = d("101000"); f.failSubmit = true;
        assertThrows(IllegalStateException.class, f::poll); assertEquals(1, f.submitted.size());
        f.recreate(); assertTrue(f.poll()); assertEquals(1, f.submitted.size());
        assertTrue(f.exit.message().contains("未知"));
    }
    @Test void belowWaterCancelsPendingHalfAndWaitsBeforeGridResume() throws Exception {
        Fixture f = new Fixture(); f.price = d("101000"); f.poll(); OrderIntent half = f.submitted.get(0);
        f.partial(half, d("0.01")); f.poll();
        f.price = d("99000"); assertTrue(f.poll()); assertEquals("RESUMING", f.exit.phase());
        f.open.clear(); var ev = f.evidence.get(half.clientOrderId());
        f.evidence.put(half.clientOrderId(), new RebalanceProfitExit.Evidence(ev.order().withStatus(OrderStatus.CANCELED, Instant.now(), ""), ev.executedQty(), ev.fills()));
        assertFalse(f.poll()); assertEquals("GRID", f.exit.phase()); assertEquals(0, d("0.09").compareTo(f.qty));
    }
    @Test void ratioCompletionCancelsAndKeepsBtcRatherThanFlattening() throws Exception {
        Fixture f = new Fixture(); f.price = d("99000"); f.poll();
        assertTrue(f.exit.poll(f.market(), START, true)); assertTrue(f.exit.complete());
        assertEquals(0, d("0.1").compareTo(f.qty)); assertTrue(f.submitted.isEmpty());
    }
    @Test void projectedProfitableExecutionFreezesBeforeNewGridPair() throws Exception {
        Fixture f = new Fixture(); f.price = d("99990"); f.poll();
        f.exit.beforeFill(f.fill("RBAL_grid", TradeSide.SELL, d("0.006"), d("101000")), f.market());
        assertTrue(f.exit.frozen()); assertEquals("PAUSED", f.exit.phase());
    }
    @Test void smallHalfNeverRoundsUpAndManualTakeoverReleasesFrozenState() throws Exception {
        Fixture f = new Fixture(); f.qty = d("0.001"); f.value = d("100"); f.price = d("101000");
        f.poll(); assertTrue(f.submitted.isEmpty()); assertEquals("PAUSED", f.exit.phase());
        f.exit.finishExternalExit(); f.recreate(); assertFalse(f.exit.frozen());
        assertFalse(f.exit.poll(f.market(), null, false));
    }
    @Test void corruptStateFailsClosed() throws Exception {
        Fixture f = new Fixture(); Files.writeString(f.path, "{}");
        assertThrows(IllegalStateException.class, f::recreate);
    }

    @Test void manualLongReductionClosesTenPercentAndCanRepeatAfterCompletion() throws Exception {
        Fixture f = new Fixture(); f.price = d("99000"); f.poll();
        assertEquals(0, d("0.010").compareTo(f.exit.requestManualReduction(f.market())));
        assertTrue(f.exit.frozen());
        assertThrows(IllegalStateException.class, () -> f.exit.requestManualReduction(f.market()));
        f.poll(); OrderIntent first = f.submitted.get(0);
        assertEquals(TradeSide.SELL, first.side()); assertEquals(0, d("0.010").compareTo(first.qty()));
        f.finish(first); assertFalse(f.poll()); assertEquals("GRID", f.exit.phase());
        assertEquals(0, d("0.090").compareTo(f.qty)); assertFalse(f.exit.complete());
        assertEquals(0, d("0.009").compareTo(f.exit.requestManualReduction(f.market())));
        f.poll(); assertEquals(0, d("0.009").compareTo(f.submitted.get(1).qty()));
    }
    @Test void manualShortReductionBuysBackAndKeepsProfitableCyclePaused() throws Exception {
        Fixture f = new Fixture(); f.qty = d("-0.1"); f.value = d("-10000"); f.entryDirection = "SHORT";
        f.price = d("99500"); f.poll();
        f.exit.requestManualReduction(f.market()); f.poll(); OrderIntent order = f.submitted.get(0);
        assertEquals(TradeSide.BUY, order.side()); assertEquals(0, d("0.01").compareTo(order.qty()));
        f.finish(order); assertTrue(f.poll()); assertEquals("PAUSED", f.exit.phase());
        assertEquals(0, d("-0.09").compareTo(f.qty)); assertFalse(f.exit.complete());
    }
    @Test void manualCancellationRaceKeepsClickQuantityAndWaitsForCompleteEvidence() throws Exception {
        Fixture f = new Fixture(); f.price = d("99000"); f.poll();
        WorkingOrder grid = f.order("RBAL_old", TradeSide.BUY, OrderStatus.NEW, d("0.006"), d("98900"));
        f.open.add(grid); f.registry.trackRemote(grid); f.exit.requestManualReduction(f.market());
        f.recreate(); assertTrue(f.poll()); assertTrue(f.submitted.isEmpty());
        f.open.clear(); f.poll(); assertTrue(f.submitted.isEmpty());
        f.evidence.put(grid.clientOrderId(), new RebalanceProfitExit.Evidence(grid.withStatus(OrderStatus.FILLED, Instant.now(), ""), grid.qty(), List.of(f.fill(grid.clientOrderId(), grid.side(), grid.qty(), grid.price()))));
        f.poll(); assertEquals(0, d("0.106").compareTo(f.qty));
        assertEquals(0, d("0.010").compareTo(f.submitted.get(0).qty()));
        f.finish(f.submitted.get(0)); f.poll(); assertEquals(0, d("0.096").compareTo(f.qty));
    }
    @Test void manualPartialFillAndRestartDoNotDuplicateAndBelowWaterStillFinishesRequestedAmount() throws Exception {
        Fixture f = new Fixture(); f.price = d("99500"); f.poll(); f.exit.requestManualReduction(f.market());
        f.poll(); OrderIntent order = f.submitted.get(0); f.partial(order, d("0.004"));
        f.poll(); f.recreate(); f.poll(); assertEquals(1, f.submitted.size());
        assertEquals("MANUAL", f.exit.phase()); assertEquals(0, d("0.096").compareTo(f.qty));
        var ev = f.evidence.get(order.clientOrderId()); f.open.clear();
        f.evidence.put(order.clientOrderId(), new RebalanceProfitExit.Evidence(ev.order().withStatus(OrderStatus.CANCELED, Instant.now(), ""), ev.executedQty(), ev.fills()));
        f.poll(); assertEquals(2, f.submitted.size()); assertEquals(0, d("0.006").compareTo(f.submitted.get(1).qty()));
        f.finish(f.submitted.get(1)); assertFalse(f.poll()); assertEquals(0, d("0.090").compareTo(f.qty));
    }
    @Test void manualRejectsDustAndExistingAutomaticExitWithoutChangingControl() throws Exception {
        Fixture f = new Fixture(); f.qty = d("0.009"); f.value = d("900"); f.price = d("99000"); f.poll();
        assertThrows(IllegalStateException.class, () -> f.exit.requestManualReduction(f.market()));
        assertEquals("GRID", f.exit.phase());
        f.qty = d("0.1"); f.value = d("10000"); f.price = d("101000"); f.poll();
        assertThrows(IllegalStateException.class, () -> f.exit.requestManualReduction(f.market()));
        assertEquals("HALF", f.exit.phase());
    }

    static class Fixture {
        final Path path;
        Instant start = START;
        BigDecimal qty = d("0.1"), value = d("10000"), fees = BigDecimal.ZERO, price = d("100000");
        BigDecimal startPrice = d("100000");
        String entryDirection = "LONG";
        boolean failSubmit;
        final List<OrderIntent> submitted = new ArrayList<>();
        final List<String> canceled = new ArrayList<>();
        final List<WorkingOrder> open = new ArrayList<>();
        final Map<String, RebalanceProfitExit.Evidence> evidence = new HashMap<>();
        final Set<String> applied = new HashSet<>();
        final WorkingOrderRegistry registry = new WorkingOrderRegistry();
        RebalanceWaterExit exit;
        Fixture() throws Exception { path = Files.createTempDirectory("water-exit-test").resolve("control.json"); recreate(); }
        void recreate() {
            RebalanceSettings settings = new RebalanceSettings(true, ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                    List.of(new RebalanceBand(d("80000"), d("0.70"))), d("0.01"), d("0.001"), d("0.85"), d("0.005"),
                    d("5"), d("0.1"), d("5"), d("0.001"), d("0.001"), Duration.ofMinutes(15), 1, "unused", "unused");
            exit = new RebalanceWaterExit(path, settings, new InstrumentSpec("BTCUSDT", d("0.001"), d("0.001"), d("0.1"), d("0.1")),
                    new RebalanceProfitExit.Exchange() {
                        public List<WorkingOrder> openOrders() { return List.copyOf(open); }
                        public RebalanceProfitExit.Evidence evidence(String id) { return evidence.get(id); }
                        public BigDecimal bestOppositePrice(TradeSide side) { return price; }
                    }, new BybitTradeGateway() {
                        public BybitOrderSubmission submit(OrderIntent intent) {
                            submitted.add(intent); if (failSubmit) throw new IllegalStateException("timeout");
                            return new BybitOrderSubmission("remote", intent.clientOrderId());
                        }
                        public void cancel(String symbol, String id) { canceled.add(id); }
                    }, registry, p -> cycle(), this::apply);
        }
        void apply(FillEvent fill) {
            if (!applied.add(fill.executionId())) return;
            BigDecimal signed = fill.side() == TradeSide.BUY ? fill.fillQty() : fill.fillQty().negate();
            qty = qty.add(signed); value = value.add(signed.multiply(fill.fillPrice())); fees = fees.add(fill.fee());
        }
        CurrentCycleSummary cycle() {
            Map<String,Object> data = new HashMap<>(); data.put("symbol", "RBAL:BTCUSDT"); data.put("startedAt", start.toString());
            data.put("cumulativeQty", qty); data.put("cumulativeValue", value); data.put("cumulativeFee", fees);
            data.put("startPrice", startPrice); data.put("entryDirection", entryDirection);
            return JSON.convertValue(data, CurrentCycleSummary.class);
        }
        MarketSnapshot market() { return new MarketSnapshot("BTCUSDT", Instant.now(), price, price, null); }
        boolean poll() { return exit.poll(market(), start, false); }
        WorkingOrder order(String id, TradeSide side, OrderStatus status, BigDecimal q, BigDecimal p) {
            return new WorkingOrder("BTCUSDT", id, "remote", side, OrderType.LIMIT, p, q, false, status, Instant.now(), Instant.now());
        }
        FillEvent fill(String id, TradeSide side, BigDecimal q, BigDecimal p) {
            return new FillEvent("exec-" + UUID.randomUUID(), "BTCUSDT", id, side, q, p, Instant.now(), q.multiply(p), BigDecimal.ZERO, "USDT");
        }
        void finish(OrderIntent i) {
            open.removeIf(o -> o.clientOrderId().equals(i.clientOrderId()));
            evidence.put(i.clientOrderId(), new RebalanceProfitExit.Evidence(order(i.clientOrderId(), i.side(), OrderStatus.FILLED, i.qty(), i.price()), i.qty(), List.of(fill(i.clientOrderId(), i.side(), i.qty(), i.price()))));
        }
        void partial(OrderIntent i, BigDecimal q) {
            WorkingOrder row = order(i.clientOrderId(), i.side(), OrderStatus.NEW, i.qty(), i.price());
            open.add(row); evidence.put(i.clientOrderId(), new RebalanceProfitExit.Evidence(row, q, List.of(fill(i.clientOrderId(), i.side(), q, i.price()))));
        }
    }
}
