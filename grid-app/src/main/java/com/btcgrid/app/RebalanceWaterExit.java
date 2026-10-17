package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.common.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Durable per-Cycle dynamic break-even controller. Only confirmed ledger fills change inventory. */
public final class RebalanceWaterExit {
    public static final class Control {
        public long cycleStartMillis;
        public String phase = "RESET";
        public boolean halfDone;
        public BigDecimal targetAbsQty;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public BigDecimal manualReduceQty;
        public String pendingOrderId;
        public List<String> trackedOrderIds = new ArrayList<>();
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal HALF_RATE = new BigDecimal("0.01");
    private static final BigDecimal FULL_RATE = new BigDecimal("0.02");
    private final Path path;
    private final RebalanceSettings settings;
    private final InstrumentSpec spec;
    private final RebalanceProfitExit.Exchange exchange;
    private final BybitTradeGateway trade;
    private final WorkingOrderRegistry registry;
    private final Function<BigDecimal, CurrentCycleSummary> summary;
    private final Consumer<FillEvent> fills;
    private Control control;
    private String message = "动态水位网格";

    public RebalanceWaterExit(Path path, RebalanceSettings settings, InstrumentSpec spec,
            RebalanceProfitExit.Exchange exchange, BybitTradeGateway trade, WorkingOrderRegistry registry,
            Function<BigDecimal, CurrentCycleSummary> summary, Consumer<FillEvent> fills) {
        this.path = path; this.settings = settings; this.spec = spec; this.exchange = exchange;
        this.trade = trade; this.registry = registry; this.summary = summary; this.fills = fills;
        if (Files.exists(path)) {
            try {
                control = JSON.readValue(path.toFile(), Control.class);
                if (control.cycleStartMillis <= 0 || control.trackedOrderIds == null
                        || !Set.of("RESET", "GRID", "PAUSED", "HALF", "EXITING", "RESUMING", "FINISH_KEEP", "MANUAL", "COMPLETE").contains(control.phase)
                        || (("HALF".equals(control.phase) || "EXITING".equals(control.phase))
                            && (control.targetAbsQty == null || control.targetAbsQty.signum() < 0)))
                    throw new IllegalStateException("Invalid dynamic water control");
                if ("MANUAL".equals(control.phase) && (control.manualReduceQty == null || control.manualReduceQty.signum() <= 0))
                    throw new IllegalStateException("Invalid manual reduction quantity");
            } catch (Exception e) { throw new IllegalStateException("Cannot load dynamic water control; refuse to trade", e); }
        }
    }
    public boolean frozen() { return control != null && !Set.of("GRID", "COMPLETE").contains(control.phase); }
    public boolean complete() { return control != null && "COMPLETE".equals(control.phase); }
    public String message() { return message; }
    public String phase() { return control == null ? "GRID" : control.phase; }
    /** The existing manual exit has already reconciled every order and closed the Cycle. */
    public void finishExternalExit() {
        if (control != null) { control.phase = "COMPLETE"; control.manualReduceQty = null; persist(); }
    }

    /** Freeze before returning to the HTTP handler; one request cannot be queued twice. */
    public BigDecimal requestManualReduction(MarketSnapshot market) {
        CurrentCycleSummary cycle = summary.apply(price(market));
        if (cycle == null || cycle.startedAt() == null || cycle.cumulativeQty().signum() == 0)
            throw new IllegalStateException("当前 Cycle 没有可减仓的净数量");
        if (control != null && !Set.of("GRID", "PAUSED", "COMPLETE").contains(control.phase))
            throw new IllegalStateException("正在撤单或减仓，请等待当前操作完成");
        BigDecimal amount = spec.roundQtyDown(cycle.cumulativeQty().abs().multiply(new BigDecimal("0.10")));
        if (amount.compareTo(spec.minOrderQty()) < 0 || amount.multiply(price(market)).compareTo(spec.minNotionalValue()) < 0)
            throw new IllegalStateException("10% 减仓量小于交易所最小下单要求");
        bind(cycle);
        if (complete()) throw new IllegalStateException("当前 Cycle 已结束");
        control.phase = "MANUAL"; control.manualReduceQty = amount; control.targetAbsQty = null;
        registry.snapshot().stream().filter(this::ours).filter(o -> o.status() == OrderStatus.NEW)
                .forEach(o -> track(o.clientOrderId()));
        persist(); message = "手动减仓 10%：撤单后按盘口限价减仓 " + amount.toPlainString() + " BTC";
        return amount;
    }

    static BigDecimal water(CurrentCycleSummary cycle) {
        if (cycle == null || cycle.cumulativeQty() == null || cycle.cumulativeQty().signum() == 0) return null;
        return cycle.cumulativeValue().add(cycle.cumulativeFee())
                .divide(cycle.cumulativeQty(), 16, RoundingMode.HALF_UP);
    }
    static BigDecimal rate(CurrentCycleSummary cycle, BigDecimal price) {
        BigDecimal water = water(cycle);
        if (water == null) return null;
        // Nonpositive break-even means cumulative realised gains already cover the remaining long inventory.
        if (water.signum() <= 0) return cycle.cumulativeQty().signum() > 0 ? FULL_RATE : FULL_RATE.negate();
        return price.subtract(water).divide(water, 16, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(cycle.cumulativeQty().signum()));
    }
    private BigDecimal price(MarketSnapshot market) {
        BigDecimal p = market.markPrice().signum() > 0 ? market.markPrice() : market.lastPrice();
        if (p.signum() <= 0) throw new IllegalStateException("Missing water-control market price");
        return p;
    }
    private boolean profitable(CurrentCycleSummary cycle, BigDecimal price) {
        return cycle.cumulativeQty().multiply(price).subtract(cycle.cumulativeValue())
                .subtract(cycle.cumulativeFee()).signum() > 0;
    }

    /** Called before applying a grid execution, so a newly profitable fill cannot generate another grid pair. */
    public void beforeFill(FillEvent fill, MarketSnapshot market) {
        if (frozen()) return;
        CurrentCycleSummary cycle = summary.apply(price(market));
        if (cycle == null || cycle.cumulativeQty().signum() == 0) return;
        BigDecimal signed = fill.fillQty().multiply(fill.side() == TradeSide.BUY ? BigDecimal.ONE : BigDecimal.ONE.negate());
        BigDecimal projectedQty = cycle.cumulativeQty().add(signed);
        if (projectedQty.signum() != cycle.cumulativeQty().signum()) return;
        BigDecimal value = fill.fillValue() == null ? fill.fillQty().multiply(fill.fillPrice()) : fill.fillValue();
        BigDecimal projectedValue = cycle.cumulativeValue().add(fill.side() == TradeSide.BUY ? value : value.negate());
        BigDecimal fees = cycle.cumulativeFee().add(fill.fee() == null ? BigDecimal.ZERO : fill.fee());
        if (projectedQty.multiply(price(market)).subtract(projectedValue).subtract(fees).signum() > 0) {
            bind(cycle);
            control.phase = "PAUSED";
            registry.snapshot().stream().filter(this::ours).filter(o -> o.status() == OrderStatus.NEW)
                    .forEach(o -> track(o.clientOrderId()));
            persist();
        }
    }

    /** True freezes normal grid logic; false allows a confirmed return below break-even to rebuild the pair. */
    public boolean poll(MarketSnapshot market, Instant activeStart, boolean finishForRatio) {
        if (activeStart == null) {
            if (frozen()) { message = "动态水位状态待恢复核对，禁止启动新 Cycle"; return true; }
            return false;
        }
        BigDecimal price = price(market);
        CurrentCycleSummary cycle = summary.apply(price);
        if (cycle == null || cycle.startedAt() == null) {
            if (frozen()) { message = "等待当前 Cycle 完整成交账本"; return true; }
            return false;
        }
        bind(cycle);
        if (complete()) return true;
        if (finishForRatio && !"EXITING".equals(control.phase)) { control.phase = "FINISH_KEEP"; persist(); }
        if ("GRID".equals(control.phase)) {
            if (!profitable(cycle, price)) return false;
            control.phase = "PAUSED"; persist();
        }
        BigDecimal currentRate = rate(cycle, price);
        if (!Set.of("EXITING", "FINISH_KEEP", "RESET", "MANUAL").contains(control.phase)
                && currentRate != null && currentRate.signum() < 0) {
            control.phase = "RESUMING"; persist();
        }
        List<WorkingOrder> open = exchange.openOrders().stream().filter(this::ours).toList();
        registry.snapshot().stream().filter(this::ours).filter(o -> o.status() == OrderStatus.NEW).forEach(o -> track(o.clientOrderId()));
        open.forEach(o -> track(o.clientOrderId()));
        persist();
        boolean cancelPending = Set.of("RESUMING", "FINISH_KEEP").contains(control.phase);
        boolean canceling = false;
        for (WorkingOrder order : open) {
            registry.trackRemote(order);
            if (!order.clientOrderId().equals(control.pendingOrderId) || cancelPending) {
                trade.cancel(settings.symbol(), order.clientOrderId()); canceling = true;
            }
        }
        if (canceling) { message = "动态水位：撤单中，等待最终成交确认"; return true; }
        RebalanceProfitExit.Evidence pending = null;
        for (String id : List.copyOf(control.trackedOrderIds)) {
            var evidence = exchange.evidence(id);
            if (evidence == null || evidence.order() == null) { message = "动态水位：订单结果未知 " + id; return true; }
            BigDecimal observed = evidence.fills().stream().map(FillEvent::fillQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (observed.compareTo(evidence.executedQty()) != 0) { message = "动态水位：等待完整成交记录 " + id; return true; }
            evidence.fills().forEach(fills);
            registry.trackRemote(evidence.order());
            if (evidence.order().status() == OrderStatus.NEW) {
                if (!id.equals(control.pendingOrderId) || cancelPending) return true;
                pending = evidence;
            } else {
                // Executions are durable before removing terminal evidence. Do not depend on exchange
                // history retention throughout a long pause; unresolved orders remain tracked.
                control.trackedOrderIds.remove(id);
                if (id.equals(control.pendingOrderId)) control.pendingOrderId = null;
                persist();
            }
        }
        cycle = summary.apply(price);
        if (cycle == null || cycle.startedAt().toEpochMilli() != control.cycleStartMillis)
            throw new IllegalStateException("Dynamic water Cycle ledger changed during reconciliation");
        if (cycle.cumulativeQty().signum() == 0 || "FINISH_KEEP".equals(control.phase)) {
            if (pending != null) { trade.cancel(settings.symbol(), pending.order().clientOrderId()); return true; }
            control.phase = "COMPLETE"; control.manualReduceQty = null; persist();
            message = cycle.cumulativeQty().signum() == 0 ? "动态水位清仓成交完成，Cycle 结束" : "目标资产比例达到，撤单完成，Cycle 结束并保留仓位";
            return true;
        }
        currentRate = rate(cycle, price);
        if ("MANUAL".equals(control.phase)) {
            if (control.targetAbsQty == null) {
                control.targetAbsQty = cycle.cumulativeQty().abs().subtract(control.manualReduceQty).max(BigDecimal.ZERO);
                persist();
            }
            if (cycle.cumulativeQty().abs().compareTo(control.targetAbsQty) <= 0) {
                if (pending != null) { trade.cancel(settings.symbol(), pending.order().clientOrderId()); return true; }
                control.phase = "RESET"; control.manualReduceQty = null; control.targetAbsQty = null; persist();
            }
        }
        if ("RESET".equals(control.phase)) {
            control.phase = profitable(cycle, price) ? "PAUSED" : "RESUMING"; persist();
        }
        if (!Set.of("EXITING", "MANUAL").contains(control.phase) && currentRate.signum() < 0) control.phase = "RESUMING";
        if ("RESUMING".equals(control.phase)) {
            if (pending != null) { trade.cancel(settings.symbol(), pending.order().clientOrderId()); persist(); return true; }
            if (currentRate.signum() > 0) { control.phase = "PAUSED"; persist(); }
            else {
                control.phase = "GRID"; control.halfDone = false; control.pendingOrderId = null;
                control.targetAbsQty = null; control.trackedOrderIds.clear(); persist();
                message = "价格回到动态水位，恢复网格；下次盈利波段可再次减半";
                return false;
            }
        }
        BigDecimal qty = cycle.cumulativeQty().abs();
        if (currentRate.compareTo(FULL_RATE) >= 0 && !Set.of("EXITING", "MANUAL").contains(control.phase)) {
            control.phase = "EXITING"; control.targetAbsQty = BigDecimal.ZERO; persist();
        } else if ("PAUSED".equals(control.phase) && !control.halfDone && currentRate.compareTo(HALF_RATE) >= 0) {
            BigDecimal half = spec.roundQtyDown(qty.divide(new BigDecimal("2")));
            if (half.compareTo(spec.minOrderQty()) < 0) { message = "动态水位达到 1%，半仓小于最小下单量，继续暂停网格"; return true; }
            control.phase = "HALF"; control.targetAbsQty = qty.subtract(half); persist();
        }
        if ("HALF".equals(control.phase) && qty.compareTo(control.targetAbsQty) <= 0) {
            if (pending != null) { trade.cancel(settings.symbol(), pending.order().clientOrderId()); return true; }
            control.phase = "PAUSED"; control.halfDone = true; control.pendingOrderId = null; persist();
        }
        if (!Set.of("HALF", "EXITING", "MANUAL").contains(control.phase)) {
            message = "Cycle 已盈利，网格暂停；动态水位 " + water(cycle).stripTrailingZeros().toPlainString()
                    + (control.halfDone ? "；本次盈利波段已减半，等待 2% 清仓或回到亏损侧恢复" : "；等待 1% 减半、2% 清仓");
            return true;
        }
        BigDecimal remaining = spec.roundQtyDown(qty.subtract(control.targetAbsQty).max(BigDecimal.ZERO));
        TradeSide side = cycle.cumulativeQty().signum() > 0 ? TradeSide.SELL : TradeSide.BUY;
        BigDecimal quote = exchange.bestOppositePrice(side);
        BigDecimal limit = quote.divide(spec.priceTick(), 0, side == TradeSide.SELL ? RoundingMode.FLOOR : RoundingMode.CEILING).multiply(spec.priceTick());
        if (limit.signum() <= 0) throw new IllegalStateException("Invalid dynamic water exit quote");
        if (pending != null) {
            BigDecimal leaves = pending.order().qty().subtract(pending.executedQty());
            if (pending.order().side() != side || leaves.compareTo(remaining) > 0 || pending.order().price().compareTo(limit) != 0)
                trade.cancel(settings.symbol(), pending.order().clientOrderId());
            message = "动态水位 " + exitLabel() + "限价单待成交";
            return true;
        }
        if (remaining.compareTo(spec.minOrderQty()) < 0 || remaining.multiply(limit).compareTo(spec.minNotionalValue()) < 0)
            throw new IllegalStateException("Dynamic water residual below exchange minimum; refuse over-close");
        String id = "RBAL_WATER_" + UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        OrderIntent intent = new OrderIntent(settings.symbol(), id, side, OrderType.LIMIT, remaining.min(qty), limit,
                false, "dynamic-water-" + control.phase.toLowerCase());
        control.pendingOrderId = id; track(id); persist();
        registry.trackSubmitted(intent, Instant.now());
        var submitted = trade.submit(intent);
        registry.markAccepted(id, submitted.orderId(), Instant.now());
        message = "动态水位 " + exitLabel() + "限价单已提交";
        return true;
    }
    private String exitLabel() {
        return "MANUAL".equals(control.phase) ? "手动减仓 10%" : "HALF".equals(control.phase) ? "1% 减半" : "2% 清仓";
    }
    private void bind(CurrentCycleSummary cycle) {
        if (control != null && control.cycleStartMillis == cycle.startedAt().toEpochMilli()) return;
        if (frozen()) throw new IllegalStateException("Unresolved water-control orders belong to another Cycle");
        control = new Control(); control.cycleStartMillis = cycle.startedAt().toEpochMilli(); persist();
    }
    private boolean ours(WorkingOrder o) { return settings.symbol().equals(o.symbol()) && RebalanceService.isRebalanceClientOrder(o.clientOrderId()); }
    private void track(String id) { if (!control.trackedOrderIds.contains(id)) control.trackedOrderIds.add(id); }
    private void persist() {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, JSON.writeValueAsBytes(control));
            try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw new IllegalStateException("Cannot persist dynamic water state", e); }
    }
}
