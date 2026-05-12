package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.OrderUpdateEvent;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.ActiveOrderPair;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.GridStrategyEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GridLiveRunner implements AutoCloseable, PrivateExecutionListener, PrivateOrderListener, PrivateStreamLifecycleListener {

    private final GridProperties properties;
    private final BybitSdkMarketDataGateway marketDataGateway;
    private final BybitPrivateStreamGateway privateStreamGateway;
    private final GridStrategyEngine strategyEngine;
    private final StrategyLedger strategyLedger;
    private final LiveCommandExecutor commandExecutor;
    private final BybitOrderQueryGateway orderQueryGateway;
    private final StateStore stateStore;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final RuntimeStatusPublisher runtimeStatusPublisher;
    private final Consumer<FillEvent> fillObserver;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicReference<MarketSnapshot> latestSnapshot = new AtomicReference<>();
    private final Set<String> processedExecutionIds;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean privateStreamConnected = new AtomicBoolean(false);
    private final AtomicBoolean stopAfterCycle = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final Object privateStreamConnectionMonitor = new Object();
    private volatile ScheduledFuture<?> reconnectFuture;

    public GridLiveRunner(
            GridProperties properties,
            BybitSdkMarketDataGateway marketDataGateway,
            BybitPrivateStreamGateway privateStreamGateway,
            GridStrategyEngine strategyEngine,
            StrategyLedger strategyLedger,
            LiveCommandExecutor commandExecutor,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore
    ) {
        this(
                properties,
                marketDataGateway,
                privateStreamGateway,
                strategyEngine,
                strategyLedger,
                commandExecutor,
                null,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillEvent -> {
                }
        );
    }

    public GridLiveRunner(
            GridProperties properties,
            BybitSdkMarketDataGateway marketDataGateway,
            BybitPrivateStreamGateway privateStreamGateway,
            GridStrategyEngine strategyEngine,
            StrategyLedger strategyLedger,
            LiveCommandExecutor commandExecutor,
            BybitOrderQueryGateway orderQueryGateway,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore
    ) {
        this(
                properties,
                marketDataGateway,
                privateStreamGateway,
                strategyEngine,
                strategyLedger,
                commandExecutor,
                orderQueryGateway,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillEvent -> {
                }
        );
    }

    public GridLiveRunner(
            GridProperties properties,
            BybitSdkMarketDataGateway marketDataGateway,
            BybitPrivateStreamGateway privateStreamGateway,
            GridStrategyEngine strategyEngine,
            StrategyLedger strategyLedger,
            LiveCommandExecutor commandExecutor,
            BybitOrderQueryGateway orderQueryGateway,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore,
            Consumer<FillEvent> fillObserver
    ) {
        this.properties = properties;
        this.marketDataGateway = marketDataGateway;
        this.privateStreamGateway = privateStreamGateway;
        this.strategyEngine = strategyEngine;
        this.strategyLedger = strategyLedger;
        this.commandExecutor = commandExecutor;
        this.orderQueryGateway = orderQueryGateway;
        this.stateStore = stateStore;
        this.workingOrderRegistry = workingOrderRegistry;
        this.processedExecutionIds = processedExecutionIds;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.fillObserver = fillObserver;
        this.runtimeStatusPublisher = new RuntimeStatusPublisher(
                properties.strategyConfig().symbol(),
                strategyEngine,
                strategyLedger,
                workingOrderRegistry,
                statusStore,
                latestSnapshot,
                processedExecutionIds
        );
    }

    public void start() {
        running.set(true);
        MarketSnapshot snapshot = marketDataGateway.latestSnapshot(properties.strategyConfig().symbol());
        latestSnapshot.set(snapshot);
        connectPrivateStream();
        waitForPrivateStreamConnection();
        eventLogger.info("live.start", Map.of("symbol", properties.strategyConfig().symbol(), "state", strategyEngine.runtimeState().state().name()));
        List<String> failures = commandExecutor.execute(strategyEngine.onMarket(snapshot), strategyLedger.snapshot());
        if (strategyEngine.runtimeState().state() == GridState.OPENING_FIRST_POSITION && noOpenOrders()) {
            running.set(false);
            throw new IllegalStateException(failures.isEmpty()
                    ? "Initial position order was not accepted by Bybit"
                    : failures.get(0));
        }
        persistState();
        runtimeStatusPublisher.publish();
        long pollSeconds = properties.runtimeSettings().marketPollInterval().toSeconds();
        scheduler.scheduleAtFixedRate(this::pollMarket, pollSeconds, pollSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void onExecution(FillEvent fillEvent) {
        handleExecution(fillEvent, "stream");
    }

    @Override
    public void onOrderUpdate(OrderUpdateEvent orderUpdateEvent) {
        workingOrderRegistry.applyUpdate(orderUpdateEvent);
        eventLogger.info("order.update", Map.of(
                "clientOrderId", orderUpdateEvent.clientOrderId(),
                "exchangeOrderId", orderUpdateEvent.exchangeOrderId(),
                "symbol", orderUpdateEvent.symbol(),
                "status", orderUpdateEvent.status().name(),
                "cumExecQty", orderUpdateEvent.cumExecQty()
        ));
        persistState();
        runtimeStatusPublisher.publish();
    }

    @Override
    public void close() {
        shutdown.set(true);
        running.set(false);
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
        }
        scheduler.shutdownNow();
        privateStreamGateway.close();
        eventLogger.info("live.stopped", Map.of("symbol", properties.strategyConfig().symbol()));
    }

    public boolean isRunning() {
        return running.get() && !shutdown.get();
    }

    public RuntimeStatusSnapshot snapshot() {
        return new RuntimeStatusSnapshot(
                Instant.now(),
                properties.strategyConfig().symbol(),
                strategyEngine.runtimeState(),
                strategyLedger.snapshot(),
                latestSnapshot.get(),
                processedExecutionIds.size(),
                List.copyOf(workingOrderRegistry.snapshot())
        );
    }

    public void requestStopAfterCycle() {
        stopAfterCycle.set(true);
    }

    public void stopNow() {
        cancelWorkingOrders();
        close();
    }

    public synchronized List<String> cancelOrder(String symbol, String clientOrderId) {
        List<String> failures = commandExecutor.execute(
                List.of(StrategyCommand.cancel(symbol, clientOrderId, "manual-cancel")),
                strategyLedger.snapshot()
        );
        persistState();
        runtimeStatusPublisher.publish();
        return failures;
    }

    public void flattenAndStop() {
        cancelWorkingOrders();
        BigDecimal netQty = strategyLedger.snapshot().netQty();
        if (netQty.signum() != 0) {
            TradeSide side = netQty.signum() > 0 ? TradeSide.SELL : TradeSide.BUY;
            OrderIntent flatten = new OrderIntent(
                    properties.strategyConfig().symbol(),
                    "MANUAL_FLATTEN_" + System.currentTimeMillis(),
                    side,
                    OrderType.MARKET,
                    netQty.abs(),
                    null,
                    true,
                    "manual-flatten-and-stop"
            );
            commandExecutor.execute(List.of(StrategyCommand.place(flatten)), strategyLedger.snapshot());
        }
        close();
    }

    private void cancelWorkingOrders() {
        List<StrategyCommand> cancels = new ArrayList<>();
        workingOrderRegistry.snapshot().forEach(order ->
                cancels.add(StrategyCommand.cancel(order.symbol(), order.clientOrderId(), "manual-stop"))
        );
        commandExecutor.execute(cancels, strategyLedger.snapshot());
    }

    private void pollMarket() {
        if (!running.get() || shutdown.get()) {
            return;
        }
        try {
            MarketSnapshot snapshot = marketDataGateway.latestSnapshot(properties.strategyConfig().symbol());
            latestSnapshot.set(snapshot);
            reconcileMissingExecutions();
            List<StrategyCommand> commands = repairIncompleteActivePair(snapshot);
            if (commands.isEmpty()) {
                commands = strategyEngine.onMarket(snapshot);
            }
            commandExecutor.execute(commands, strategyLedger.snapshot());
            persistState();
            runtimeStatusPublisher.publish();
        } catch (Exception e) {
            eventLogger.error("market.poll.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("MARKET_POLL_FAILED", "Market polling failed", Map.of("message", e.getMessage()));
        }
    }

    private List<StrategyCommand> repairIncompleteActivePair(MarketSnapshot snapshot) {
        ActiveOrderPair pair = strategyEngine.runtimeState().activeOrderPair();
        if (strategyEngine.runtimeState().state() != GridState.GRID_ACTIVE || pair == null) {
            return List.of();
        }
        Collection<WorkingOrder> workingOrders = workingOrderRegistry.snapshot();
        Set<String> activeClientOrderIds = workingOrders.stream()
                .filter(GridLiveRunner::isActiveOrder)
                .map(WorkingOrder::clientOrderId)
                .collect(Collectors.toSet());
        boolean buyActive = pair.buyOrder() == null || activeClientOrderIds.contains(pair.buyOrder().clientOrderId());
        boolean sellActive = pair.sellOrder() == null || activeClientOrderIds.contains(pair.sellOrder().clientOrderId());
        if (buyActive && sellActive) {
            return List.of();
        }
        Set<String> pairClientOrderIds = new HashSet<>();
        if (pair.buyOrder() != null) {
            pairClientOrderIds.add(pair.buyOrder().clientOrderId());
        }
        if (pair.sellOrder() != null) {
            pairClientOrderIds.add(pair.sellOrder().clientOrderId());
        }
        Set<String> activePairClientOrderIds = activeClientOrderIds.stream()
                .filter(pairClientOrderIds::contains)
                .collect(Collectors.toSet());
        eventLogger.warn("active-pair.incomplete", Map.of(
                "symbol", properties.strategyConfig().symbol(),
                "buyClientOrderId", pair.buyOrder() == null ? "" : pair.buyOrder().clientOrderId(),
                "sellClientOrderId", pair.sellOrder() == null ? "" : pair.sellOrder().clientOrderId(),
                "buyActive", buyActive,
                "sellActive", sellActive
        ));
        alertService.warning("ACTIVE_PAIR_INCOMPLETE", "Active order pair is incomplete; requoting", Map.of(
                "symbol", properties.strategyConfig().symbol(),
                "buyActive", buyActive,
                "sellActive", sellActive
        ));
        return strategyEngine.replanActivePair(snapshot, activePairClientOrderIds, "manual-requote");
    }

    private static boolean isActiveOrder(WorkingOrder order) {
        return order != null && order.status() == OrderStatus.NEW;
    }

    private void reconcileMissingExecutions() {
        if (orderQueryGateway == null) {
            return;
        }
        Set<String> clientOrderIds = workingOrderRegistry.snapshot().stream()
                .map(order -> order.clientOrderId())
                .filter(clientOrderId -> clientOrderId != null && clientOrderId.startsWith("GRID_"))
                .collect(Collectors.toSet());
        if (clientOrderIds.isEmpty()) {
            return;
        }
        try {
            for (FillEvent fillEvent : orderQueryGateway.fetchRecentExecutions(properties.strategyConfig().symbol(), clientOrderIds)) {
                handleExecution(fillEvent, "rest-reconcile");
            }
        } catch (RuntimeException e) {
            eventLogger.error("execution.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("EXECUTION_RECONCILE_FAILED", "Execution reconciliation failed", Map.of("message", e.getMessage()));
        }
    }

    private synchronized void handleExecution(FillEvent fillEvent, String source) {
        if (fillEvent.executionId() != null && !processedExecutionIds.add(fillEvent.executionId())) {
            return;
        }
        MarketSnapshot snapshot = latestSnapshot.updateAndGet(current -> current != null
                ? current
                : marketDataGateway.latestSnapshot(properties.strategyConfig().symbol()));
        eventLogger.info("fill.received", Map.of(
                "source", source,
                "executionId", fillEvent.executionId(),
                "clientOrderId", fillEvent.clientOrderId(),
                "symbol", fillEvent.symbol(),
                "side", fillEvent.side().name(),
                "qty", fillEvent.fillQty(),
                "price", fillEvent.fillPrice()
        ));
        workingOrderRegistry.markFilled(fillEvent.clientOrderId(), fillEvent.fillTime() == null ? Instant.now() : fillEvent.fillTime());
        commandExecutor.execute(strategyEngine.onFill(fillEvent, snapshot), strategyLedger.snapshot());
        fillObserver.accept(fillEvent);
        persistState();
        runtimeStatusPublisher.publish();
        if (stopAfterCycle.get()
                && strategyLedger.snapshot().netQty().signum() == 0
                && strategyEngine.runtimeState().state() == com.btcgrid.common.model.GridState.INIT) {
            close();
        }
    }

    private void persistState() {
        StrategyStateSnapshot base = strategyEngine.snapshot(Set.copyOf(processedExecutionIds));
        stateStore.save(new StrategyStateSnapshot(
                base.ledgerSnapshot(),
                base.runtimeState(),
                base.activeSpacing(),
                base.processedExecutionIds(),
                List.copyOf(workingOrderRegistry.snapshot())
        ));
    }

    private boolean noOpenOrders() {
        return workingOrderRegistry.snapshot().stream()
                .noneMatch(order -> order.status() == OrderStatus.NEW);
    }

    @Override
    public void onConnected() {
        privateStreamConnected.set(true);
        synchronized (privateStreamConnectionMonitor) {
            privateStreamConnectionMonitor.notifyAll();
        }
        reconnectAttempts.set(0);
        eventLogger.info("private-stream.connected", Map.of("symbol", properties.strategyConfig().symbol()));
    }

    @Override
    public void onDisconnected(int code, String reason) {
        privateStreamConnected.set(false);
        eventLogger.warn("private-stream.disconnected", Map.of("code", code, "reason", reason));
        alertService.warning("PRIVATE_STREAM_DISCONNECTED", "Private stream disconnected", Map.of("code", code, "reason", reason));
        scheduleReconnect();
    }

    @Override
    public void onStreamError(Throwable throwable) {
        privateStreamConnected.set(false);
        eventLogger.error("private-stream.error", Map.of("message", throwable.getMessage()));
        alertService.warning("PRIVATE_STREAM_ERROR", "Private stream error", Map.of("message", throwable.getMessage()));
        scheduleReconnect();
    }

    private void connectPrivateStream() {
        if (shutdown.get()) {
            return;
        }
        privateStreamGateway.connect(this, this, this);
    }

    private void waitForPrivateStreamConnection() {
        Instant deadline = Instant.now().plusSeconds(10);
        synchronized (privateStreamConnectionMonitor) {
            while (!privateStreamConnected.get() && !shutdown.get()) {
                long waitMillis = java.time.Duration.between(Instant.now(), deadline).toMillis();
                if (waitMillis <= 0) {
                    running.set(false);
                    throw new IllegalStateException("Private stream did not connect before order submission");
                }
                try {
                    privateStreamConnectionMonitor.wait(Math.min(waitMillis, 250L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    throw new IllegalStateException("Interrupted while waiting for private stream connection", e);
                }
            }
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || shutdown.get()) {
            return;
        }
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > properties.runtimeSettings().reconnectMaxAttempts()) {
            alertService.critical(
                    "PRIVATE_STREAM_RECONNECT_EXHAUSTED",
                    "Private stream reconnect attempts exhausted",
                    Map.of("attempts", attempt)
            );
            return;
        }
        long delay = (long) properties.runtimeSettings().reconnectBaseDelaySeconds() * Math.min(attempt, 6);
        eventLogger.warn("private-stream.reconnect.scheduled", Map.of("attempt", attempt, "delaySeconds", delay));
        reconnectFuture = scheduler.schedule(() -> {
            try {
                privateStreamGateway.close();
            } catch (Exception ignored) {
            }
            connectPrivateStream();
        }, delay, TimeUnit.SECONDS);
    }
}
