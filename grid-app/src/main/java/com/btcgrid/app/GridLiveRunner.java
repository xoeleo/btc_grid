package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitFundingFeeQueryGateway;
import com.btcgrid.bybit.adapter.BybitOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateFundingFeeListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.FundingFeeEvent;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.InstrumentSpec;
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
import com.btcgrid.strategy.model.GridSafetyState;
import com.btcgrid.strategy.service.GridStrategyEngine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
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

public class GridLiveRunner implements AutoCloseable, PrivateExecutionListener, PrivateFundingFeeListener, PrivateOrderListener, PrivateStreamLifecycleListener {

    private static final Duration OPEN_ORDER_VISIBILITY_GRACE = Duration.ofSeconds(10);

    private final GridProperties properties;
    private final BybitSdkMarketDataGateway marketDataGateway;
    private final BybitPrivateStreamGateway privateStreamGateway;
    private final GridStrategyEngine strategyEngine;
    private final StrategyLedger strategyLedger;
    private final LiveCommandExecutor commandExecutor;
    private final BybitOrderQueryGateway orderQueryGateway;
    private final BybitFundingFeeQueryGateway fundingFeeQueryGateway;
    private final StateStore stateStore;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final RuntimeStatusPublisher runtimeStatusPublisher;
    private final RebalanceService rebalanceService;
    private final Consumer<FillEvent> fillObserver;
    private final Consumer<FundingFeeEvent> fundingFeeObserver;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicReference<MarketSnapshot> latestSnapshot = new AtomicReference<>();
    private final Set<String> processedExecutionIds;
    private final Map<String, Integer> missingOpenOrderObservations = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean privateStreamConnected = new AtomicBoolean(false);
    private final AtomicBoolean privateStreamSubscribed = new AtomicBoolean(false);
    private final AtomicBoolean stopAfterCycle = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicReference<Instant> lastPrivateStreamMessageAt = new AtomicReference<>(Instant.EPOCH);
    private final Object privateStreamConnectionMonitor = new Object();
    private volatile ScheduledFuture<?> reconnectFuture;
    private boolean stopRequested;
    private String flattenClientOrderId;
    private Instant executionsThrough;
    private Instant queriedThrough;
    private boolean ledgerTrusted;
    private volatile boolean recoveryReady;
    private boolean reconciling;
    private String recoveryError = "启动对账尚未完成";
    private final Map<String, BigDecimal> accountedExecutionQty = new java.util.HashMap<>();

    public synchronized String recoveryStatus() {
        return recoveryReady ? (stopRequested ? "停止待确认，仅保留对账监控" : "ready") : recoveryError;
    }

    public synchronized boolean isStopPending() { return stopRequested; }


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
                null,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillEvent -> {
                },
                fundingFeeEvent -> {
                },
                null
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
            BybitFundingFeeQueryGateway fundingFeeQueryGateway,
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
                fundingFeeQueryGateway,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillEvent -> {
                },
                fundingFeeEvent -> {
                },
                null
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
                null,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                (RebalanceService) null
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
            BybitFundingFeeQueryGateway fundingFeeQueryGateway,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore,
            Consumer<FillEvent> fillObserver
    ) {
        this(
                properties,
                marketDataGateway,
                privateStreamGateway,
                strategyEngine,
                strategyLedger,
                commandExecutor,
                orderQueryGateway,
                fundingFeeQueryGateway,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillObserver,
                fundingFeeEvent -> {
                },
                null
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
            BybitFundingFeeQueryGateway fundingFeeQueryGateway,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore,
            Consumer<FillEvent> fillObserver,
            Consumer<FundingFeeEvent> fundingFeeObserver
    ) {
        this(
                properties,
                marketDataGateway,
                privateStreamGateway,
                strategyEngine,
                strategyLedger,
                commandExecutor,
                orderQueryGateway,
                fundingFeeQueryGateway,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillObserver,
                fundingFeeObserver,
                null
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
            BybitFundingFeeQueryGateway fundingFeeQueryGateway,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore,
            RebalanceService rebalanceService
    ) {
        this(
                properties,
                marketDataGateway,
                privateStreamGateway,
                strategyEngine,
                strategyLedger,
                commandExecutor,
                orderQueryGateway,
                fundingFeeQueryGateway,
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                fillEvent -> {
                },
                fundingFeeEvent -> {
                },
                rebalanceService
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
            BybitFundingFeeQueryGateway fundingFeeQueryGateway,
            StateStore stateStore,
            WorkingOrderRegistry workingOrderRegistry,
            Set<String> processedExecutionIds,
            EventLogger eventLogger,
            AlertService alertService,
            StatusStore statusStore,
            Consumer<FillEvent> fillObserver,
            Consumer<FundingFeeEvent> fundingFeeObserver,
            RebalanceService rebalanceService
    ) {
        this.properties = properties;
        this.marketDataGateway = marketDataGateway;
        this.privateStreamGateway = privateStreamGateway;
        this.strategyEngine = strategyEngine;
        this.strategyLedger = strategyLedger;
        this.commandExecutor = commandExecutor;
        this.orderQueryGateway = orderQueryGateway;
        this.fundingFeeQueryGateway = fundingFeeQueryGateway;
        this.stateStore = stateStore;
        this.workingOrderRegistry = workingOrderRegistry;
        this.processedExecutionIds = processedExecutionIds;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.fillObserver = fillObserver;
        this.fundingFeeObserver = fundingFeeObserver;
        this.rebalanceService = rebalanceService;
        this.runtimeStatusPublisher = new RuntimeStatusPublisher(
                properties.strategyConfig().symbol(),
                strategyEngine,
                strategyLedger,
                workingOrderRegistry,
                statusStore,
                latestSnapshot,
                processedExecutionIds
        );
        commandExecutor.setBeforeExchangeMutation(this::persistState);
    }

    public synchronized void start() {
        restoreSafetyState();
        running.set(true);
        MarketSnapshot snapshot = marketDataGateway.latestSnapshot(properties.strategyConfig().symbol());
        latestSnapshot.set(snapshot);
        connectPrivateStream();
        try { waitForPrivateStreamConnection(); }
        catch (RuntimeException e) {
            running.set(true);
            recoveryReady = false;
            eventLogger.warn("private-stream.monitor-only", Map.of("message", e.getMessage()));
            scheduleReconnect();
        }
        eventLogger.info("live.start", Map.of("symbol", properties.strategyConfig().symbol(), "state", strategyEngine.runtimeState().state().name()));
        reconcileFundingFees();
        recoverAndEvaluate(snapshot);
        persistState();
        runtimeStatusPublisher.publish();
        long pollSeconds = Math.max(1, properties.runtimeSettings().marketPollInterval().toSeconds());
        scheduler.scheduleAtFixedRate(this::pollMarket, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkPrivateStreamFreshness, 30, 30, TimeUnit.SECONDS);
    }

    private void restoreSafetyState() {
        StrategyStateSnapshot saved = stateStore.load().orElse(null);
        GridSafetyState safety = saved != null && saved.ledgerSnapshot().symbol().equalsIgnoreCase(properties.strategyConfig().symbol())
                ? saved.safetyState() : null;
        if (safety != null) {
            stopRequested = safety.stopRequested();
            flattenClientOrderId = safety.flattenClientOrderId();
            executionsThrough = safety.executionsThrough();
            ledgerTrusted = safety.ledgerTrusted() && safety.executionsThrough() != null;
            accountedExecutionQty.putAll(safety.accountedExecutionQty());
            if (!saved.ledgerSnapshot().equals(strategyLedger.snapshot())
                    || !saved.processedExecutionIds().equals(processedExecutionIds)) {
                ledgerTrusted = false;
            }
        } else {
            // Only a new, empty strategy has an unambiguous zero-inventory baseline.
            ledgerTrusted = strategyLedger.snapshot().netQty().signum() == 0
                    && workingOrderRegistry.snapshot().isEmpty()
                    && processedExecutionIds.isEmpty();
            executionsThrough = ledgerTrusted ? Instant.now() : null;
        }
        if (strategyWorkingOrders().stream().anyMatch(o -> "unattributed-startup-order".equals(o.reason()))) ledgerTrusted = false;
    }

    private void recoverAndEvaluate(MarketSnapshot snapshot) {
        try {
            reconciling = true;
            recoveryReady = false;
            boolean recovered = reconcileMissingExecutions();
            OpenOrderReconciliation orders = reconcileOpenOrders();
            if (!ledgerTrusted) {
                throw new IllegalStateException("缺少可信策略账本或恢复水位，需人工核对历史成交；禁止下单和自动清仓");
            }
            executionsThrough = queriedThrough;
            if (!stopRequested && !privateStreamSubscribed.get()) throw new IllegalStateException("私有流未订阅，仅保留对账监控");
            recoveryReady = true;
            reconciling = false;
            persistState();
            if (!stopRequested) {
                commandExecutor.execute(evaluateMarket(snapshot, recovered || orders.recoveredExecution(), orders.orderStateChanged()), strategyLedger.snapshot());
                pollRebalance();
            }
        } catch (RuntimeException e) {
            recoveryReady = false;
            recoveryError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            eventLogger.error("recovery.blocked", Map.of("message", recoveryError));
            alertService.warning("RECOVERY_BLOCKED", "仅保留查询监控，禁止策略下单", Map.of("message", recoveryError));
        } finally {
            reconciling = false;
        }
    }

    @Override
    public void onExecution(FillEvent fillEvent) {
        handleExecution(fillEvent, "stream");
    }

    @Override
    public void onFundingFee(FundingFeeEvent fundingFeeEvent) {
        handleFundingFee(fundingFeeEvent, "stream");
    }

    private synchronized boolean handleFundingFee(FundingFeeEvent fundingFeeEvent, String source) {
        if (!isStrategySymbol(fundingFeeEvent.symbol())) {
            return false;
        }
        if (fundingFeeEvent.executionId() != null && !processedExecutionIds.add(fundingFeeEvent.executionId())) {
            return false;
        }
        eventLogger.info("funding-fee.received", Map.of(
                "source", source,
                "executionId", fundingFeeEvent.executionId(),
                "symbol", fundingFeeEvent.symbol(),
                "time", fundingFeeEvent.time(),
                "fee", fundingFeeEvent.fee(),
                "feeCurrency", fundingFeeEvent.feeCurrency()
        ));
        fundingFeeObserver.accept(fundingFeeEvent);
        persistState();
        runtimeStatusPublisher.publish();
        return true;
    }

    @Override
    public synchronized void onOrderUpdate(OrderUpdateEvent orderUpdateEvent) {
        if (rebalanceService != null && RebalanceService.isRebalanceClientOrder(orderUpdateEvent.clientOrderId())) {
            workingOrderRegistry.applyUpdate(orderUpdateEvent);
            rebalanceService.onOrderUpdate(
                    orderUpdateEvent.symbol(),
                    orderUpdateEvent.clientOrderId(),
                    orderUpdateEvent.status(),
                    orderUpdateEvent.updatedAt()
            );
            return;
        }
        if (!isStrategySymbol(orderUpdateEvent.symbol()) || !isOwnedOrderId(orderUpdateEvent.clientOrderId())) {
            return;
        }
        workingOrderRegistry.applyUpdate(orderUpdateEvent);
        recoveryReady = false;
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
        privateStreamConnected.set(false);
        privateStreamSubscribed.set(false);
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
                strategyWorkingOrders()
        );
    }

    public void requestStopAfterCycle() {
        stopAfterCycle.set(true);
    }

    public boolean isStopAfterCycleRequested() {
        return stopAfterCycle.get();
    }

    public synchronized void stopNow() {
        stopRequested = true;
        recoveryReady = false;
        persistState();
        List<String> failures = cancelWorkingOrders();
        persistState();
        if (!failures.isEmpty() || !noOpenOrders()) {
            throw new IllegalStateException("停止待确认：保留监控，等待撤单终态与成交对账");
        }
        reconcileMissingExecutions();
        reconcileOpenOrders();
        executionsThrough = queriedThrough;
        if (flattenClientOrderId == null) stopRequested = false;
        persistState();
        close();
    }

    public synchronized List<String> cancelOrder(String symbol, String clientOrderId) {
        if (!isStrategySymbol(symbol) || !isOwnedOrderId(clientOrderId)
                || strategyWorkingOrders().stream().noneMatch(o -> o.clientOrderId().equals(clientOrderId))) {
            return List.of("拒绝撤销未归属本策略的订单");
        }
        recoveryReady = false;
        List<String> failures = commandExecutor.execute(
                List.of(StrategyCommand.cancel(symbol, clientOrderId, "manual-cancel")),
                strategyLedger.snapshot()
        );
        persistState();
        runtimeStatusPublisher.publish();
        return failures;
    }

    public synchronized void requestStopForFlatten() {
        stopRequested = true;
        recoveryReady = false;
        recoveryError = "停止待确认：保留对账监控，禁止新策略订单";
        persistState();
    }

    public void flattenAndStop() {
        synchronized (this) {
            stopRequested = true;
            recoveryReady = false;
            recoveryError = "停止待确认：保留对账监控，禁止新策略订单";
            persistState(); // Must succeed before any exchange mutation.
            List<String> failures = cancelWorkingOrders();
            persistState();
            if (!failures.isEmpty()) {
                throw new IllegalStateException("撤单结果待确认，保留监控: " + String.join("; ", failures));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            synchronized (this) {
                reconciling = true;
                try {
                    reconcileMissingExecutions();
                    reconcileOpenOrders();
                    executionsThrough = queriedThrough;
                    if (!ledgerTrusted) {
                        throw new IllegalStateException("策略账本归属或恢复水位不可信，禁止自动清仓，请人工核对");
                    }
                    boolean gridOrdersTerminal = strategyWorkingOrders().stream()
                            .filter(o -> !o.clientOrderId().equals(flattenClientOrderId))
                            .noneMatch(GridLiveRunner::isActiveOrder);
                    if (gridOrdersTerminal) {
                        if (flattenClientOrderId == null) {
                            BigDecimal netQty = strategyLedger.snapshot().netQty();
                            if (netQty.signum() == 0) {
                                persistState();
                                close();
                                return;
                            }
                            InstrumentSpec spec = marketDataGateway.loadInstrumentSpec(properties.strategyConfig().symbol());
                            BigDecimal qty = netQty.abs();
                            if (properties.strategyConfig().entryDirection().isSpotLongOnly()) {
                                if (netQty.signum() < 0) throw new IllegalStateException("现货策略净持仓为负，需人工核对");
                                qty = qty.min(commandExecutor.spotBaseWalletBalance(properties.strategyConfig().symbol()));
                            }
                            qty = spec.roundQtyDown(qty);
                            if (qty.signum() <= 0 || qty.compareTo(spec.minOrderQty()) < 0) {
                                throw new IllegalStateException("剩余策略持仓低于最小可卖数量或余额不足，未完成清仓，需人工核对");
                            }
                            MarketSnapshot market = latestSnapshot.get();
                            if (spec.minNotionalValue() != null && spec.minNotionalValue().signum() > 0
                                    && (market == null || qty.multiply(market.lastPrice()).compareTo(spec.minNotionalValue()) < 0)) {
                                throw new IllegalStateException("剩余策略持仓未满足交易所最小成交额，未完成清仓，需人工核对");
                            }
                            flattenClientOrderId = "MANUAL_FLATTEN_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                            OrderIntent flatten = new OrderIntent(properties.strategyConfig().symbol(), flattenClientOrderId,
                                    netQty.signum() > 0 ? TradeSide.SELL : TradeSide.BUY, OrderType.MARKET, qty, null,
                                    !properties.strategyConfig().entryDirection().isSpotLongOnly(), "manual-flatten-and-stop");
                            // Persist both the unique ID and unknown submission before sending. Never submit it again.
                            workingOrderRegistry.trackSubmitted(flatten, Instant.now());
                            persistState();
                            List<String> failures = commandExecutor.executeManualFlatten(flatten, strategyLedger.snapshot());
                            persistState();
                            if (!failures.isEmpty()) throw new IllegalStateException("清仓请求未确认，保留原订单标识并继续对账: " + String.join("; ", failures));
                        } else {
                            WorkingOrder flatten = strategyWorkingOrders().stream()
                                    .filter(o -> o.clientOrderId().equals(flattenClientOrderId)).findFirst()
                                    .orElseThrow(() -> new IllegalStateException("清仓订单记录缺失，禁止重复清仓"));
                            if (!isActiveOrder(flatten)) {
                                if (strategyLedger.snapshot().netQty().signum() == 0 && flatten.status() == OrderStatus.FILLED) {
                                    persistState();
                                    close();
                                    return;
                                }
                                throw new IllegalStateException("清仓订单已终态但策略仍有持仓，未完成清仓，需人工核对");
                            }
                        }
                    }
                    persistState();
                    runtimeStatusPublisher.publish();
                } finally {
                    reconciling = false;
                }
            }
            try { Thread.sleep(200L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("停止待确认：等待被中断，保留监控", e);
            }
        } while (System.nanoTime() < deadline && !shutdown.get());
        throw new IllegalStateException("停止待确认：30秒内未确认所有终态及完整成交，保留监控，禁止新策略订单");
    }

    private List<String> cancelWorkingOrders() {
        List<StrategyCommand> cancels = new ArrayList<>();
        workingOrderRegistry.snapshot().stream()
                .filter(order -> order != null && isStrategySymbol(order.symbol()))
                .filter(order -> order.status() == OrderStatus.NEW)
                .filter(order -> isOwnedOrderId(order.clientOrderId()))
                .filter(order -> !order.clientOrderId().equals(flattenClientOrderId))
                .forEach(order -> cancels.add(StrategyCommand.cancel(order.symbol(), order.clientOrderId(), "manual-stop")));
        return commandExecutor.execute(cancels, strategyLedger.snapshot());
    }

    private synchronized void pollMarket() {
        if (!running.get() || shutdown.get()) {
            return;
        }
        try {
            MarketSnapshot snapshot = marketDataGateway.latestSnapshot(properties.strategyConfig().symbol());
            latestSnapshot.set(snapshot);
            reconcileFundingFees();
            recoverAndEvaluate(snapshot);
            persistState();
            runtimeStatusPublisher.publish();
        } catch (Exception e) {
            eventLogger.error("market.poll.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("MARKET_POLL_FAILED", "Market polling failed", Map.of("message", e.getMessage()));
        }
    }

    private List<StrategyCommand> evaluateMarket(MarketSnapshot snapshot, boolean recoveredExecution) {
        return evaluateMarket(snapshot, recoveredExecution, false);
    }

    private List<StrategyCommand> evaluateMarket(MarketSnapshot snapshot, boolean recoveredExecution, boolean reconciledOpenOrders) {
        if (!running.get() || shutdown.get() || stopRequested || !recoveryReady || reconciling || workingOrderRegistry.hasPendingCancellation()) return List.of();
        List<StrategyCommand> commands = cancelStaleActiveOrders();
        if (commands.isEmpty() && reconciledOpenOrders && !recoveredExecution) {
            eventLogger.warn("active-pair.repair.deferred", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "reason", "open-orders-reconciled"
            ));
            return commands;
        }
        if (commands.isEmpty()) {
            commands = repairIncompleteActivePair(snapshot);
        }
        if (commands.isEmpty() && recoveredExecution) {
            commands = new ArrayList<>();
            commands.addAll(cancelOrphanWorkingOrdersWithoutActivePair());
            commands.addAll(strategyEngine.recoverPairFromLastFill(snapshot, "recovery-follow-fill"));
        }
        if (commands.isEmpty() && shouldRecoverWaitingTriggerPair()) {
            commands = new ArrayList<>();
            commands.addAll(cancelOrphanWorkingOrdersWithoutActivePair());
            commands.addAll(strategyEngine.recoverPairFromLastFill(snapshot, "recovery-follow-fill"));
        }
        if (commands.isEmpty()) {
            commands = cancelOrphanWorkingOrdersWithoutActivePair();
        }
        if (commands.isEmpty()) {
            commands = strategyEngine.onMarket(snapshot);
        }
        return commands;
    }

    private boolean shouldRecoverWaitingTriggerPair() {
        return strategyEngine.runtimeState().state() == GridState.WAITING_TRIGGER
                && strategyEngine.runtimeState().activeOrderPair() == null
                && strategyLedger.snapshot().netQty().signum() != 0;
    }

    private List<StrategyCommand> cancelStaleActiveOrders() {
        ActiveOrderPair pair = strategyEngine.runtimeState().activeOrderPair();
        if (strategyEngine.runtimeState().state() != GridState.GRID_ACTIVE || pair == null) {
            return List.of();
        }
        Set<String> pairClientOrderIds = new HashSet<>();
        if (pair.buyOrder() != null) {
            pairClientOrderIds.add(pair.buyOrder().clientOrderId());
        }
        if (pair.sellOrder() != null) {
            pairClientOrderIds.add(pair.sellOrder().clientOrderId());
        }
        List<StrategyCommand> cancels = workingOrderRegistry.snapshot().stream()
                .filter(GridLiveRunner::isActiveOrder)
                .filter(order -> isStrategySymbol(order.symbol()) && isOwnedOrderId(order.clientOrderId()))
                .filter(order -> !pairClientOrderIds.contains(order.clientOrderId()))
                .map(order -> StrategyCommand.cancel(order.symbol(), order.clientOrderId(), "stale-active-order"))
                .toList();
        if (!cancels.isEmpty()) {
            eventLogger.warn("active-orders.stale", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "count", cancels.size(),
                    "activePairId", pair.pairId()
            ));
            alertService.warning("STALE_ACTIVE_ORDERS", "Stale active orders found; canceling extras", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "count", cancels.size()
            ));
        }
        return cancels;
    }

    private List<StrategyCommand> cancelOrphanWorkingOrdersWithoutActivePair() {
        GridState state = strategyEngine.runtimeState().state();
        if (state == GridState.GRID_ACTIVE || state == GridState.OPENING_FIRST_POSITION
                || strategyEngine.runtimeState().activeOrderPair() != null) {
            return List.of();
        }
        List<StrategyCommand> cancels = workingOrderRegistry.snapshot().stream()
                .filter(GridLiveRunner::isActiveOrder)
                .filter(order -> isStrategySymbol(order.symbol()))
                .filter(order -> order.clientOrderId() != null && order.clientOrderId().startsWith("GRID_"))
                .map(order -> StrategyCommand.cancel(order.symbol(), order.clientOrderId(), "orphan-working-order"))
                .toList();
        if (!cancels.isEmpty()) {
            eventLogger.warn("working-orders.orphan", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "state", state.name(),
                    "count", cancels.size()
            ));
            alertService.warning("ORPHAN_WORKING_ORDERS", "Working orders found without an active pair; canceling before resume", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "state", state.name(),
                    "count", cancels.size()
            ));
        }
        return cancels;
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
        boolean buyExpected = shouldHavePairSide(TradeSide.BUY);
        boolean sellExpected = shouldHavePairSide(TradeSide.SELL);
        boolean buyActive = !buyExpected || (pair.buyOrder() != null && activeClientOrderIds.contains(pair.buyOrder().clientOrderId()));
        boolean sellActive = !sellExpected || (pair.sellOrder() != null && activeClientOrderIds.contains(pair.sellOrder().clientOrderId()));
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
                "buyExpected", buyExpected,
                "sellExpected", sellExpected,
                "buyActive", buyActive,
                "sellActive", sellActive
        ));
        alertService.warning("ACTIVE_PAIR_INCOMPLETE", "Active order pair is incomplete; requoting", Map.of(
                "symbol", properties.strategyConfig().symbol(),
                "buyExpected", buyExpected,
                "sellExpected", sellExpected,
                "buyActive", buyActive,
                "sellActive", sellActive
        ));
        return strategyEngine.replanActivePair(snapshot, activePairClientOrderIds, "manual-requote");
    }

    private boolean shouldHavePairSide(TradeSide side) {
        BigDecimal netQty = strategyLedger.snapshot().netQty();
        BigDecimal maxAbsQty = properties.strategyConfig().maxInventoryQty()
                .min(properties.strategyConfig().maxDirectionalInventoryQty());
        if (netQty.signum() > 0) {
            return side == TradeSide.SELL || netQty.compareTo(maxAbsQty) < 0;
        }
        if (netQty.signum() < 0) {
            return side == TradeSide.BUY || netQty.abs().compareTo(maxAbsQty) < 0;
        }
        return true;
    }

    private static boolean isActiveOrder(WorkingOrder order) {
        return order != null && order.status() == OrderStatus.NEW;
    }

    private boolean reconcileMissingExecutions() {
        if (orderQueryGateway == null) throw new IllegalStateException("缺少成交查询接口，禁止下单");
        Instant end = Instant.now();
        List<WorkingOrder> owned = strategyWorkingOrders().stream().filter(o -> isOwnedOrderId(o.clientOrderId())).toList();
        Set<String> ids = owned.stream().map(WorkingOrder::clientOrderId).collect(Collectors.toSet());
        Instant start = executionsThrough;
        if (start == null) {
            start = owned.stream().map(WorkingOrder::createdAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(end);
        }
        if (start.isAfter(end)) throw new IllegalStateException("恢复水位晚于当前时间，禁止下单");
        boolean recovered = false;
        for (FillEvent fill : orderQueryGateway.fetchExecutions(properties.strategyConfig().symbol(), null, start.minusSeconds(1), end)) {
            if (!isStrategySymbol(fill.symbol()) || !isOwnedOrderId(fill.clientOrderId())) continue;
            if (!ids.contains(fill.clientOrderId())) {
                ledgerTrusted = false;
                throw new IllegalStateException("发现本地未跟踪的策略前缀成交，需人工核对: " + fill.clientOrderId());
            }
            recovered = handleExecution(fill, "rest-reconcile") || recovered;
        }
        // Keep an overlap; execution IDs make replay idempotent.
        queriedThrough = end;
        return recovered;
    }

    private boolean reconcileFundingFees() {
        if (fundingFeeQueryGateway == null || properties.strategyConfig().entryDirection().isSpotLongOnly()) {
            return false;
        }
        boolean reconciled = false;
        Instant now = Instant.now();
        try {
            for (FundingFeeEvent fundingFeeEvent : fundingFeeQueryGateway.fetchFundingFees(
                    properties.strategyConfig().symbol(),
                    now.minus(Duration.ofDays(7)),
                    now
            )) {
                reconciled = handleFundingFee(fundingFeeEvent, "rest-reconcile") || reconciled;
            }
        } catch (RuntimeException e) {
            eventLogger.error("funding-fee.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("FUNDING_FEE_RECONCILE_FAILED", "Funding fee reconciliation failed", Map.of("message", e.getMessage()));
        }
        return reconciled;
    }

    private OpenOrderReconciliation reconcileOpenOrders() {
        if (orderQueryGateway == null) throw new IllegalStateException("缺少订单查询接口");
        List<WorkingOrder> remote = orderQueryGateway.fetchOpenOrders(properties.strategyConfig().symbol()).stream()
                .filter(o -> isStrategySymbol(o.symbol()) && isOwnedOrderId(o.clientOrderId())).toList();
        Set<String> known = strategyWorkingOrders().stream().map(WorkingOrder::clientOrderId).collect(Collectors.toSet());
        if (remote.stream().anyMatch(o -> !known.contains(o.clientOrderId()))) {
            throw new IllegalStateException("发现未归属的策略前缀开放订单，需人工核对");
        }
        Set<String> openIds = remote.stream().filter(GridLiveRunner::isActiveOrder).map(WorkingOrder::clientOrderId).collect(Collectors.toSet());
        for (WorkingOrder order : remote) workingOrderRegistry.trackRemote(order);
        Set<String> missing = strategyWorkingOrders().stream().filter(GridLiveRunner::isActiveOrder)
                .filter(o -> isOwnedOrderId(o.clientOrderId()) && !openIds.contains(o.clientOrderId()))
                .map(WorkingOrder::clientOrderId).collect(Collectors.toSet());
        List<WorkingOrder> history = missing.isEmpty() ? List.of() : orderQueryGateway.fetchRecentOrders(properties.strategyConfig().symbol(), missing);
        boolean changed = false;
        for (WorkingOrder order : history) {
            if (!missing.contains(order.clientOrderId()) || !isStrategySymbol(order.symbol())) continue;
            if (!isActiveOrder(order)) {
                verifyAccountedExecutions(order);
                workingOrderRegistry.trackRemote(order);
                changed = true;
                missing.remove(order.clientOrderId());
            }
        }
        for (WorkingOrder order : strategyWorkingOrders()) verifyAccountedExecutions(order);
        if (!missing.isEmpty()) throw new IllegalStateException("订单终态尚未确认，禁止下单: " + missing);
        return new OpenOrderReconciliation(changed, false);
    }

    private void verifyAccountedExecutions(WorkingOrder order) {
        BigDecimal expected = order.cumulativeExecutedQty();
        if (expected == null) expected = order.status() == OrderStatus.FILLED ? order.qty() : BigDecimal.ZERO;
        if (expected != null && accountedExecutionQty.getOrDefault(order.clientOrderId(), BigDecimal.ZERO).compareTo(expected) < 0) {
            throw new IllegalStateException("订单成交尚未完整入账: " + order.clientOrderId());
        }
    }

    private static boolean isOwnedOrderId(String id) {
        return id != null && (id.startsWith("GRID_") || id.startsWith("MANUAL_FLATTEN_"));
    }

    private record OpenOrderReconciliation(boolean orderStateChanged, boolean recoveredExecution) {
        private static OpenOrderReconciliation none() {
            return new OpenOrderReconciliation(false, false);
        }
    }

    private synchronized boolean handleExecution(FillEvent fillEvent, String source) {
        if (rebalanceService != null && RebalanceService.isRebalanceClientOrder(fillEvent.clientOrderId())) {
            if (fillEvent.executionId() != null && !processedExecutionIds.add(fillEvent.executionId())) {
                return false;
            }
            return rebalanceService.onExecution(fillEvent, source);
        }
        if (!isStrategySymbol(fillEvent.symbol()) || !isOwnedOrderId(fillEvent.clientOrderId())) return false;
        if (workingOrderRegistry.snapshot().stream().noneMatch(o -> o.clientOrderId().equals(fillEvent.clientOrderId()))) {
            ledgerTrusted = false;
            recoveryReady = false;
            recoveryError = "收到无法归属的策略前缀成交，需人工核对";
            persistState();
            alertService.warning("UNATTRIBUTED_EXECUTION", recoveryError, Map.of("clientOrderId", fillEvent.clientOrderId()));
            return false;
        }
        if (fillEvent.executionId() == null || fillEvent.executionId().isBlank()
                || fillEvent.fillQty() == null || fillEvent.fillQty().signum() <= 0
                || fillEvent.fillPrice() == null || fillEvent.fillPrice().signum() <= 0 || fillEvent.fillTime() == null) {
            recoveryReady = false;
            throw new IllegalStateException("策略成交字段不完整，禁止继续下单");
        }
        if (fillEvent.executionId() != null && !processedExecutionIds.add(fillEvent.executionId())) {
            return false;
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
        if (source != null && source.startsWith("rest-reconcile") && privateStreamConnected.get()) {
            privateStreamConnected.set(false);
            privateStreamSubscribed.set(false);
            eventLogger.warn("private-stream.missed-execution", Map.of(
                    "source", source,
                    "executionId", fillEvent.executionId(),
                    "clientOrderId", fillEvent.clientOrderId()
            ));
            scheduleReconnect();
        }
        BigDecimal cumulative = accountedExecutionQty.merge(fillEvent.clientOrderId(), fillEvent.fillQty(), BigDecimal::add);
        WorkingOrder order = workingOrderRegistry.snapshot().stream().filter(o -> o.clientOrderId().equals(fillEvent.clientOrderId())).findFirst().orElseThrow();
        if (order.qty() != null && cumulative.compareTo(order.qty()) >= 0) {
            workingOrderRegistry.markFilled(fillEvent.clientOrderId(), fillEvent.fillTime() == null ? Instant.now() : fillEvent.fillTime());
        }
        List<StrategyCommand> commands;
        try {
            commands = strategyEngine.onFill(fillEvent, snapshot, () -> fillObserver.accept(fillEvent));
            persistState();
        } catch (RuntimeException e) {
            recoveryReady = false;
            ledgerTrusted = false;
            recoveryError = "成交入账或持久化失败，需人工核对";
            try { persistState(); } catch (RuntimeException persistenceFailure) { e.addSuppressed(persistenceFailure); }
            throw e;
        }
        if (running.get() && !shutdown.get() && !stopRequested && recoveryReady && !reconciling) {
            commandExecutor.execute(commands, strategyLedger.snapshot());
        }
        persistState();
        runtimeStatusPublisher.publish();
        if (!stopRequested && !reconciling && recoveryReady && stopAfterCycle.get()
                && strategyLedger.snapshot().netQty().signum() == 0
                && strategyEngine.runtimeState().state() == com.btcgrid.common.model.GridState.INIT) {
            close();
        }
        return true;
    }

    private void persistState() {
        StrategyStateSnapshot base = strategyEngine.snapshot(Set.copyOf(processedExecutionIds));
        try {
            stateStore.save(new StrategyStateSnapshot(
                    base.ledgerSnapshot(), base.runtimeState(), base.activeSpacing(), base.processedExecutionIds(),
                    strategyWorkingOrders(), properties.strategyConfig(),
                    new GridSafetyState(stopRequested, flattenClientOrderId, executionsThrough, ledgerTrusted, accountedExecutionQty)));
        } catch (RuntimeException e) {
            recoveryReady = false;
            ledgerTrusted = false;
            recoveryError = "状态持久化失败，禁止交易，需恢复磁盘与账本一致性";
            throw e;
        }
    }

    private List<WorkingOrder> strategyWorkingOrders() {
        return workingOrderRegistry.snapshot().stream()
                .filter(order -> order != null && isStrategySymbol(order.symbol()) && isOwnedOrderId(order.clientOrderId()))
                .toList();
    }

    private boolean isStrategySymbol(String symbol) {
        return symbol != null && symbol.equalsIgnoreCase(properties.strategyConfig().symbol());
    }

    private void pollRebalance() {
        if (rebalanceService != null) {
            rebalanceService.onPoll();
        }
    }

    private boolean noOpenOrders() {
        return workingOrderRegistry.snapshot().stream()
                .filter(order -> order != null && isStrategySymbol(order.symbol()))
                .noneMatch(order -> order.status() == OrderStatus.NEW);
    }

    @Override
    public void onConnected() {
        privateStreamConnected.set(true);
        lastPrivateStreamMessageAt.set(Instant.now());
        eventLogger.info("private-stream.connected", Map.of("symbol", properties.strategyConfig().symbol()));
    }

    @Override
    public void onDisconnected(int code, String reason) {
        recoveryReady = false;
        privateStreamConnected.set(false);
        privateStreamSubscribed.set(false);
        eventLogger.warn("private-stream.disconnected", Map.of("code", code, "reason", reason));
        alertService.warning("PRIVATE_STREAM_DISCONNECTED", "Private stream disconnected", Map.of("code", code, "reason", reason));
        scheduleReconnect();
    }

    @Override
    public void onStreamError(Throwable throwable) {
        recoveryReady = false;
        privateStreamConnected.set(false);
        privateStreamSubscribed.set(false);
        String message = throwable == null || throwable.getMessage() == null ? "unknown private stream error" : throwable.getMessage();
        eventLogger.error("private-stream.error", Map.of("message", message));
        alertService.warning("PRIVATE_STREAM_ERROR", "Private stream error", Map.of("message", message));
        scheduleReconnect();
    }

    @Override
    public void onSubscribed() {
        privateStreamConnected.set(true);
        privateStreamSubscribed.set(true);
        cancelPendingReconnect();
        lastPrivateStreamMessageAt.set(Instant.now());
        synchronized (privateStreamConnectionMonitor) {
            privateStreamConnectionMonitor.notifyAll();
        }
        reconnectAttempts.set(0);
        eventLogger.info("private-stream.subscribed", Map.of("symbol", properties.strategyConfig().symbol()));
    }

    private void cancelPendingReconnect() {
        ScheduledFuture<?> pending = reconnectFuture;
        if (pending != null) {
            pending.cancel(false);
            reconnectFuture = null;
        }
    }

    @Override
    public void onMessageReceived(String op, String topic) {
        lastPrivateStreamMessageAt.set(Instant.now());
    }

    private void connectPrivateStream() {
        if (shutdown.get()) {
            return;
        }
        privateStreamGateway.connect(this, this, this, this);
    }

    private void waitForPrivateStreamConnection() {
        Instant deadline = Instant.now().plusSeconds(10);
        synchronized (privateStreamConnectionMonitor) {
            while (!privateStreamSubscribed.get() && !shutdown.get()) {
                long waitMillis = java.time.Duration.between(Instant.now(), deadline).toMillis();
                if (waitMillis <= 0) {
                    running.set(false);
                    throw new IllegalStateException("Private stream did not subscribe before order submission");
                }
                try {
                    privateStreamConnectionMonitor.wait(Math.min(waitMillis, 250L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    throw new IllegalStateException("Interrupted while waiting for private stream subscription", e);
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
        int maxFastAttempts = properties.runtimeSettings().reconnectMaxAttempts();
        boolean slowRetry = attempt > maxFastAttempts;
        if (attempt == maxFastAttempts + 1) {
            alertService.critical(
                    "PRIVATE_STREAM_RECONNECT_EXHAUSTED",
                    "Private stream fast reconnect attempts exhausted; continuing with slow retries",
                    Map.of("attempts", attempt)
            );
        }
        long delay = slowRetry
                ? 300L
                : (long) properties.runtimeSettings().reconnectBaseDelaySeconds() * Math.min(attempt, 6);
        eventLogger.warn("private-stream.reconnect.scheduled", Map.of(
                "attempt", attempt,
                "delaySeconds", delay,
                "mode", slowRetry ? "slow" : "fast"
        ));
        reconnectFuture = scheduler.schedule(() -> {
            try {
                privateStreamGateway.close();
            } catch (Exception ignored) {
            }
            connectPrivateStream();
        }, delay, TimeUnit.SECONDS);
    }

    private void checkPrivateStreamFreshness() {
        if (!running.get() || shutdown.get() || !privateStreamSubscribed.get()) {
            return;
        }
        if (strategyWorkingOrders().isEmpty()) {
            return;
        }
        Instant lastMessageAt = lastPrivateStreamMessageAt.get();
        long staleSeconds = Duration.between(lastMessageAt, Instant.now()).toSeconds();
        long thresholdSeconds = Math.max(600L, properties.runtimeSettings().marketPollInterval().toSeconds() * 20L);
        if (staleSeconds < thresholdSeconds) {
            return;
        }
        privateStreamConnected.set(false);
        privateStreamSubscribed.set(false);
        eventLogger.warn("private-stream.stale", Map.of(
                "lastMessageAt", lastMessageAt.toString(),
                "staleSeconds", staleSeconds,
                "thresholdSeconds", thresholdSeconds
        ));
        alertService.warning("PRIVATE_STREAM_STALE", "Private stream stale; reconnect scheduled", Map.of(
                "staleSeconds", staleSeconds,
                "thresholdSeconds", thresholdSeconds
        ));
        scheduleReconnect();
    }
}
