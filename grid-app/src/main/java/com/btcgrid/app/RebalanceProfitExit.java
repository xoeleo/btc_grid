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

/** One-cycle operator instruction. Its presence permanently inhibits new cycles. */
public final class RebalanceProfitExit {
    public interface Exchange {
        List<WorkingOrder> openOrders();
        Evidence evidence(String clientOrderId);
        BigDecimal bestOppositePrice(TradeSide closeSide);
    }
    public record Evidence(WorkingOrder order, BigDecimal executedQty, List<FillEvent> fills) { }
    public static final class Control {
        public long cycleStartMillis;
        public String phase = "WAITING";
        public BigDecimal feeRate = new BigDecimal("0.001");
        public BigDecimal minimumNetProfit = new BigDecimal("1");
        public List<String> trackedOrderIds = new ArrayList<>();
        public String pendingOrderId;
        public BigDecimal completedPnl;
        public String mode = "PROFIT";
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path path;
    private final RebalanceSettings settings;
    private final InstrumentSpec spec;
    private final Exchange exchange;
    private final BybitTradeGateway trade;
    private final WorkingOrderRegistry registry;
    private final Function<BigDecimal, CurrentCycleSummary> summary;
    private final Consumer<FillEvent> fillConsumer;
    private final EventLogger logger;
    private Control control;
    private String message;

    public RebalanceProfitExit(Path path, RebalanceSettings settings, InstrumentSpec spec,
            Exchange exchange, BybitTradeGateway trade, WorkingOrderRegistry registry,
            Function<BigDecimal, CurrentCycleSummary> summary, Consumer<FillEvent> fillConsumer,
            EventLogger logger) {
        this.path = path; this.settings = settings; this.spec = spec; this.exchange = exchange;
        this.trade = trade; this.registry = registry; this.summary = summary;
        this.fillConsumer = fillConsumer; this.logger = logger;
        if (Files.exists(path)) {
            try {
                control = JSON.readValue(path.toFile(), Control.class);
                if (control.cycleStartMillis <= 0 || control.feeRate == null
                        || control.feeRate.signum() <= 0 || control.feeRate.compareTo(BigDecimal.ONE) >= 0
                        || control.minimumNetProfit == null || control.minimumNetProfit.signum() <= 0
                        || !Set.of("WAITING", "EXITING", "COMPLETE").contains(control.phase)
                        || control.trackedOrderIds == null
                        || !Set.of("PROFIT", "MANUAL").contains(control.mode)) {
                    throw new IllegalStateException("Invalid profit-exit instruction");
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load profit-exit instruction; refuse to trade", e);
            }
        }
    }
    public boolean armed() { return control != null; }
    public boolean frozen() { return armed() && !"WAITING".equals(control.phase); }
    public boolean complete() { return armed() && "COMPLETE".equals(control.phase); }
    public String message() { return message; }
    public boolean matches(Instant startedAt) {
        return armed() && startedAt != null && startedAt.toEpochMilli() == control.cycleStartMillis;
    }

    public void armManual(Instant startedAt) {
        if (startedAt == null) throw new IllegalStateException("No active rebalance cycle");
        if (control != null && !matches(startedAt))
            throw new IllegalStateException("Existing exit instruction belongs to another cycle");
        if (control != null && !"WAITING".equals(control.phase))
            throw new IllegalStateException("Cycle exit already in progress or complete");
        if (control == null) {
            control = new Control();
            control.cycleStartMillis = startedAt.toEpochMilli();
        }
        control.mode = "MANUAL";
        control.phase = "EXITING";
        registry.snapshot().stream().filter(this::ours).filter(o -> o.status() == OrderStatus.NEW)
                .map(WorkingOrder::clientOrderId).forEach(this::track);
        persist();
    }

    public boolean manual() { return armed() && "MANUAL".equals(control.mode); }

    public boolean releaseCompletedManual() {
        if (!manual() || !complete()) return false;
        try {
            Files.delete(path);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot release completed manual exit", e);
        }
        control = null;
        message = null;
        return true;
    }

    /** True means ordinary grid logic must not run during this poll. */
    public boolean poll(MarketSnapshot market, Instant startedAt) {
        if (!armed()) return false;
        if (complete()) { message = manual() ? "manual cycle close complete; idle; new cycles paused" : "profit exit complete; new cycles disabled"; return true; }
        CurrentCycleSummary cycle = summary.apply(market.markPrice());
        if (cycle == null || !matches(cycle.startedAt())
                || (startedAt != null && !matches(startedAt))
                || (startedAt == null && cycle.cumulativeQty().signum() != 0)) {
            message = "profit exit waiting for matching cycle; new cycles disabled";
            return true;
        }
        if (!frozen()) {
            if (!manual() && (cycle.markPricePnl() == null || cycle.markPricePnl().signum() <= 0)) {
                message = "new cycles disabled; waiting for current cycle market-price PnL > 0";
                return false;
            }
            // Persist the freeze before any cancellation. Stream callbacks use the same service lock.
            control.phase = "EXITING";
            registry.snapshot().stream().filter(this::ours).filter(o -> o.status() == OrderStatus.NEW)
                    .map(WorkingOrder::clientOrderId).forEach(this::track);
            persist();
            logger.info("rebalance.profit-exit.triggered", Map.of("cycleStartMillis", control.cycleStartMillis,
                    "marketPricePnl", cycle.markPricePnl(), "cycleQty", cycle.cumulativeQty()));
        }
        message = "profit exit: reconciling orders and fills; new cycles disabled";
        List<WorkingOrder> open = exchange.openOrders().stream().filter(this::ours).toList();
        open.forEach(o -> track(o.clientOrderId()));
        persist();
        // Cancel grid orders first, and never infer a terminal state from cancel acknowledgement.
        boolean gridOpen = false;
        for (WorkingOrder order : open) {
            registry.trackRemote(order);
            if (!order.clientOrderId().equals(control.pendingOrderId)) {
                gridOpen = true;
                trade.cancel(settings.symbol(), order.clientOrderId());
            }
        }
        if (gridOpen) return true;
        boolean pendingOpen = false;
        for (String id : List.copyOf(control.trackedOrderIds)) {
            Evidence evidence = exchange.evidence(id);
            if (evidence == null || evidence.order() == null) {
                message = "profit exit blocked: order outcome unknown " + id;
                return true; // Includes ambiguous submission: never submit a duplicate.
            }
            BigDecimal observed = evidence.fills().stream().map(FillEvent::fillQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (observed.compareTo(evidence.executedQty()) != 0) {
                message = "profit exit waiting for complete execution history " + id;
                return true;
            }
            evidence.fills().forEach(fillConsumer);
            registry.trackRemote(evidence.order());
            if (evidence.order().status() == OrderStatus.NEW) {
                if (!id.equals(control.pendingOrderId)) return true;
                pendingOpen = true;
            }
        }
        cycle = summary.apply(market.markPrice());
        if (cycle == null || !matches(cycle.startedAt())) {
            message = "profit exit blocked: cycle ledger changed";
            return true;
        }
        BigDecimal netQty = cycle.cumulativeQty();
        if (netQty.signum() == 0) {
            if (pendingOpen || !open.isEmpty()) {
                for (WorkingOrder order : open) trade.cancel(settings.symbol(), order.clientOrderId());
                return true;
            }
            control.phase = "COMPLETE";
            control.completedPnl = cycle.cumulativeValue().negate().subtract(cycle.cumulativeFee());
            persist();
            message = (manual() ? "manual cycle close complete; idle; new cycles paused" : "profit exit complete; new cycles disabled")
                    + "; actual cycle net PnL=" + control.completedPnl;
            logger.info("rebalance.profit-exit.complete", Map.of("cycleStartMillis", control.cycleStartMillis,
                    "netPnl", control.completedPnl));
            return true;
        }
        TradeSide closeSide = netQty.signum() < 0 ? TradeSide.BUY : TradeSide.SELL;
        BigDecimal limit = manual() ? exchange.bestOppositePrice(closeSide)
                : limitPrice(netQty, cycle.cumulativeValue(), cycle.cumulativeFee(),
                    control.feeRate, control.minimumNetProfit, spec.priceTick());
        if (limit == null || limit.signum() <= 0 || limit.remainder(spec.priceTick()).signum() != 0)
            throw new IllegalStateException("Invalid exit limit price");
        if (pendingOpen) {
            WorkingOrder order = exchange.evidence(control.pendingOrderId).order();
            boolean wrongSide = order.side() != (netQty.signum() < 0 ? TradeSide.BUY : TradeSide.SELL);
            boolean unsafe = manual() ? order.price().compareTo(limit) != 0
                    : (netQty.signum() < 0 ? order.price().compareTo(limit) > 0
                    : order.price().compareTo(limit) < 0);
            if (wrongSide || unsafe) trade.cancel(settings.symbol(), order.clientOrderId());
            message = "profit exit limit working; remaining cycle qty=" + netQty + "; safe price=" + limit;
            return true;
        }
        control.pendingOrderId = null;
        BigDecimal qty = spec.roundQtyDown(netQty.abs().min(settings.maxOrderQty()));
        if (qty.signum() <= 0 || qty.compareTo(spec.minOrderQty()) < 0
                || qty.multiply(limit).compareTo(spec.minNotionalValue()) < 0) {
            throw new IllegalStateException("Remaining cycle quantity below exchange minimum; no over-close allowed");
        }
        String id = "RBAL_PEXIT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        OrderIntent intent = new OrderIntent(settings.symbol(), id,
                closeSide, OrderType.LIMIT,
                qty, limit, false, manual() ? "current-cycle-manual-limit-exit" : "current-cycle-profitable-limit-exit");
        // Cycle inventory is virtual and may offset other strategies in the shared exchange position.
        // Therefore account reduceOnly is deliberately false; qty is capped by this cycle's own ledger.
        control.pendingOrderId = id;
        track(id);
        persist(); // durable id BEFORE submission, so a timeout/restart cannot duplicate it
        registry.trackSubmitted(intent, Instant.now());
        var submitted = trade.submit(intent);
        registry.markAccepted(id, submitted.orderId(), Instant.now());
        message = "profit exit limit submitted; cycle qty=" + netQty + "; qty=" + qty + "; price=" + limit;
        logger.info("rebalance.profit-exit.limit", Map.of("clientOrderId", id, "side", intent.side(),
                "qty", qty, "price", limit, "cycleQty", netQty));
        return true;
    }
    static BigDecimal limitPrice(BigDecimal qty, BigDecimal value, BigDecimal fees,
            BigDecimal feeRate, BigDecimal minimumProfit, BigDecimal tick) {
        // At full close: qty * price - cumulativeValue - cumulativeFee - abs(qty)*price*feeRate.
        BigDecimal numerator = qty.signum() < 0 ? value.negate().subtract(fees).subtract(minimumProfit)
                : value.add(fees).add(minimumProfit);
        BigDecimal denominator = qty.abs().multiply(qty.signum() < 0
                ? BigDecimal.ONE.add(feeRate) : BigDecimal.ONE.subtract(feeRate));
        RoundingMode rounding = qty.signum() < 0 ? RoundingMode.FLOOR : RoundingMode.CEILING;
        return numerator.divide(denominator.multiply(tick), 0, rounding).multiply(tick);
    }
    private boolean ours(WorkingOrder order) {
        return settings.symbol().equals(order.symbol()) && RebalanceService.isRebalanceClientOrder(order.clientOrderId());
    }
    private void track(String id) { if (!control.trackedOrderIds.contains(id)) control.trackedOrderIds.add(id); }
    private void persist() {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, JSON.writeValueAsBytes(control));
            try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw new IllegalStateException("Cannot persist profit-exit state", e); }
    }
}
