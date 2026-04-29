package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.OrderUpdateEvent;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.GridStrategyEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final AtomicBoolean stopAfterCycle = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
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
        eventLogger.info("live.start", Map.of("symbol", properties.strategyConfig().symbol(), "state", strategyEngine.runtimeState().state().name()));
        commandExecutor.execute(strategyEngine.onMarket(snapshot), strategyLedger.snapshot());
        persistState();
        runtimeStatusPublisher.publish();
        connectPrivateStream();
        long pollSeconds = properties.runtimeSettings().marketPollInterval().toSeconds();
        scheduler.scheduleAtFixedRate(this::pollMarket, pollSeconds, pollSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void onExecution(FillEvent fillEvent) {
        if (fillEvent.executionId() != null && !processedExecutionIds.add(fillEvent.executionId())) {
            return;
        }
        MarketSnapshot snapshot = latestSnapshot.updateAndGet(current -> current != null
                ? current
                : marketDataGateway.latestSnapshot(properties.strategyConfig().symbol()));
        eventLogger.info("fill.received", Map.of(
                "executionId", fillEvent.executionId(),
                "clientOrderId", fillEvent.clientOrderId(),
                "symbol", fillEvent.symbol(),
                "side", fillEvent.side().name(),
                "qty", fillEvent.fillQty(),
                "price", fillEvent.fillPrice()
        ));
        fillObserver.accept(fillEvent);
        commandExecutor.execute(strategyEngine.onFill(fillEvent, snapshot), strategyLedger.snapshot());
        persistState();
        runtimeStatusPublisher.publish();
        if (stopAfterCycle.get()
                && strategyLedger.snapshot().netQty().signum() == 0
                && strategyEngine.runtimeState().state() == com.btcgrid.common.model.GridState.INIT) {
            close();
        }
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
            commandExecutor.execute(strategyEngine.onMarket(snapshot), strategyLedger.snapshot());
            persistState();
            runtimeStatusPublisher.publish();
        } catch (Exception e) {
            eventLogger.error("market.poll.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("MARKET_POLL_FAILED", "Market polling failed", Map.of("message", e.getMessage()));
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

    @Override
    public void onConnected() {
        reconnectAttempts.set(0);
        eventLogger.info("private-stream.connected", Map.of("symbol", properties.strategyConfig().symbol()));
    }

    @Override
    public void onDisconnected(int code, String reason) {
        eventLogger.warn("private-stream.disconnected", Map.of("code", code, "reason", reason));
        alertService.warning("PRIVATE_STREAM_DISCONNECTED", "Private stream disconnected", Map.of("code", code, "reason", reason));
        scheduleReconnect();
    }

    @Override
    public void onStreamError(Throwable throwable) {
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
