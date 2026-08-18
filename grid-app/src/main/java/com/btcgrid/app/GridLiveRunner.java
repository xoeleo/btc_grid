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
    }

    public void start() {
        running.set(true);
        MarketSnapshot snapshot = marketDataGateway.latestSnapshot(properties.strategyConfig().symbol());
        latestSnapshot.set(snapshot);
        connectPrivateStream();
        waitForPrivateStreamConnection();
        eventLogger.info("live.start", Map.of("symbol", properties.strategyConfig().symbol(), "state", strategyEngine.runtimeState().state().name()));
        reconcileFundingFees();
        boolean recoveredExecution = reconcileMissingExecutions();
        List<String> failures = commandExecutor.execute(evaluateMarket(snapshot, recoveredExecution), strategyLedger.snapshot());
        pollRebalance();
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
        scheduler.scheduleAtFixedRate(this::checkPrivateStreamFreshness, 30, 30, TimeUnit.SECONDS);
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
    public void onOrderUpdate(OrderUpdateEvent orderUpdateEvent) {
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
        if (!isStrategySymbol(orderUpdateEvent.symbol())) {
            return;
        }
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
        RuntimeException failure = null;
        try {
            List<String> cancelFailures = cancelWorkingOrders();
            if (!cancelFailures.isEmpty()) {
                throw new IllegalStateException("撤单失败: " + String.join("; ", cancelFailures));
            }
            BigDecimal netQty = properties.strategyConfig().entryDirection().isSpotLongOnly()
                    ? commandExecutor.spotBaseWalletBalance(properties.strategyConfig().symbol())
                    : strategyLedger.snapshot().netQty();
            if (netQty.signum() != 0) {
                InstrumentSpec instrumentSpec = marketDataGateway.loadInstrumentSpec(properties.strategyConfig().symbol());
                BigDecimal flattenQty = instrumentSpec.roundQtyDown(netQty.abs());
                if (flattenQty.compareTo(instrumentSpec.minOrderQty()) < 0) {
                    eventLogger.info("manual-flatten.dust-skipped", Map.of(
                            "symbol", properties.strategyConfig().symbol(),
                            "walletQty", netQty.abs(),
                            "qtyStep", instrumentSpec.qtyStep(),
                            "minOrderQty", instrumentSpec.minOrderQty()
                    ));
                    return;
                }
                TradeSide side = netQty.signum() > 0 ? TradeSide.SELL : TradeSide.BUY;
                OrderIntent flatten = new OrderIntent(
                        properties.strategyConfig().symbol(),
                        "MANUAL_FLATTEN_" + System.currentTimeMillis(),
                        side,
                        OrderType.MARKET,
                        flattenQty,
                        null,
                        !properties.strategyConfig().entryDirection().isSpotLongOnly(),
                        "manual-flatten-and-stop"
                );
                List<String> flattenFailures = commandExecutor.executeManualFlatten(flatten, strategyLedger.snapshot());
                if (!flattenFailures.isEmpty()) {
                    throw new IllegalStateException("清仓下单失败: " + String.join("; ", flattenFailures));
                }
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            close();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private List<String> cancelWorkingOrders() {
        List<StrategyCommand> cancels = new ArrayList<>();
        workingOrderRegistry.snapshot().stream()
                .filter(order -> order != null && isStrategySymbol(order.symbol()))
                .filter(order -> order.status() == OrderStatus.NEW)
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
            boolean recoveredExecution = reconcileMissingExecutions();
            OpenOrderReconciliation openOrderReconciliation = reconcileOpenOrders();
            recoveredExecution = recoveredExecution || openOrderReconciliation.recoveredExecution();
            List<StrategyCommand> commands = evaluateMarket(snapshot, recoveredExecution, openOrderReconciliation.orderStateChanged());
            commandExecutor.execute(commands, strategyLedger.snapshot());
            pollRebalance();
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
                .filter(order -> isStrategySymbol(order.symbol()))
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
        if (orderQueryGateway == null) {
            return false;
        }
        Set<String> clientOrderIds = workingOrderRegistry.snapshot().stream()
                .map(order -> order.clientOrderId())
                .filter(clientOrderId -> clientOrderId != null && clientOrderId.startsWith("GRID_"))
                .collect(Collectors.toSet());
        if (clientOrderIds.isEmpty()) {
            return false;
        }
        boolean reconciled = false;
        try {
            for (FillEvent fillEvent : orderQueryGateway.fetchRecentExecutions(properties.strategyConfig().symbol(), clientOrderIds)) {
                reconciled = handleExecution(fillEvent, "rest-reconcile") || reconciled;
            }
        } catch (RuntimeException e) {
            eventLogger.error("execution.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("EXECUTION_RECONCILE_FAILED", "Execution reconciliation failed", Map.of("message", e.getMessage()));
        }
        return reconciled;
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
        if (orderQueryGateway == null) {
            return OpenOrderReconciliation.none();
        }
        try {
            List<WorkingOrder> remoteOpenOrders = orderQueryGateway.fetchOpenOrders(properties.strategyConfig().symbol()).stream()
                    .filter(order -> order != null && isStrategySymbol(order.symbol()))
                    .filter(order -> order.clientOrderId() != null && order.clientOrderId().startsWith("GRID_"))
                    .filter(GridLiveRunner::isActiveOrder)
                    .toList();
            Set<String> remoteOpenClientOrderIds = remoteOpenOrders.stream()
                    .map(WorkingOrder::clientOrderId)
                    .collect(Collectors.toSet());
            remoteOpenClientOrderIds.forEach(missingOpenOrderObservations::remove);
            remoteOpenOrders.forEach(workingOrderRegistry::trackRemote);

            Instant visibilityCutoff = Instant.now().minus(OPEN_ORDER_VISIBILITY_GRACE);
            List<WorkingOrder> locallyOpenButMissingRemote = workingOrderRegistry.snapshot().stream()
                    .filter(GridLiveRunner::isActiveOrder)
                    .filter(order -> isStrategySymbol(order.symbol()))
                    .filter(order -> order.clientOrderId() != null && order.clientOrderId().startsWith("GRID_"))
                    .filter(order -> !remoteOpenClientOrderIds.contains(order.clientOrderId()))
                    .filter(order -> order.createdAt() == null || !order.createdAt().isAfter(visibilityCutoff))
                    .toList();
            if (locallyOpenButMissingRemote.isEmpty()) {
                return OpenOrderReconciliation.none();
            }
            List<FillEvent> missingOrderExecutions = orderQueryGateway.fetchRecentExecutions(
                    properties.strategyConfig().symbol(),
                    locallyOpenButMissingRemote.stream()
                            .map(WorkingOrder::clientOrderId)
                            .collect(Collectors.toSet())
            );
            Set<String> clientOrderIdsWithExecutions = missingOrderExecutions.stream()
                    .map(FillEvent::clientOrderId)
                    .collect(Collectors.toSet());
            boolean recoveredExecution = false;
            for (FillEvent fillEvent : missingOrderExecutions) {
                recoveredExecution = handleExecution(fillEvent, "rest-reconcile-missing-open") || recoveredExecution;
            }
            if (!clientOrderIdsWithExecutions.isEmpty()) {
                clientOrderIdsWithExecutions.forEach(missingOpenOrderObservations::remove);
                eventLogger.warn("open-orders.missing.execution-found", Map.of(
                        "symbol", properties.strategyConfig().symbol(),
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
            List<WorkingOrder> terminalOrders = orderQueryGateway.fetchRecentOrders(
                            properties.strategyConfig().symbol(),
                            missingClientOrderIds
                    ).stream()
                    .filter(order -> order.status() == OrderStatus.CANCELED || order.status() == OrderStatus.EXPIRED)
                    .toList();
            List<WorkingOrder> awaitingConfirmation = missingWithoutExecutions.stream()
                    .filter(order -> terminalOrders.stream().noneMatch(terminal -> terminal.clientOrderId().equals(order.clientOrderId())))
                    .toList();
            if (!awaitingConfirmation.isEmpty()) {
                awaitingConfirmation.forEach(order -> missingOpenOrderObservations.merge(order.clientOrderId(), 1, Integer::sum));
                eventLogger.warn("open-orders.missing.pending", Map.of(
                        "symbol", properties.strategyConfig().symbol(),
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
                missingOpenOrderObservations.remove(order.clientOrderId());
            });
            eventLogger.warn("open-orders.reconciled", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "remoteOpenCount", remoteOpenOrders.size(),
                    "locallyCanceledCount", terminalOrders.size(),
                    "clientOrderIds", terminalOrders.stream()
                            .map(WorkingOrder::clientOrderId)
                            .toList()
            ));
            alertService.warning("OPEN_ORDERS_RECONCILED", "Local open orders were missing remotely and marked canceled", Map.of(
                    "symbol", properties.strategyConfig().symbol(),
                    "remoteOpenCount", remoteOpenOrders.size(),
                    "locallyCanceledCount", terminalOrders.size()
            ));
            return new OpenOrderReconciliation(true, recoveredExecution);
        } catch (RuntimeException e) {
            eventLogger.error("open-orders.reconcile.failed", Map.of("at", Instant.now(), "message", e.getMessage()));
            alertService.warning("OPEN_ORDERS_RECONCILE_FAILED", "Open order reconciliation failed", Map.of("message", e.getMessage()));
            return OpenOrderReconciliation.none();
        }
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
        if (!isStrategySymbol(fillEvent.symbol())) {
            return false;
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
        workingOrderRegistry.markFilled(fillEvent.clientOrderId(), fillEvent.fillTime() == null ? Instant.now() : fillEvent.fillTime());
        commandExecutor.execute(
                strategyEngine.onFill(fillEvent, snapshot, () -> fillObserver.accept(fillEvent)),
                strategyLedger.snapshot()
        );
        persistState();
        runtimeStatusPublisher.publish();
        if (stopAfterCycle.get()
                && strategyLedger.snapshot().netQty().signum() == 0
                && strategyEngine.runtimeState().state() == com.btcgrid.common.model.GridState.INIT) {
            close();
        }
        return true;
    }

    private void persistState() {
        StrategyStateSnapshot base = strategyEngine.snapshot(Set.copyOf(processedExecutionIds));
        stateStore.save(new StrategyStateSnapshot(
                base.ledgerSnapshot(),
                base.runtimeState(),
                base.activeSpacing(),
                base.processedExecutionIds(),
                strategyWorkingOrders(),
                properties.strategyConfig()
        ));
    }

    private List<WorkingOrder> strategyWorkingOrders() {
        return workingOrderRegistry.snapshot().stream()
                .filter(order -> order != null && isStrategySymbol(order.symbol()))
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
        privateStreamConnected.set(false);
        privateStreamSubscribed.set(false);
        eventLogger.warn("private-stream.disconnected", Map.of("code", code, "reason", reason));
        alertService.warning("PRIVATE_STREAM_DISCONNECTED", "Private stream disconnected", Map.of("code", code, "reason", reason));
        scheduleReconnect();
    }

    @Override
    public void onStreamError(Throwable throwable) {
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
