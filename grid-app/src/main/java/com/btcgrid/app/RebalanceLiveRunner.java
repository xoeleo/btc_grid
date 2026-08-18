package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitFundingFeeQueryGateway;
import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateFundingFeeListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.FundingFeeEvent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderUpdateEvent;
import com.btcgrid.common.model.WorkingOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RebalanceLiveRunner implements AutoCloseable, PrivateExecutionListener, PrivateFundingFeeListener, PrivateOrderListener, PrivateStreamLifecycleListener {

    private static final Duration OPEN_ORDER_VISIBILITY_GRACE = Duration.ofSeconds(10);
    private static final Duration FUNDING_FEE_QUERY_WINDOW = Duration.ofDays(7).minusMillis(1);
    private static final Duration FUNDING_FEE_RECONCILE_OVERLAP = Duration.ofMinutes(1);

    private final RebalanceService rebalanceService;
    private final BybitPrivateStreamGateway privateStreamGateway;
    private final BybitOrderQueryGateway orderQueryGateway;
    private final BybitFundingFeeQueryGateway fundingFeeQueryGateway;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final Duration pollInterval;
    private final Consumer<FillEvent> fillObserver;
    private final Consumer<FundingFeeEvent> fundingFeeObserver;
    private final String symbol;
    private final Set<String> processedExecutionIds = ConcurrentHashMap.newKeySet();
    private final Set<String> processedFundingFeeIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> missingOpenOrderObservations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rebalance-live-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean privateStreamConnected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicReference<Instant> lastPrivateStreamMessageAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> fundingFeeReconcileStart = new AtomicReference<>();
    private volatile ScheduledFuture<?> reconnectFuture;

    public RebalanceLiveRunner(
            RebalanceService rebalanceService,
            BybitPrivateStreamGateway privateStreamGateway,
            WorkingOrderRegistry workingOrderRegistry,
            EventLogger eventLogger,
            AlertService alertService,
            Duration pollInterval
    ) {
        this(
                rebalanceService,
                privateStreamGateway,
                null,
                null,
                workingOrderRegistry,
                eventLogger,
                alertService,
                pollInterval,
                fillEvent -> {
                },
                fundingFeeEvent -> {
                }
        );
    }

    public RebalanceLiveRunner(
            RebalanceService rebalanceService,
            BybitPrivateStreamGateway privateStreamGateway,
            BybitOrderQueryGateway orderQueryGateway,
            BybitFundingFeeQueryGateway fundingFeeQueryGateway,
            WorkingOrderRegistry workingOrderRegistry,
            EventLogger eventLogger,
            AlertService alertService,
            Duration pollInterval,
            Consumer<FillEvent> fillObserver,
            Consumer<FundingFeeEvent> fundingFeeObserver
    ) {
        this.rebalanceService = rebalanceService;
        this.privateStreamGateway = privateStreamGateway;
        this.orderQueryGateway = orderQueryGateway;
        this.fundingFeeQueryGateway = fundingFeeQueryGateway;
        this.workingOrderRegistry = workingOrderRegistry;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.pollInterval = pollInterval == null || pollInterval.isZero() ? Duration.ofSeconds(15) : pollInterval;
        this.fillObserver = fillObserver == null ? fillEvent -> {
        } : fillObserver;
        this.fundingFeeObserver = fundingFeeObserver == null ? fundingFeeEvent -> {
        } : fundingFeeObserver;
        this.symbol = rebalanceService.symbol();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        privateStreamGateway.connect(this, this, this, this);
        eventLogger.info("rebalance.live.start", Map.of("pollSeconds", pollInterval.toSeconds()));
        pollOnce();
        scheduler.scheduleAtFixedRate(this::pollOnce, pollInterval.toSeconds(), pollInterval.toSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkPrivateStreamFreshness, 30, 30, TimeUnit.SECONDS);
    }

    public boolean isRunning() {
        return running.get() && !shutdown.get();
    }

    @Override
    public void onExecution(FillEvent fillEvent) {
        handleExecution(fillEvent, "rebalance-stream");
    }

    @Override
    public void onFundingFee(FundingFeeEvent fundingFeeEvent) {
        handleFundingFee(fundingFeeEvent, "rebalance-stream");
    }

    @Override
    public void onOrderUpdate(OrderUpdateEvent orderUpdateEvent) {
        if (!RebalanceService.isRebalanceClientOrder(orderUpdateEvent.clientOrderId())) {
            return;
        }
        workingOrderRegistry.applyUpdate(orderUpdateEvent);
        rebalanceService.onOrderUpdate(
                orderUpdateEvent.symbol(),
                orderUpdateEvent.clientOrderId(),
                orderUpdateEvent.status(),
                orderUpdateEvent.updatedAt()
        );
    }

    @Override
    public void onConnected() {
        lastPrivateStreamMessageAt.set(Instant.now());
        eventLogger.info("rebalance.private-stream.connected", Map.of());
    }

    @Override
    public void onDisconnected(int code, String reason) {
        privateStreamConnected.set(false);
        eventLogger.warn("rebalance.private-stream.disconnected", Map.of(
                "code", code,
                "reason", reason == null ? "" : reason
        ));
        scheduleReconnect();
    }

    @Override
    public void onStreamError(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null ? "unknown private stream error" : throwable.getMessage();
        privateStreamConnected.set(false);
        eventLogger.error("rebalance.private-stream.error", Map.of("message", message));
        alertService.warning("REBALANCE_PRIVATE_STREAM_ERROR", "Rebalance private stream error", Map.of("message", message));
        scheduleReconnect();
    }

    @Override
    public void onMessageReceived(String op, String topic) {
        lastPrivateStreamMessageAt.set(Instant.now());
    }

    @Override
    public void onSubscribed() {
        privateStreamConnected.set(true);
        cancelPendingReconnect();
        reconnectAttempts.set(0);
        lastPrivateStreamMessageAt.set(Instant.now());
        eventLogger.info("rebalance.private-stream.subscribed", Map.of());
    }

    private void cancelPendingReconnect() {
        ScheduledFuture<?> pending = reconnectFuture;
        if (pending != null) {
            pending.cancel(false);
            reconnectFuture = null;
        }
    }

    @Override
    public void close() {
        shutdown.set(true);
        running.set(false);
        scheduler.shutdownNow();
        privateStreamGateway.close();
        eventLogger.info("rebalance.live.stopped", Map.of());
    }

    private void pollOnce() {
        if (!isRunning()) {
            return;
        }
        try {
            reconcileFundingFees();
            reconcileMissingExecutions();
            reconcileOpenOrders();
            rebalanceService.onPoll();
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.live.poll.failed", Map.of("message", e.getMessage()));
            alertService.warning("REBALANCE_LIVE_POLL_FAILED", "Rebalance live polling failed", Map.of("message", e.getMessage()));
        }
    }

    private boolean handleExecution(FillEvent fillEvent, String source) {
        if (fillEvent == null || !RebalanceService.isRebalanceClientOrder(fillEvent.clientOrderId())) {
            return false;
        }
        if (fillEvent.executionId() != null
                && !fillEvent.executionId().isBlank()
                && !processedExecutionIds.add(fillEvent.executionId())) {
            return false;
        }
        if (rebalanceService.onExecution(fillEvent, source, () -> fillObserver.accept(fillEvent))) {
            if (source != null && source.startsWith("rebalance-rest-reconcile") && privateStreamConnected.get()) {
                privateStreamConnected.set(false);
                eventLogger.warn("rebalance.private-stream.missed-execution", Map.of(
                        "source", source,
                        "executionId", fillEvent.executionId(),
                        "clientOrderId", fillEvent.clientOrderId()
                ));
                scheduleReconnect();
            }
            return true;
        }
        return false;
    }

    private void scheduleReconnect() {
        if (!running.get() || shutdown.get()) {
            return;
        }
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        boolean slowRetry = attempt > 20;
        if (attempt == 21) {
            alertService.warning(
                    "REBALANCE_PRIVATE_STREAM_RECONNECT_EXHAUSTED",
                    "Rebalance private stream fast reconnect attempts exhausted; continuing with slow retries",
                    Map.of("attempts", attempt)
            );
        }
        long delaySeconds = slowRetry ? 300L : Math.min(30L, 5L * Math.min(attempt, 6));
        eventLogger.warn("rebalance.private-stream.reconnect.scheduled", Map.of(
                "attempt", attempt,
                "delaySeconds", delaySeconds,
                "mode", slowRetry ? "slow" : "fast"
        ));
        reconnectFuture = scheduler.schedule(() -> {
            try {
                privateStreamGateway.close();
            } catch (Exception ignored) {
            }
            if (!shutdown.get()) {
                privateStreamGateway.connect(this, this, this, this);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void checkPrivateStreamFreshness() {
        if (!running.get() || shutdown.get() || !privateStreamConnected.get()) {
            return;
        }
        if (workingOrderRegistry.snapshot().stream().noneMatch(order ->
                order != null && RebalanceService.isRebalanceClientOrder(order.clientOrderId()))) {
            return;
        }
        Instant lastMessageAt = lastPrivateStreamMessageAt.get();
        long staleSeconds = Duration.between(lastMessageAt, Instant.now()).toSeconds();
        long thresholdSeconds = Math.max(600L, pollInterval.toSeconds() * 20L);
        if (staleSeconds < thresholdSeconds) {
            return;
        }
        privateStreamConnected.set(false);
        eventLogger.warn("rebalance.private-stream.stale", Map.of(
                "lastMessageAt", lastMessageAt.toString(),
                "staleSeconds", staleSeconds,
                "thresholdSeconds", thresholdSeconds
        ));
        alertService.warning("REBALANCE_PRIVATE_STREAM_STALE", "Rebalance private stream stale; reconnect scheduled", Map.of(
                "staleSeconds", staleSeconds,
                "thresholdSeconds", thresholdSeconds
        ));
        scheduleReconnect();
    }

    private boolean handleFundingFee(FundingFeeEvent fundingFeeEvent, String source) {
        if (fundingFeeEvent == null || fundingFeeEvent.symbol() == null || !fundingFeeEvent.symbol().equalsIgnoreCase(symbol)) {
            return false;
        }
        if (fundingFeeEvent.executionId() != null
                && !fundingFeeEvent.executionId().isBlank()
                && !processedFundingFeeIds.add(fundingFeeEvent.executionId())) {
            return false;
        }
        eventLogger.info("rebalance.funding-fee.received", Map.of(
                "source", source,
                "executionId", fundingFeeEvent.executionId() == null ? "" : fundingFeeEvent.executionId(),
                "symbol", fundingFeeEvent.symbol(),
                "time", fundingFeeEvent.time() == null ? "" : fundingFeeEvent.time(),
                "fee", fundingFeeEvent.fee() == null ? "" : fundingFeeEvent.fee(),
                "feeCurrency", fundingFeeEvent.feeCurrency() == null ? "" : fundingFeeEvent.feeCurrency()
        ));
        fundingFeeObserver.accept(fundingFeeEvent);
        return true;
    }

    private boolean reconcileMissingExecutions() {
        if (orderQueryGateway == null) {
            return false;
        }
        Set<String> clientOrderIds = workingOrderRegistry.snapshot().stream()
                .map(WorkingOrder::clientOrderId)
                .filter(RebalanceService::isRebalanceClientOrder)
                .collect(Collectors.toSet());
        if (clientOrderIds.isEmpty()) {
            return false;
        }
        boolean reconciled = false;
        try {
            for (FillEvent fillEvent : orderQueryGateway.fetchRecentExecutions(symbol, clientOrderIds)) {
                reconciled = handleExecution(fillEvent, "rebalance-rest-reconcile") || reconciled;
            }
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.execution.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("REBALANCE_EXECUTION_RECONCILE_FAILED", "Rebalance execution reconciliation failed", Map.of("message", e.getMessage()));
        }
        return reconciled;
    }

    private boolean reconcileFundingFees() {
        if (fundingFeeQueryGateway == null) {
            return false;
        }
        Instant cycleStart = rebalanceService.activeCycleStartedAt();
        Instant start = fundingFeeReconcileStart.get();
        if (start == null || (cycleStart != null && cycleStart.isAfter(start))) {
            start = cycleStart;
        }
        if (start == null) {
            return false;
        }
        boolean reconciled = false;
        Instant now = Instant.now();
        try {
            for (FundingFeeQueryWindow window : fundingFeeQueryWindows(start, now)) {
                for (FundingFeeEvent fundingFeeEvent : fundingFeeQueryGateway.fetchFundingFees(
                        symbol,
                        window.startInclusive(),
                        window.endInclusive()
                )) {
                    reconciled = handleFundingFee(fundingFeeEvent, "rebalance-rest-reconcile") || reconciled;
                }
            }
            fundingFeeReconcileStart.set(now.minus(FUNDING_FEE_RECONCILE_OVERLAP));
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.funding-fee.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("REBALANCE_FUNDING_FEE_RECONCILE_FAILED", "Rebalance funding fee reconciliation failed", Map.of("message", e.getMessage()));
        }
        return reconciled;
    }

    static List<FundingFeeQueryWindow> fundingFeeQueryWindows(Instant startInclusive, Instant endInclusive) {
        if (startInclusive == null || endInclusive == null || startInclusive.isAfter(endInclusive)) {
            return List.of();
        }
        List<FundingFeeQueryWindow> windows = new java.util.ArrayList<>();
        Instant windowStart = startInclusive;
        while (!windowStart.isAfter(endInclusive)) {
            Instant windowEnd = windowStart.plus(FUNDING_FEE_QUERY_WINDOW);
            if (windowEnd.isAfter(endInclusive)) {
                windowEnd = endInclusive;
            }
            windows.add(new FundingFeeQueryWindow(windowStart, windowEnd));
            if (windowEnd.equals(endInclusive)) {
                break;
            }
            windowStart = windowEnd.plusMillis(1);
        }
        return List.copyOf(windows);
    }

    record FundingFeeQueryWindow(Instant startInclusive, Instant endInclusive) {
    }

    private OpenOrderReconciliation reconcileOpenOrders() {
        if (orderQueryGateway == null) {
            return OpenOrderReconciliation.none();
        }
        try {
            List<WorkingOrder> remoteOpenOrders = orderQueryGateway.fetchOpenOrders(symbol).stream()
                    .filter(order -> order != null && order.symbol() != null && order.symbol().equalsIgnoreCase(symbol))
                    .filter(order -> RebalanceService.isRebalanceClientOrder(order.clientOrderId()))
                    .filter(RebalanceLiveRunner::isActiveOrder)
                    .toList();
            Set<String> remoteOpenClientOrderIds = remoteOpenOrders.stream()
                    .map(WorkingOrder::clientOrderId)
                    .collect(Collectors.toSet());
            remoteOpenClientOrderIds.forEach(missingOpenOrderObservations::remove);
            remoteOpenOrders.forEach(workingOrderRegistry::trackRemote);

            Instant visibilityCutoff = Instant.now().minus(OPEN_ORDER_VISIBILITY_GRACE);
            List<WorkingOrder> locallyOpenButMissingRemote = workingOrderRegistry.snapshot().stream()
                    .filter(RebalanceLiveRunner::isActiveOrder)
                    .filter(order -> order.symbol() != null && order.symbol().equalsIgnoreCase(symbol))
                    .filter(order -> RebalanceService.isRebalanceClientOrder(order.clientOrderId()))
                    .filter(order -> !remoteOpenClientOrderIds.contains(order.clientOrderId()))
                    .filter(order -> order.createdAt() == null || !order.createdAt().isAfter(visibilityCutoff))
                    .toList();
            if (locallyOpenButMissingRemote.isEmpty()) {
                return OpenOrderReconciliation.none();
            }
            List<FillEvent> missingOrderExecutions = orderQueryGateway.fetchRecentExecutions(
                    symbol,
                    locallyOpenButMissingRemote.stream()
                            .map(WorkingOrder::clientOrderId)
                            .collect(Collectors.toSet())
            );
            Set<String> clientOrderIdsWithExecutions = missingOrderExecutions.stream()
                    .map(FillEvent::clientOrderId)
                    .collect(Collectors.toSet());
            boolean recoveredExecution = false;
            for (FillEvent fillEvent : missingOrderExecutions) {
                recoveredExecution = handleExecution(fillEvent, "rebalance-rest-reconcile-missing-open") || recoveredExecution;
            }
            if (!clientOrderIdsWithExecutions.isEmpty()) {
                clientOrderIdsWithExecutions.forEach(missingOpenOrderObservations::remove);
                eventLogger.warn("rebalance.open-orders.missing.execution-found", Map.of(
                        "symbol", symbol,
                        "executionCount", missingOrderExecutions.size(),
                        "clientOrderIds", clientOrderIdsWithExecutions
                ));
            }
            List<WorkingOrder> missingWithoutExecutions = locallyOpenButMissingRemote.stream()
                    .filter(order -> !clientOrderIdsWithExecutions.contains(order.clientOrderId()))
                    .toList();
            if (missingWithoutExecutions.isEmpty()) {
                return new OpenOrderReconciliation(false, true);
            }
            Set<String> missingClientOrderIds = missingWithoutExecutions.stream()
                    .map(WorkingOrder::clientOrderId)
                    .collect(Collectors.toSet());
            List<WorkingOrder> terminalOrders = orderQueryGateway.fetchRecentOrders(symbol, missingClientOrderIds).stream()
                    .filter(order -> order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.EXPIRED)
                    .toList();
            List<WorkingOrder> awaitingConfirmation = missingWithoutExecutions.stream()
                    .filter(order -> terminalOrders.stream().noneMatch(terminal -> terminal.clientOrderId().equals(order.clientOrderId())))
                    .toList();
            if (!awaitingConfirmation.isEmpty()) {
                awaitingConfirmation.forEach(order -> missingOpenOrderObservations.merge(order.clientOrderId(), 1, Integer::sum));
                eventLogger.warn("rebalance.open-orders.missing.pending", Map.of(
                        "symbol", symbol,
                        "remoteOpenCount", remoteOpenOrders.size(),
                        "clientOrderIds", awaitingConfirmation.stream().map(WorkingOrder::clientOrderId).toList(),
                        "reason", "terminal-order-status-not-confirmed"
                ));
            }
            if (terminalOrders.isEmpty()) {
                return OpenOrderReconciliation.none();
            }
            Instant now = Instant.now();
            terminalOrders.forEach(order -> {
                workingOrderRegistry.markCanceled(order.clientOrderId(), now);
                rebalanceService.onOrderUpdate(order.symbol(), order.clientOrderId(), order.status(), order.updatedAt());
                missingOpenOrderObservations.remove(order.clientOrderId());
            });
            eventLogger.warn("rebalance.open-orders.reconciled", Map.of(
                    "symbol", symbol,
                    "remoteOpenCount", remoteOpenOrders.size(),
                    "locallyCanceledCount", terminalOrders.size(),
                    "clientOrderIds", terminalOrders.stream().map(WorkingOrder::clientOrderId).toList()
            ));
            alertService.warning("REBALANCE_OPEN_ORDERS_RECONCILED", "Rebalance local open orders were missing remotely and marked canceled", Map.of(
                    "symbol", symbol,
                    "remoteOpenCount", remoteOpenOrders.size(),
                    "locallyCanceledCount", terminalOrders.size()
            ));
            return new OpenOrderReconciliation(true, recoveredExecution);
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.open-orders.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("REBALANCE_OPEN_ORDERS_RECONCILE_FAILED", "Rebalance open order reconciliation failed", Map.of("message", e.getMessage()));
            return OpenOrderReconciliation.none();
        }
    }

    private static boolean isActiveOrder(WorkingOrder order) {
        return order != null && order.status() == OrderStatus.NEW;
    }

    private record OpenOrderReconciliation(boolean orderStateChanged, boolean recoveredExecution) {
        private static OpenOrderReconciliation none() {
            return new OpenOrderReconciliation(false, false);
        }
    }
}
