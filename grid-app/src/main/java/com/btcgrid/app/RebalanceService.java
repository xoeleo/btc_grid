package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitAccountGateway;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.ActiveOrderPair;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class RebalanceService {

    public static final String CLIENT_ORDER_PREFIX = "RBAL_";
    private static final BigDecimal ABNORMAL_ASSET_CHANGE_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal MIN_COMPARE_VALUE = new BigDecimal("0.00000001");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RebalanceSettings settings;
    private final BybitSdkMarketDataGateway marketDataGateway;
    private final BybitAccountGateway accountGateway;
    private final BybitTradeGateway tradeGateway;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final FileRebalanceStateStore stateStore;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final InstrumentSpec instrumentSpec;
    private final Consumer<String> cycleEndObserver;
    private final GridOrderPlanner orderPlanner;

    private RebalanceState state;
    private GridStrategyEngine rebalanceEngine;
    private StrategyLedger rebalanceLedger;
    private LiveCommandExecutor rebalanceCommandExecutor;
    private final Set<String> processedExecutionIds = ConcurrentHashMap.newKeySet();
    private Instant activeCycleStartedAt;

    public RebalanceService(
            RebalanceSettings settings,
            BybitSdkMarketDataGateway marketDataGateway,
            BybitAccountGateway accountGateway,
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            FileRebalanceStateStore stateStore,
            EventLogger eventLogger,
            AlertService alertService
    ) {
        this(
                settings,
                marketDataGateway,
                accountGateway,
                tradeGateway,
                workingOrderRegistry,
                stateStore,
                eventLogger,
                alertService,
                reason -> {
                },
                null
        );
    }

    public RebalanceService(
            RebalanceSettings settings,
            BybitSdkMarketDataGateway marketDataGateway,
            BybitAccountGateway accountGateway,
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            FileRebalanceStateStore stateStore,
            EventLogger eventLogger,
            AlertService alertService,
            Consumer<String> cycleEndObserver
    ) {
        this(
                settings,
                marketDataGateway,
                accountGateway,
                tradeGateway,
                workingOrderRegistry,
                stateStore,
                eventLogger,
                alertService,
                cycleEndObserver,
                null
        );
    }

    public RebalanceService(
            RebalanceSettings settings,
            BybitSdkMarketDataGateway marketDataGateway,
            BybitAccountGateway accountGateway,
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            FileRebalanceStateStore stateStore,
            EventLogger eventLogger,
            AlertService alertService,
            Consumer<String> cycleEndObserver,
            GridOrderPlanner orderPlanner
    ) {
        this.settings = settings;
        this.marketDataGateway = marketDataGateway;
        this.accountGateway = accountGateway;
        this.tradeGateway = tradeGateway;
        this.workingOrderRegistry = workingOrderRegistry;
        this.stateStore = stateStore;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.cycleEndObserver = cycleEndObserver == null ? reason -> {
        } : cycleEndObserver;
        this.orderPlanner = orderPlanner;
        this.instrumentSpec = settings.enabled() ? marketDataGateway.loadInstrumentSpec(settings.symbol()) : null;
        this.state = stateStore.load().orElseGet(() -> RebalanceState.initial(settings.enabled(), settings.symbol()));
        if (state.activeOrder() != null && isRebalanceClientOrder(state.activeOrder().clientOrderId())) {
            workingOrderRegistry.trackRemote(state.activeOrder());
        }
    }

    public synchronized void onPoll() {
        if (!settings.enabled()) {
            save(stateWith("DISABLED", "rebalance disabled"));
            return;
        }
        try {
            MarketSnapshot marketSnapshot = marketDataGateway.latestSnapshot(settings.symbol());
            AccountAssetSnapshot accountSnapshot = accountGateway.walletSnapshot(
                    settings.accountType(),
                    settings.baseCoin(),
                    settings.quoteCoin(),
                    settings.category(),
                    settings.symbol()
            );
            BigDecimal price = marketSnapshot.lastPrice();
            BigDecimal totalEquity = accountSnapshot.totalEquityUsdt();
            if (price.signum() <= 0 || totalEquity.signum() <= 0) {
                save(stateWith("WAITING", "missing price or account equity"));
                return;
            }
            BigDecimal rawRatio = accountSnapshot.baseValue(price).divide(totalEquity, 8, RoundingMode.HALF_UP);
            boolean abruptAssetChange = abruptAssetChange(accountSnapshot);
            List<BigDecimal> samples = abruptAssetChange ? List.of(rawRatio) : nextSamples(rawRatio);
            int requiredSamples = Math.max(1, settings.confirmationSamples());
            if (abruptAssetChange || samples.size() < requiredSamples) {
                save(stateForSnapshot("WAITING", marketSnapshot, accountSnapshot, targetRatio(price, false), rawRatio, samples,
                        abruptAssetChange
                                ? "asset snapshot changed abruptly; waiting for confirmation samples"
                                : "waiting for ratio confirmation samples"));
                return;
            }
            BigDecimal currentRatio = average(samples);
            if (rebalanceCycleActive()) {
                BigDecimal targetRatio = settings.mode() == RebalanceMode.AUTO_CYCLE
                        ? BigDecimal.ONE
                        : targetRatio(price, true);
                BigDecimal lockedTargetRatio = lockedCycleTargetRatio(targetRatio);
                TradeSide cycleSide = cycleEntrySide();
                boolean flat = rebalanceGridFlat(cycleSide);
                boolean reached = !flat && targetReached(currentRatio, lockedTargetRatio, cycleSide);
                if (reached || flat) {
                    String reason = reached
                            ? "target ratio reached; rebalance grid stopped"
                            : rebalanceGridFlatReason(cycleSide);
                    stopRebalanceGrid(reason);
                    save(stateForSnapshot("IDLE", marketSnapshot, accountSnapshot, lockedTargetRatio, currentRatio, samples,
                            reason));
                    return;
                }
                cancelOrphanOrdersIfNeeded();
                requoteTimedOutPairIfNeeded(marketSnapshot);
                recoverMissingPairIfNeeded(marketSnapshot);
                save(stateForSnapshot("GRID_ACTIVE", marketSnapshot, accountSnapshot, lockedTargetRatio, currentRatio, samples,
                        rebalanceGridRunning()
                                ? "rebalance grid running with locked target ratio"
                                : "rebalance cycle active; waiting for grid fills or target"));
                return;
            }
            if (settings.mode() == RebalanceMode.AUTO_CYCLE) {
                BigDecimal totalEquityBtc = totalEquity.divide(price, 8, RoundingMode.HALF_UP);
                if (currentRatio.compareTo(BigDecimal.ONE) >= 0) {
                    save(stateForSnapshot("IDLE", marketSnapshot, accountSnapshot, BigDecimal.ONE, currentRatio, samples,
                            "auto cycle idle; BTC ratio reached 100%"));
                    return;
                }
                if (totalEquityBtc.compareTo(settings.autoMinTotalBtc()) <= 0) {
                    save(stateForSnapshot("IDLE", marketSnapshot, accountSnapshot, BigDecimal.ONE, currentRatio, samples,
                            "auto cycle waiting; total equity " + totalEquityBtc.stripTrailingZeros().toPlainString()
                                    + " BTC must be greater than "
                                    + settings.autoMinTotalBtc().stripTrailingZeros().toPlainString() + " BTC"));
                    return;
                }
                BigDecimal initialQty = settings.autoBaseGridQty().multiply(settings.autoInitialSteps());
                startRebalanceGrid(marketSnapshot, accountSnapshot, TradeSide.BUY, initialQty,
                        BigDecimal.ONE, currentRatio, samples);
                return;
            }
            BigDecimal targetRatio = targetRatio(price, false);
            BigDecimal ratioDiff = targetRatio.subtract(currentRatio);
            if (ratioDiff.abs().compareTo(settings.ratioThreshold()) <= 0) {
                save(stateForSnapshot("IDLE", marketSnapshot, accountSnapshot, targetRatio, currentRatio, samples,
                        "ratio within threshold"));
                return;
            }
            TradeSide side = ratioDiff.signum() > 0 ? TradeSide.BUY : TradeSide.SELL;
            BigDecimal targetBaseQty = totalEquity.multiply(targetRatio).divide(price, 8, RoundingMode.HALF_UP);
            BigDecimal rawQty = targetBaseQty.subtract(accountSnapshot.netBaseQty()).abs();
            if (settings.category() == ProductCategory.SPOT && side == TradeSide.SELL) {
                rawQty = rawQty.min(accountSnapshot.baseWalletBalance().max(BigDecimal.ZERO));
            }
            if (rawQty.signum() <= 0) {
                save(stateForSnapshot("WAITING", marketSnapshot, accountSnapshot, targetRatio, currentRatio, samples,
                        "calculated quantity is below tradable minimum"));
                return;
            }
            startRebalanceGrid(marketSnapshot, accountSnapshot, side, rawQty, targetRatio, currentRatio, samples);
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.poll.failed", Map.of("symbol", settings.symbol(), "message", e.getMessage()));
            alertService.warning("REBALANCE_POLL_FAILED", "Rebalance polling failed", Map.of(
                    "symbol", settings.symbol(),
                    "message", e.getMessage()
            ));
            save(stateWith("ERROR", e.getMessage()));
        }
    }

    public synchronized void restoreActiveCycle(List<FillRecord> fills, List<WorkingOrder> openOrders) {
        List<FillRecord> tradingFills = fills == null ? List.of() : fills.stream()
                .filter(fill -> fill != null && ("BUY".equals(fill.side()) || "SELL".equals(fill.side())))
                .toList();
        tradingFills.stream()
                .map(FillRecord::executionId)
                .filter(executionId -> executionId != null && !executionId.isBlank())
                .forEach(processedExecutionIds::add);
        List<WorkingOrder> activeOrders = openOrders == null ? List.of() : openOrders.stream()
                .filter(order -> order != null && order.status() == OrderStatus.NEW)
                .filter(order -> isRebalanceClientOrder(order.clientOrderId()))
                .toList();
        if (tradingFills.isEmpty() && activeOrders.isEmpty()) {
            return;
        }
        BigDecimal netQty = tradingFills.isEmpty()
                ? BigDecimal.ZERO
                : zero(tradingFills.get(tradingFills.size() - 1).cumulativeQty());
        if (netQty.signum() == 0 && activeOrders.isEmpty()) {
            return;
        }
        TradeSide entrySide = tradingFills.isEmpty()
                ? (netQty.signum() < 0 ? TradeSide.SELL : TradeSide.BUY)
                : ("SELL".equals(tradingFills.get(0).side()) ? TradeSide.SELL : TradeSide.BUY);
        ensureCommandExecutor(entrySide, settings.maxInitialSteps());
        if (!tradingFills.isEmpty()) {
            rebalanceLedger.restore(snapshotFromFills(tradingFills, netQty));
            activeCycleStartedAt = tradingFills.get(0).time();
        }
        BigDecimal restoredTargetRatio = restoredCycleTargetRatio(tradingFills);
        ActiveOrderPair pair = activePairFromOrders(activeOrders);
        rebalanceEngine.restore(
                new StrategyRuntimeState(pair == null ? GridState.WAITING_TRIGGER : GridState.GRID_ACTIVE, pair, Instant.now()),
                null
        );
        save(new RebalanceState(
                settings.enabled(),
                settings.symbol(),
                "GRID_ACTIVE",
                restoredTargetRatio,
                state.currentRatio(),
                state.baseBalance(),
                state.contractPositionQty(),
                state.netBaseQty(),
                state.totalEquityUsdt(),
                state.lastPrice(),
                entrySide,
                null,
                Instant.now(),
                null,
                state.ratioSamples(),
                Instant.now(),
                pair == null ? "rebalance cycle restored; waiting to recover grid pair" : "rebalance cycle restored from persisted fills and open orders"
        ));
        eventLogger.info("rebalance.cycle.restored", Map.of(
                "symbol", settings.symbol(),
                "fills", tradingFills.size(),
                "openOrders", activeOrders.size(),
                "netQty", netQty,
                "targetRatio", restoredTargetRatio
        ));
    }

    private BigDecimal restoredCycleTargetRatio(List<FillRecord> tradingFills) {
        for (FillRecord fill : tradingFills) {
            if (fill.configJson() == null || fill.configJson().isBlank()) {
                continue;
            }
            try {
                JsonNode target = OBJECT_MAPPER.readTree(fill.configJson()).path("targetRatio");
                if (!target.isMissingNode() && !target.isNull()) {
                    BigDecimal value = target.decimalValue();
                    if (value.signum() > 0) {
                        return value;
                    }
                }
            } catch (RuntimeException | java.io.IOException ignored) {
                // Older cycle metadata may not be JSON; fall back to the persisted state.
            }
        }
        return state.targetRatio();
    }

    public synchronized boolean onExecution(FillEvent fillEvent, String source) {
        return onExecution(fillEvent, source, () -> {
        });
    }

    public synchronized boolean onExecution(
            FillEvent fillEvent,
            String source,
            Runnable afterLedgerApplied
    ) {
        if (!isRebalanceFill(fillEvent)) {
            return false;
        }
        if (fillEvent.executionId() != null
                && !fillEvent.executionId().isBlank()
                && !processedExecutionIds.add(fillEvent.executionId())) {
            eventLogger.warn("rebalance.fill.duplicate", Map.of(
                    "source", source,
                    "executionId", fillEvent.executionId(),
                    "clientOrderId", fillEvent.clientOrderId(),
                    "symbol", fillEvent.symbol()
            ));
            return false;
        }
        workingOrderRegistry.markFilled(fillEvent.clientOrderId(), fillEvent.fillTime() == null ? Instant.now() : fillEvent.fillTime());
        if (activeCycleStartedAt == null) {
            activeCycleStartedAt = fillEvent.fillTime() == null ? Instant.now() : fillEvent.fillTime();
        }
        eventLogger.info("rebalance.fill.received", Map.of(
                "source", source,
                "executionId", fillEvent.executionId(),
                "clientOrderId", fillEvent.clientOrderId(),
                "symbol", fillEvent.symbol(),
                "side", fillEvent.side().name(),
                "qty", fillEvent.fillQty(),
                "price", fillEvent.fillPrice()
        ));
        boolean cycleWasActive = rebalanceCycleActive();
        TradeSide cycleSide = cycleEntrySide();
        boolean fillObserved = false;
        if (isAccountingOnlyCorrectionOrder(fillEvent.clientOrderId()) && rebalanceLedger != null) {
            rebalanceLedger.applyFill(fillEvent);
            if (afterLedgerApplied != null) {
                afterLedgerApplied.run();
            }
            fillObserved = true;
            eventLogger.info("rebalance.fill.correction-applied", Map.of(
                    "executionId", fillEvent.executionId(),
                    "clientOrderId", fillEvent.clientOrderId(),
                    "symbol", fillEvent.symbol(),
                    "side", fillEvent.side().name(),
                    "qty", fillEvent.fillQty()
            ));
        } else if (rebalanceEngine != null && rebalanceLedger != null && rebalanceCommandExecutor != null) {
            MarketSnapshot marketSnapshot = marketDataGateway.latestSnapshot(settings.symbol());
            List<StrategyCommand> commands = rebalanceEngine.onFill(fillEvent, marketSnapshot, afterLedgerApplied);
            fillObserved = true;
            List<String> failures = rebalanceCommandExecutor.execute(commands, rebalanceLedger.snapshot());
            if (!failures.isEmpty()) {
                eventLogger.error("rebalance.grid.command.failed", Map.of(
                        "symbol", settings.symbol(),
                        "message", failures.get(0)
                ));
            }
            if (rebalanceGridFlat(cycleSide)) {
                String reason = rebalanceGridFlatReason(cycleSide);
                stopRebalanceGrid(reason);
                save(stateWith("IDLE", reason));
                return true;
            }
        }
        if (!fillObserved && afterLedgerApplied != null) {
            afterLedgerApplied.run();
        }
        boolean cycleStillActive = cycleWasActive || rebalanceCycleActive();
        save(new RebalanceState(
                settings.enabled(),
                settings.symbol(),
                cycleStillActive ? "GRID_ACTIVE" : "FILLED",
                state.targetRatio(),
                state.currentRatio(),
                state.baseBalance(),
                state.contractPositionQty(),
                state.netBaseQty(),
                state.totalEquityUsdt(),
                fillEvent.fillPrice(),
                cycleStillActive ? cycleSide : null,
                null,
                null,
                null,
                state.ratioSamples(),
                Instant.now(),
                cycleStillActive ? "rebalance grid fill processed" : "rebalance order filled"
        ));
        return true;
    }

    public synchronized boolean onOrderUpdate(String symbol, String clientOrderId, OrderStatus status, Instant updatedAt) {
        if (!isRebalanceClientOrder(clientOrderId) || !settings.symbol().equalsIgnoreCase(symbol)) {
            return false;
        }
        if ((status == OrderStatus.CANCELED || status == OrderStatus.EXPIRED || status == OrderStatus.FILLED)
                && !rebalanceCycleActive()) {
            save(new RebalanceState(
                    settings.enabled(),
                    settings.symbol(),
                    status.name(),
                    state.targetRatio(),
                    state.currentRatio(),
                    state.baseBalance(),
                    state.contractPositionQty(),
                    state.netBaseQty(),
                    state.totalEquityUsdt(),
                    state.lastPrice(),
                    null,
                    null,
                    null,
                    null,
                    state.ratioSamples(),
                    updatedAt == null ? Instant.now() : updatedAt,
                    "rebalance order " + status.name().toLowerCase()
            ));
        }
        return true;
    }

    public static boolean isRebalanceClientOrder(String clientOrderId) {
        return clientOrderId != null && clientOrderId.startsWith(CLIENT_ORDER_PREFIX);
    }

    static boolean isAccountingOnlyCorrectionOrder(String clientOrderId) {
        return clientOrderId != null && clientOrderId.contains("_PS_fix");
    }

    public String symbol() {
        return settings.symbol();
    }

    private boolean isRebalanceFill(FillEvent fillEvent) {
        return fillEvent != null
                && settings.symbol().equalsIgnoreCase(fillEvent.symbol())
                && isRebalanceClientOrder(fillEvent.clientOrderId());
    }

    private void cancelActiveOrder(String reason) {
        List<StrategyCommand> cancels = activeRebalanceOrders().stream()
                .map(order -> StrategyCommand.cancel(order.symbol(), order.clientOrderId(), reason))
                .toList();
        if (cancels.isEmpty()) {
            return;
        }
        try {
            ensureCommandExecutor(state.activeSide() == TradeSide.BUY ? TradeSide.BUY : TradeSide.SELL, settings.maxInitialSteps());
            rebalanceCommandExecutor.execute(cancels, rebalanceLedger == null
                    ? new InMemoryStrategyLedger(settings.symbol()).snapshot()
                    : rebalanceLedger.snapshot());
            eventLogger.info("rebalance.cancel", Map.of(
                    "symbol", settings.symbol(),
                    "count", cancels.size(),
                    "reason", reason
            ));
            save(new RebalanceState(
                    settings.enabled(),
                    settings.symbol(),
                    "CANCELED",
                    state.targetRatio(),
                    state.currentRatio(),
                    state.baseBalance(),
                    state.contractPositionQty(),
                    state.netBaseQty(),
                    state.totalEquityUsdt(),
                    state.lastPrice(),
                    null,
                    null,
                    null,
                    null,
                    state.ratioSamples(),
                    Instant.now(),
                    reason
            ));
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.cancel.failed", Map.of(
                    "symbol", settings.symbol(),
                    "message", e.getMessage()
            ));
            save(stateWith("ERROR", e.getMessage()));
        }
    }

    private void startRebalanceGrid(
            MarketSnapshot marketSnapshot,
            AccountAssetSnapshot accountSnapshot,
            TradeSide side,
            BigDecimal rawQty,
            BigDecimal targetRatio,
            BigDecimal currentRatio,
            List<BigDecimal> samples
    ) {
        BigDecimal effectiveSteps = effectiveInitialSteps(rawQty);
        if (effectiveSteps.signum() <= 0) {
            save(stateForSnapshot("WAITING", marketSnapshot, accountSnapshot, targetRatio, currentRatio, samples,
                    "calculated quantity is below initial grid size"));
            return;
        }
        ensureCommandExecutor(side, effectiveSteps);
        Instant now = Instant.now();
        activeCycleStartedAt = now;
        try {
            List<StrategyCommand> commands = rebalanceEngine.onMarket(marketSnapshot);
            List<String> failures = rebalanceCommandExecutor.execute(commands, rebalanceLedger.snapshot());
            if (!failures.isEmpty()) {
                throw new IllegalStateException(failures.get(0));
            }
            WorkingOrder activeOrder = activeRebalanceOrders().stream().findFirst().orElse(null);
            save(new RebalanceState(
                    settings.enabled(),
                    settings.symbol(),
                    "GRID_OPENING",
                    targetRatio,
                    currentRatio,
                    accountSnapshot.baseWalletBalance(),
                    accountSnapshot.contractPositionQty(),
                    accountSnapshot.netBaseQty(),
                    accountSnapshot.totalEquityUsdt(),
                    marketSnapshot.lastPrice(),
                    side,
                    activeOrder == null ? null : activeOrder.clientOrderId(),
                    now,
                    activeOrder,
                    samples,
                    now,
                    "rebalance grid initial order submitted"
            ));
            eventLogger.info("rebalance.grid.start", Map.of(
                    "symbol", settings.symbol(),
                    "mode", settings.mode().configValue(),
                    "side", side.name(),
                    "effectiveInitialSteps", effectiveSteps,
                    "baseGridQty", cycleBaseGridQty(),
                    "targetRatio", targetRatio,
                    "currentRatio", currentRatio
            ));
        } catch (RuntimeException e) {
            eventLogger.error("rebalance.place.failed", Map.of(
                    "symbol", settings.symbol(),
                    "message", e.getMessage()
            ));
            alertService.warning("REBALANCE_ORDER_PLACE_FAILED", "Rebalance order placement failed", Map.of(
                    "symbol", settings.symbol(),
                    "message", e.getMessage()
            ));
            save(stateWith("ERROR", e.getMessage()));
        }
    }

    private void ensureCommandExecutor(TradeSide side, BigDecimal effectiveInitialSteps) {
        GridStrategyConfig config = gridConfig(side, effectiveInitialSteps);
        if (rebalanceLedger == null || rebalanceEngine == null || rebalanceCommandExecutor == null
                || rebalanceEngine.config().entryDirection() != config.entryDirection()) {
            rebalanceLedger = new InMemoryStrategyLedger(settings.symbol());
            rebalanceEngine = new GridStrategyEngine(
                    config,
                    instrumentSpec,
                    rebalanceLedger,
                    new GridTriggerEvaluator(),
                    orderPlanner == null
                            ? new GridOrderPlanner(new ClientOrderIdFactory("RBAL"))
                            : orderPlanner,
                    true
            );
            rebalanceCommandExecutor = new LiveCommandExecutor(
                    tradeGateway,
                    workingOrderRegistry,
                    new RiskGuardService(config),
                    eventLogger,
                    alertService
            );
        }
    }

    private GridStrategyConfig gridConfig(TradeSide side, BigDecimal effectiveInitialSteps) {
        EntryDirection entryDirection = side == TradeSide.BUY ? EntryDirection.LONG_ONLY : EntryDirection.SHORT_ONLY;
        BigDecimal baseGridQty = cycleBaseGridQty();
        BigDecimal maxOrderQty = settings.mode() == RebalanceMode.AUTO_CYCLE
                ? settings.maxOrderQty().max(baseGridQty.multiply(effectiveInitialSteps))
                : settings.maxOrderQty();
        return new GridStrategyConfig(
                settings.category(),
                settings.symbol(),
                entryDirection,
                settings.baseGridSpacing(),
                settings.gridRequoteShrinkFactor(),
                baseGridQty,
                effectiveInitialSteps,
                maxOrderQty,
                settings.maxInventoryQty(),
                settings.maxInventoryQty(),
                100,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                settings.orderTtl(),
                Duration.ZERO
        );
    }

    private BigDecimal effectiveInitialSteps(BigDecimal rawQty) {
        BigDecimal baseQty = cycleBaseGridQty();
        if (rawQty == null || rawQty.signum() <= 0 || baseQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stepsByTarget = rawQty.divide(baseQty, 8, RoundingMode.DOWN);
        BigDecimal maxSteps = settings.mode() == RebalanceMode.AUTO_CYCLE
                ? settings.autoInitialSteps()
                : settings.maxInitialSteps();
        return stepsByTarget.min(maxSteps).max(BigDecimal.ZERO);
    }

    private BigDecimal cycleBaseGridQty() {
        return settings.mode() == RebalanceMode.AUTO_CYCLE ? settings.autoBaseGridQty() : settings.baseGridQty();
    }

    private boolean rebalanceGridRunning() {
        return (rebalanceEngine != null && rebalanceEngine.runtimeState().state() != GridState.INIT)
                || (rebalanceLedger != null && rebalanceLedger.snapshot().netQty().signum() != 0)
                || !activeRebalanceOrders().isEmpty();
    }

    private boolean rebalanceCycleActive() {
        return rebalanceGridRunning()
                || (state != null && "GRID_ACTIVE".equals(state.status()));
    }

    private boolean rebalanceGridFlat(TradeSide cycleSide) {
        if (rebalanceLedger == null) {
            return false;
        }
        int netSign = rebalanceLedger.snapshot().netQty().signum();
        if (netSign == 0) {
            return true;
        }
        if (cycleSide == TradeSide.SELL) {
            return netSign > 0;
        }
        if (cycleSide == TradeSide.BUY) {
            return netSign < 0;
        }
        return false;
    }

    private String rebalanceGridFlatReason(TradeSide cycleSide) {
        if (rebalanceLedger == null || rebalanceLedger.snapshot().netQty().signum() == 0) {
            return "rebalance grid position is flat; stopped";
        }
        return "rebalance grid position crossed zero; stopped";
    }

    private boolean targetReached(BigDecimal currentRatio, BigDecimal targetRatio, TradeSide cycleSide) {
        if (currentRatio == null || targetRatio == null) {
            return false;
        }
        TradeSide side = cycleSide;
        if (side == null && rebalanceLedger != null) {
            side = rebalanceLedger.snapshot().netQty().signum() < 0 ? TradeSide.SELL : TradeSide.BUY;
        }
        if (side == TradeSide.SELL) {
            return currentRatio.compareTo(targetRatio) <= 0;
        }
        if (side == TradeSide.BUY) {
            return currentRatio.compareTo(targetRatio) >= 0;
        }
        return false;
    }

    private TradeSide cycleEntrySide() {
        if (state != null && state.activeSide() != null) {
            return state.activeSide();
        }
        if (rebalanceLedger != null) {
            int netSign = rebalanceLedger.snapshot().netQty().signum();
            if (netSign < 0) {
                return TradeSide.SELL;
            }
            if (netSign > 0) {
                return TradeSide.BUY;
            }
        }
        return null;
    }

    private BigDecimal lockedCycleTargetRatio(BigDecimal fallbackTargetRatio) {
        if (state == null || state.targetRatio() == null || state.targetRatio().signum() <= 0) {
            return fallbackTargetRatio;
        }
        return state.targetRatio();
    }

    private void stopRebalanceGrid(String reason) {
        cancelActiveOrder(reason);
        cycleEndObserver.accept(reason);
        rebalanceEngine = null;
        rebalanceLedger = null;
        rebalanceCommandExecutor = null;
        activeCycleStartedAt = null;
    }

    public Instant activeCycleStartedAt() {
        return activeCycleStartedAt;
    }

    private void recoverMissingPairIfNeeded(MarketSnapshot marketSnapshot) {
        if (rebalanceEngine == null || rebalanceLedger == null || rebalanceCommandExecutor == null) {
            return;
        }
        List<WorkingOrder> activeOrders = activeRebalanceOrders();
        if (completePair(activeOrders) || rebalanceLedger.snapshot().netQty().signum() == 0) {
            return;
        }
        if (!activeOrders.isEmpty()) {
            List<StrategyCommand> cancels = activeOrders.stream()
                    .map(order -> StrategyCommand.cancel(order.symbol(), order.clientOrderId(), "rebalance-incomplete-pair"))
                    .toList();
            List<String> cancelFailures = rebalanceCommandExecutor.execute(cancels, rebalanceLedger.snapshot());
            if (!cancelFailures.isEmpty()) {
                eventLogger.error("rebalance.grid.incomplete-pair.cancel.failed", Map.of(
                        "symbol", settings.symbol(),
                        "message", cancelFailures.get(0)
                ));
                return;
            }
            eventLogger.warn("rebalance.grid.incomplete-pair", Map.of(
                    "symbol", settings.symbol(),
                    "openOrders", activeOrders.size(),
                    "clientOrderIds", activeOrders.stream().map(WorkingOrder::clientOrderId).toList()
            ));
        }
        if (rebalanceEngine.runtimeState().activeOrderPair() != null
                || rebalanceEngine.runtimeState().state() == GridState.GRID_ACTIVE) {
            rebalanceEngine.restoreRuntimeState(new StrategyRuntimeState(
                    GridState.WAITING_TRIGGER,
                    null,
                    rebalanceEngine.runtimeState().lastTriggerTime()
            ));
        }
        List<StrategyCommand> commands = rebalanceEngine.recoverPairFromLastFill(marketSnapshot, "rebalance-recover-pair");
        if (commands.isEmpty()) {
            return;
        }
        List<String> failures = rebalanceCommandExecutor.execute(commands, rebalanceLedger.snapshot());
        if (!failures.isEmpty()) {
            eventLogger.error("rebalance.grid.recover-pair.failed", Map.of("symbol", settings.symbol(), "message", failures.get(0)));
        } else {
            eventLogger.info("rebalance.grid.recover-pair", Map.of("symbol", settings.symbol(), "count", commands.size()));
        }
    }

    void cancelOrphanOrdersIfNeeded() {
        if (rebalanceEngine == null || rebalanceCommandExecutor == null || rebalanceLedger == null) {
            return;
        }
        ActiveOrderPair activePair = rebalanceEngine.runtimeState().activeOrderPair();
        if (activePair == null) {
            return;
        }
        List<WorkingOrder> orphanOrders = activeRebalanceOrders().stream()
                .filter(order -> !activePair.contains(order.clientOrderId()))
                .toList();
        if (orphanOrders.isEmpty()) {
            return;
        }
        List<StrategyCommand> cancels = orphanOrders.stream()
                .map(order -> StrategyCommand.cancel(order.symbol(), order.clientOrderId(), "rebalance-orphan-order"))
                .toList();
        List<String> failures = rebalanceCommandExecutor.execute(cancels, rebalanceLedger.snapshot());
        if (!failures.isEmpty()) {
            eventLogger.error("rebalance.grid.orphan.cancel.failed", Map.of(
                    "symbol", settings.symbol(),
                    "clientOrderIds", orphanOrders.stream().map(WorkingOrder::clientOrderId).toList(),
                    "message", failures.get(0)
            ));
            return;
        }
        eventLogger.warn("rebalance.grid.orphan.canceled", Map.of(
                "symbol", settings.symbol(),
                "count", orphanOrders.size(),
                "clientOrderIds", orphanOrders.stream().map(WorkingOrder::clientOrderId).toList()
        ));
    }

    private boolean completePair(List<WorkingOrder> activeOrders) {
        if (activeOrders == null || activeOrders.isEmpty()) {
            return false;
        }
        boolean hasBuy = activeOrders.stream().anyMatch(order -> order.side() == TradeSide.BUY);
        boolean hasSell = activeOrders.stream().anyMatch(order -> order.side() == TradeSide.SELL);
        return hasBuy && hasSell;
    }

    private void requoteTimedOutPairIfNeeded(MarketSnapshot marketSnapshot) {
        if (rebalanceEngine == null || rebalanceLedger == null || rebalanceCommandExecutor == null) {
            return;
        }
        GridState runtimeState = rebalanceEngine.runtimeState().state();
        if (runtimeState != GridState.GRID_ACTIVE && runtimeState != GridState.REQUOTE) {
            return;
        }
        List<StrategyCommand> commands = rebalanceEngine.onMarket(marketSnapshot);
        if (commands.isEmpty()) {
            return;
        }
        List<String> failures = rebalanceCommandExecutor.execute(commands, rebalanceLedger.snapshot());
        if (!failures.isEmpty()) {
            eventLogger.error("rebalance.grid.requote.failed", Map.of("symbol", settings.symbol(), "message", failures.get(0)));
        } else {
            eventLogger.info("rebalance.grid.requote", Map.of(
                    "symbol", settings.symbol(),
                    "count", commands.size(),
                    "shrinkFactor", settings.gridRequoteShrinkFactor()
            ));
        }
    }

    private StrategyLedgerSnapshot snapshotFromFills(List<FillRecord> fills, BigDecimal netQty) {
        FillRecord last = fills.get(fills.size() - 1);
        FillRecord previous = fills.size() < 2 ? null : fills.get(fills.size() - 2);
        BigDecimal avgEntryPrice = netQty.signum() == 0
                ? BigDecimal.ZERO
                : zero(last.cumulativeValue()).divide(netQty, java.math.MathContext.DECIMAL64).abs();
        return new StrategyLedgerSnapshot(
                settings.symbol(),
                netQty,
                avgEntryPrice,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                previous == null ? BigDecimal.ZERO : zero(previous.price()),
                zero(last.price()),
                "SELL".equals(last.side()) ? TradeSide.SELL : TradeSide.BUY,
                BandZone.INSIDE_BAND,
                0,
                0,
                InventoryMode.NORMAL,
                last.time() == null ? Instant.now() : last.time()
        );
    }

    private ActiveOrderPair activePairFromOrders(List<WorkingOrder> activeOrders) {
        OrderIntent buy = null;
        OrderIntent sell = null;
        for (WorkingOrder order : activeOrders) {
            OrderIntent intent = new OrderIntent(
                    order.symbol(),
                    order.clientOrderId(),
                    order.side(),
                    order.orderType(),
                    order.qty(),
                    order.price(),
                    order.reduceOnly(),
                    "restored-rebalance-order"
            );
            if (order.side() == TradeSide.BUY && buy == null) {
                buy = intent;
            } else if (order.side() == TradeSide.SELL && sell == null) {
                sell = intent;
            }
        }
        if (buy == null && sell == null) {
            return null;
        }
        return new ActiveOrderPair("RBAL_RESTORED_" + Instant.now().toEpochMilli(), buy, sell, Instant.now());
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<WorkingOrder> activeRebalanceOrders() {
        return workingOrderRegistry.snapshot().stream()
                .filter(order -> order != null && order.status() == OrderStatus.NEW)
                .filter(order -> isRebalanceClientOrder(order.clientOrderId()))
                .toList();
    }

    private BigDecimal targetRatio(BigDecimal price, boolean usePreviousTargetForHysteresis) {
        List<RebalanceBand> bands = settings.bands().stream()
                .sorted(Comparator.comparing(RebalanceBand::priceFloor))
                .toList();
        if (bands.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int rawIndex = bandIndexForPrice(bands, price);
        BigDecimal rawTarget = bands.get(rawIndex).targetRatio();
        BigDecimal previousTarget = usePreviousTargetForHysteresis && state != null ? state.targetRatio() : null;
        int previousIndex = bandIndexForTarget(bands, previousTarget);
        BigDecimal hysteresis = settings.bandHysteresisRatio() == null
                ? BigDecimal.ZERO
                : settings.bandHysteresisRatio();
        if (previousIndex < 0 || previousIndex == rawIndex || hysteresis.signum() <= 0) {
            return rawTarget;
        }
        if (rawIndex > previousIndex) {
            BigDecimal switchUpPrice = bands.get(rawIndex).priceFloor().multiply(BigDecimal.ONE.add(hysteresis));
            return price.compareTo(switchUpPrice) >= 0 ? rawTarget : bands.get(previousIndex).targetRatio();
        }
        BigDecimal switchDownPrice = bands.get(previousIndex).priceFloor().multiply(BigDecimal.ONE.subtract(hysteresis));
        return price.compareTo(switchDownPrice) < 0 ? rawTarget : bands.get(previousIndex).targetRatio();
    }

    private int bandIndexForPrice(List<RebalanceBand> bands, BigDecimal price) {
        int index = 0;
        for (int i = 0; i < bands.size(); i++) {
            if (price.compareTo(bands.get(i).priceFloor()) >= 0) {
                index = i;
            }
        }
        return index;
    }

    private int bandIndexForTarget(List<RebalanceBand> bands, BigDecimal targetRatio) {
        if (targetRatio == null) {
            return -1;
        }
        for (int i = 0; i < bands.size(); i++) {
            if (bands.get(i).targetRatio().compareTo(targetRatio) == 0) {
                return i;
            }
        }
        return -1;
    }

    private List<BigDecimal> nextSamples(BigDecimal ratio) {
        List<BigDecimal> samples = new ArrayList<>(state.ratioSamples() == null ? List.of() : state.ratioSamples());
        samples.add(ratio);
        int maxSamples = Math.max(1, settings.confirmationSamples());
        while (samples.size() > maxSamples) {
            samples.remove(0);
        }
        return List.copyOf(samples);
    }

    private boolean abruptAssetChange(AccountAssetSnapshot accountSnapshot) {
        if (state == null || state.updatedAt() == null) {
            return false;
        }
        if (state.totalEquityUsdt() == null || state.totalEquityUsdt().abs().compareTo(MIN_COMPARE_VALUE) <= 0) {
            return false;
        }
        return changeRateExceeded(state.totalEquityUsdt(), accountSnapshot.totalEquityUsdt())
                || changeRateExceeded(state.baseBalance(), accountSnapshot.baseWalletBalance());
    }

    private boolean changeRateExceeded(BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null || previous.abs().compareTo(MIN_COMPARE_VALUE) <= 0) {
            return false;
        }
        BigDecimal changeRate = current.subtract(previous).abs().divide(previous.abs(), 8, RoundingMode.HALF_UP);
        return changeRate.compareTo(ABNORMAL_ASSET_CHANGE_THRESHOLD) > 0;
    }

    private BigDecimal average(List<BigDecimal> samples) {
        if (samples == null || samples.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = samples.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(samples.size()), 8, RoundingMode.HALF_UP);
    }

    private RebalanceState stateForSnapshot(
            String status,
            MarketSnapshot marketSnapshot,
            AccountAssetSnapshot accountSnapshot,
            BigDecimal targetRatio,
            BigDecimal currentRatio,
            List<BigDecimal> samples,
            String message
    ) {
        return new RebalanceState(
                settings.enabled(),
                settings.symbol(),
                status,
                targetRatio,
                currentRatio,
                accountSnapshot.baseWalletBalance(),
                accountSnapshot.contractPositionQty(),
                accountSnapshot.netBaseQty(),
                accountSnapshot.totalEquityUsdt(),
                marketSnapshot.lastPrice(),
                shouldExposeActiveSide(status) ? cycleEntrySide() : null,
                null,
                null,
                null,
                samples,
                Instant.now(),
                message
        );
    }

    private RebalanceState stateWith(String status, String message) {
        return new RebalanceState(
                settings.enabled(),
                settings.symbol(),
                status,
                state.targetRatio(),
                state.currentRatio(),
                state.baseBalance(),
                state.contractPositionQty(),
                state.netBaseQty(),
                state.totalEquityUsdt(),
                state.lastPrice(),
                state.activeSide(),
                state.activeClientOrderId(),
                state.activeOrderCreatedAt(),
                state.activeOrder(),
                state.ratioSamples(),
                Instant.now(),
                message
        );
    }

    private boolean shouldExposeActiveSide(String status) {
        return "GRID_ACTIVE".equals(status) || "GRID_OPENING".equals(status);
    }

    private void save(RebalanceState nextState) {
        state = nextState;
        stateStore.save(nextState);
    }
}
