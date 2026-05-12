package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitSdkClientFactory;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitSdkOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitSdkPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkTradeGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.ClientOrderIds;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LiveControlService {

    private final GridProperties baseProperties;
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final BybitSdkClientFactory clientFactory;
    private final BybitSdkMarketDataGateway resumeMarketDataGateway;
    private final GridStrategyEngine resumeEngine;
    private final StrategyLedger resumeLedger;
    private final Set<String> resumeProcessedExecutionIds;
    private final RuntimeStatusProvider fallbackStatusProvider;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final StateStore stateStore;
    private final StatusStore statusStore;
    private final WorkingOrderRegistry fallbackWorkingOrderRegistry;
    private final FillStore fillStore;
    private volatile GridLiveRunner activeRunner;
    private volatile GridStrategyConfig lastConfig;

    public LiveControlService(
            GridProperties baseProperties,
            BybitSdkClientFactory clientFactory,
            BybitSdkMarketDataGateway resumeMarketDataGateway,
            GridStrategyEngine resumeEngine,
            StrategyLedger resumeLedger,
            Set<String> resumeProcessedExecutionIds,
            RuntimeStatusProvider fallbackStatusProvider,
            EventLogger eventLogger,
            AlertService alertService,
            StateStore stateStore,
            StatusStore statusStore,
            WorkingOrderRegistry fallbackWorkingOrderRegistry,
            FillStore fillStore
    ) {
        this.baseProperties = baseProperties;
        this.clientFactory = clientFactory;
        this.resumeMarketDataGateway = resumeMarketDataGateway;
        this.resumeEngine = resumeEngine;
        this.resumeLedger = resumeLedger;
        this.resumeProcessedExecutionIds = resumeProcessedExecutionIds;
        this.fallbackStatusProvider = fallbackStatusProvider;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.stateStore = stateStore;
        this.statusStore = statusStore;
        this.fallbackWorkingOrderRegistry = fallbackWorkingOrderRegistry;
        this.fillStore = fillStore;
        this.lastConfig = baseProperties.strategyConfig();
    }

    public synchronized Map<String, Object> start(StartRequest request) {
        if (activeRunner != null && activeRunner.isRunning()) {
            return Map.of("ok", false, "message", "strategy already running");
        }
        if (baseProperties.bybitSdkConfig().apiKey().isBlank() || baseProperties.bybitSdkConfig().apiSecret().isBlank()) {
            return Map.of("ok", false, "message", "missing bybit api key or secret");
        }
        GridStrategyConfig config = request.toConfig(baseProperties.strategyConfig());
        lastConfig = config;
        GridProperties properties = new GridProperties(
                config,
                baseProperties.bybitSdkConfig(),
                baseProperties.runtimeSettings(),
                baseProperties.rawProperties()
        );
        BybitSdkMarketDataGateway marketDataGateway = new BybitSdkMarketDataGateway(
                clientFactory,
                config.category(),
                new FourHourIndicatorCalculator(),
                config.emaLength4h(),
                config.bollLength4h(),
                config.bollStdDevMultiplier()
        );
        InstrumentSpec instrumentSpec = marketDataGateway.loadInstrumentSpec(config.symbol());
        StrategyLedger strategyLedger = new InMemoryStrategyLedger(config.symbol());
        GridStrategyEngine engine = new GridStrategyEngine(
                config,
                instrumentSpec,
                strategyLedger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory()),
                true
        );
        WorkingOrderRegistry workingOrderRegistry = new WorkingOrderRegistry();
        Set<String> processedExecutionIds = ConcurrentHashMap.newKeySet();
        BybitSdkTradeGateway tradeGateway = new BybitSdkTradeGateway(clientFactory, config.category());
        GridLiveRunner runner = new GridLiveRunner(
                properties,
                marketDataGateway,
                new BybitSdkPrivateStreamGateway(properties.bybitSdkConfig()),
                engine,
                strategyLedger,
                new LiveCommandExecutor(tradeGateway, workingOrderRegistry, new RiskGuardService(config), eventLogger, alertService),
                new BybitSdkOrderQueryGateway(clientFactory, config.category()),
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                this::recordFill
        );
        runner.requestStopAfterCycle();
        activeRunner = runner;
        try {
            runner.start();
        } catch (RuntimeException e) {
            activeRunner = null;
            try {
                runner.close();
            } catch (RuntimeException closeException) {
                e.addSuppressed(closeException);
            }
            eventLogger.error("live.start.failed", Map.of("symbol", config.symbol(), "message", e.getMessage()));
            alertService.warning("LIVE_START_FAILED", "Live start failed", Map.of("symbol", config.symbol(), "message", e.getMessage()));
            return Map.of("ok", false, "message", e.getMessage());
        }
        return Map.of("ok", true, "message", "started", "symbol", config.symbol());
    }

    public synchronized Map<String, Object> stop() {
        if (activeRunner == null || !activeRunner.isRunning()) {
            return Map.of("ok", false, "message", "strategy not running");
        }
        activeRunner.stopNow();
        return Map.of("ok", true, "message", "stopped");
    }

    public synchronized Map<String, Object> flattenAndStop() {
        if (activeRunner == null || !activeRunner.isRunning()) {
            return Map.of("ok", false, "message", "strategy not running");
        }
        activeRunner.flattenAndStop();
        return Map.of("ok", true, "message", "flatten submitted and stopped");
    }

    public synchronized Map<String, Object> resume() {
        if (activeRunner != null && activeRunner.isRunning()) {
            return Map.of("ok", false, "message", "strategy already running");
        }
        if (baseProperties.bybitSdkConfig().apiKey().isBlank() || baseProperties.bybitSdkConfig().apiSecret().isBlank()) {
            return Map.of("ok", false, "message", "missing bybit api key or secret");
        }
        RuntimeStatusSnapshot snapshot = fallbackStatusProvider.snapshot();
        if (!canResume(snapshot)) {
            return Map.of("ok", false, "message", "no resumable strategy state");
        }
        GridStrategyConfig config = resumeEngine.config();
        lastConfig = config;
        BybitSdkTradeGateway tradeGateway = new BybitSdkTradeGateway(clientFactory, config.category());
        GridLiveRunner runner = new GridLiveRunner(
                baseProperties,
                resumeMarketDataGateway,
                new BybitSdkPrivateStreamGateway(baseProperties.bybitSdkConfig()),
                resumeEngine,
                resumeLedger,
                new LiveCommandExecutor(tradeGateway, fallbackWorkingOrderRegistry, new RiskGuardService(config), eventLogger, alertService),
                new BybitSdkOrderQueryGateway(clientFactory, config.category()),
                stateStore,
                fallbackWorkingOrderRegistry,
                resumeProcessedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                this::recordFill
        );
        runner.requestStopAfterCycle();
        activeRunner = runner;
        try {
            runner.start();
        } catch (RuntimeException e) {
            activeRunner = null;
            try {
                runner.close();
            } catch (RuntimeException closeException) {
                e.addSuppressed(closeException);
            }
            eventLogger.error("live.resume.failed", Map.of("symbol", config.symbol(), "message", e.getMessage()));
            alertService.warning("LIVE_RESUME_FAILED", "Live resume failed", Map.of("symbol", config.symbol(), "message", e.getMessage()));
            return Map.of("ok", false, "message", e.getMessage());
        }
        return Map.of("ok", true, "message", "resumed", "symbol", config.symbol(), "state", resumeEngine.runtimeState().state().name());
    }

    public synchronized Map<String, Object> cancelOrder(CancelOrderRequest request) {
        String clientOrderId = request == null ? "" : request.clientOrderId();
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return Map.of("ok", false, "message", "missing clientOrderId");
        }
        clientOrderId = clientOrderId.trim();

        GridLiveRunner runner = activeRunner;
        if (runner != null && runner.isRunning()) {
            String symbol = symbolFor(clientOrderId, request.symbol(), runner.snapshot());
            List<String> failures = runner.cancelOrder(symbol, clientOrderId);
            return failures.isEmpty()
                    ? Map.of("ok", true, "message", "cancel submitted", "clientOrderId", clientOrderId)
                    : Map.of("ok", false, "message", failures.get(0), "clientOrderId", clientOrderId);
        }

        if (baseProperties.bybitSdkConfig().apiKey().isBlank() || baseProperties.bybitSdkConfig().apiSecret().isBlank()) {
            return Map.of("ok", false, "message", "missing bybit api key or secret");
        }

        RuntimeStatusSnapshot snapshot = fallbackStatusProvider.snapshot();
        String symbol = symbolFor(clientOrderId, request.symbol(), snapshot);
        fallbackWorkingOrderRegistry.markCanceled(clientOrderId, Instant.now());
        statusStore.save(fallbackStatusProvider.snapshot());
        try {
            new BybitSdkTradeGateway(clientFactory, lastConfig.category()).cancel(symbol, clientOrderId);
            eventLogger.info("command.cancel", Map.of(
                    "commandType", "CANCEL",
                    "symbol", symbol,
                    "clientOrderId", clientOrderId,
                    "reason", "manual-cancel"
            ));
            return Map.of("ok", true, "message", "cancel submitted", "clientOrderId", clientOrderId);
        } catch (RuntimeException e) {
            eventLogger.error("command.cancel.failed", Map.of(
                    "commandType", "CANCEL",
                    "symbol", symbol,
                    "clientOrderId", clientOrderId,
                    "reason", "manual-cancel",
                    "message", e.getMessage()
            ));
            alertService.warning("ORDER_CANCEL_FAILED", "Bybit order cancellation failed", Map.of(
                    "clientOrderId", clientOrderId,
                    "symbol", symbol,
                    "message", e.getMessage()
            ));
            return Map.of("ok", false, "message", e.getMessage(), "clientOrderId", clientOrderId);
        }
    }

    public RuntimeStatusSnapshot snapshot() {
        GridLiveRunner runner = activeRunner;
        return runner != null ? runner.snapshot() : fallbackStatusProvider.snapshot();
    }

    public Map<String, Object> controlState() {
        GridLiveRunner runner = activeRunner;
        RuntimeStatusSnapshot snapshot = snapshot();
        return Map.of(
                "running", runner != null && runner.isRunning(),
                "canResume", (runner == null || !runner.isRunning()) && canResume(snapshot),
                "stopAfterCycle", runner != null && runner.isStopAfterCycleRequested(),
                "config", lastConfig
        );
    }

    public List<FillRecord> recentFills() {
        return fillStore.recent(200);
    }

    public List<FillRecord> recentCycles() {
        return fillStore.recentCycles(200);
    }

    public List<FillRecord> cycleFills(String cycleExecutionId) {
        return fillStore.cycleFills(cycleExecutionId);
    }

    public CurrentCycleSummary currentCycle() {
        RuntimeStatusSnapshot snapshot = snapshot();
        BigDecimal markPrice = snapshot.latestMarketSnapshot() == null
                ? null
                : snapshot.latestMarketSnapshot().markPrice();
        return fillStore.currentCycle(snapshot.symbol(), Instant.now(), markPrice);
    }

    private void recordFill(FillEvent fillEvent) {
        RuntimeStatusSnapshot runtimeSnapshot = currentRuntimeSnapshot();
        StrategyLedgerSnapshot ledgerSnapshot = runtimeSnapshot.ledgerSnapshot();
        FillRecord previous = ClientOrderIds.isInitialPositionOrder(fillEvent.clientOrderId())
                ? null
                : fillStore.latestTradingFill(fillEvent.symbol());
        BigDecimal value = fillValue(fillEvent);
        BigDecimal fee = fillEvent.fee() == null ? BigDecimal.ZERO : fillEvent.fee();
        BigDecimal cumulativeQty = previous == null ? BigDecimal.ZERO : previous.cumulativeQty();
        BigDecimal cumulativeValue = previous == null ? BigDecimal.ZERO : previous.cumulativeValue();
        BigDecimal cumulativeFee = previous == null ? BigDecimal.ZERO : previous.cumulativeFee();
        if (fillEvent.side() == com.btcgrid.common.model.TradeSide.BUY) {
            cumulativeQty = cumulativeQty.add(fillEvent.fillQty(), MATH_CONTEXT);
            cumulativeValue = cumulativeValue.add(value, MATH_CONTEXT);
        } else {
            cumulativeQty = cumulativeQty.subtract(fillEvent.fillQty(), MATH_CONTEXT);
            cumulativeValue = cumulativeValue.subtract(value, MATH_CONTEXT);
        }
        cumulativeFee = cumulativeFee.add(fee, MATH_CONTEXT);
        BigDecimal markPrice = runtimeSnapshot.latestMarketSnapshot() == null
                ? fillEvent.fillPrice()
                : runtimeSnapshot.latestMarketSnapshot().markPrice();
        BigDecimal floatingPnlAtFill = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, fillEvent.fillPrice());
        BigDecimal totalPnlAtFill = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, markPrice);
        FillRecord record = new FillRecord(
                fillEvent.fillTime(),
                fillEvent.symbol(),
                fillEvent.side().name(),
                fillEvent.fillQty(),
                fillEvent.fillPrice(),
                value,
                fee,
                fillEvent.feeCurrency(),
                cumulativeQty,
                cumulativeValue,
                cumulativeFee,
                ledgerSnapshot.netQty(),
                ledgerSnapshot.avgEntryPrice(),
                ledgerSnapshot.realizedPnl(),
                floatingPnlAtFill,
                totalPnlAtFill,
                fillEvent.clientOrderId(),
                fillEvent.executionId()
        );
        fillStore.save(record);
        if (isCycleEnd(runtimeSnapshot)) {
            fillStore.save(cycleEndRecord(fillEvent, ledgerSnapshot, totalPnlAtFill, cumulativeQty, cumulativeValue, cumulativeFee));
        }
    }

    private RuntimeStatusSnapshot currentRuntimeSnapshot() {
        GridLiveRunner runner = activeRunner;
        return runner != null ? runner.snapshot() : fallbackStatusProvider.snapshot();
    }

    private boolean isCycleEnd(RuntimeStatusSnapshot snapshot) {
        return snapshot.ledgerSnapshot().netQty().signum() == 0
                && snapshot.runtimeState().state() == com.btcgrid.common.model.GridState.INIT;
    }

    private boolean canResume(RuntimeStatusSnapshot snapshot) {
        GridState state = snapshot.runtimeState().state();
        return state != GridState.INIT
                && (snapshot.ledgerSnapshot().netQty().signum() != 0 || snapshot.runtimeState().activeOrderPair() != null);
    }

    private FillRecord cycleEndRecord(
            FillEvent fillEvent,
            StrategyLedgerSnapshot ledgerSnapshot,
            BigDecimal totalPnlAtFill,
            BigDecimal cumulativeQty,
            BigDecimal cumulativeValue,
            BigDecimal cumulativeFee
    ) {
        String executionId = fillEvent.executionId() == null || fillEvent.executionId().isBlank()
                ? "CYCLE_END_" + fillEvent.clientOrderId() + "_" + fillEvent.fillTime().toEpochMilli()
                : "CYCLE_END_" + fillEvent.executionId();
        return new FillRecord(
                fillEvent.fillTime().plusMillis(1),
                fillEvent.symbol(),
                "CYCLE_END",
                BigDecimal.ZERO,
                fillEvent.fillPrice(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                fillEvent.feeCurrency(),
                cumulativeQty,
                cumulativeValue,
                cumulativeFee,
                ledgerSnapshot.netQty(),
                ledgerSnapshot.avgEntryPrice(),
                ledgerSnapshot.realizedPnl(),
                BigDecimal.ZERO,
                totalPnlAtFill,
                "CYCLE_END_" + fillEvent.clientOrderId(),
                executionId
        );
    }

    private BigDecimal fillValue(FillEvent fillEvent) {
        if (fillEvent.fillValue() != null && fillEvent.fillValue().signum() != 0) {
            return fillEvent.fillValue();
        }
        return fillEvent.fillQty().multiply(fillEvent.fillPrice(), MATH_CONTEXT);
    }

    private BigDecimal cumulativeProfit(
            BigDecimal cumulativeQty,
            BigDecimal cumulativeValue,
            BigDecimal cumulativeFee,
            BigDecimal lastPrice
    ) {
        return cumulativeQty.multiply(lastPrice, MATH_CONTEXT)
                .subtract(cumulativeValue, MATH_CONTEXT)
                .subtract(cumulativeFee, MATH_CONTEXT);
    }

    private String symbolFor(String clientOrderId, String requestedSymbol, RuntimeStatusSnapshot snapshot) {
        if (requestedSymbol != null && !requestedSymbol.isBlank()) {
            return requestedSymbol.trim().toUpperCase();
        }
        if (snapshot != null && snapshot.workingOrders() != null) {
            for (WorkingOrder order : snapshot.workingOrders()) {
                if (clientOrderId.equals(order.clientOrderId())) {
                    return order.symbol();
                }
            }
        }
        return lastConfig.symbol();
    }

    public record CancelOrderRequest(String symbol, String clientOrderId) {
    }

    public record StartRequest(
            String symbol,
            String entryDirection,
            BigDecimal baseGridSpacing,
            BigDecimal gridRequoteShrinkFactor,
            BigDecimal baseGridQty,
            BigDecimal maxInitialSteps,
            BigDecimal maxOrderQty,
            BigDecimal maxInventoryQty,
            BigDecimal maxDirectionalInventoryQty,
            Integer emaLength4h,
            Integer bollLength4h,
            BigDecimal bollStdDevMultiplier,
            Integer pairOrderTimeoutMinutes,
            Integer triggerCooldownMinutes
    ) {
        private GridStrategyConfig toConfig(GridStrategyConfig defaults) {
            return new GridStrategyConfig(
                    defaults.category(),
                    stringOr(symbol, defaults.symbol()),
                    entryDirectionOr(entryDirection, defaults.entryDirection()),
                    decimalOr(baseGridSpacing, defaults.baseGridSpacing()),
                    decimalOr(gridRequoteShrinkFactor, defaults.gridRequoteShrinkFactor()),
                    decimalOr(baseGridQty, defaults.baseGridQty()),
                    decimalOr(maxInitialSteps, defaults.maxInitialSteps()),
                    decimalOr(maxOrderQty, defaults.maxOrderQty()),
                    decimalOr(maxInventoryQty, defaults.maxInventoryQty()),
                    decimalOr(maxDirectionalInventoryQty, defaults.maxDirectionalInventoryQty()),
                    defaults.maxConsecutiveSameSideFills(),
                    intOr(emaLength4h, defaults.emaLength4h()),
                    intOr(bollLength4h, defaults.bollLength4h()),
                    decimalOr(bollStdDevMultiplier, defaults.bollStdDevMultiplier()),
                    defaults.deinventoryPnlThresholdRate(),
                    defaults.deinventoryBoostSlope(),
                    defaults.deinventoryBoostCap(),
                    true,
                    Duration.ofMinutes(intOr(pairOrderTimeoutMinutes, (int) defaults.pairOrderTimeout().toMinutes())),
                    Duration.ofMinutes(intOr(triggerCooldownMinutes, (int) defaults.triggerCooldown().toMinutes()))
            );
        }

        private static String stringOr(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
        }

        private static EntryDirection entryDirectionOr(String value, EntryDirection fallback) {
            return value == null || value.isBlank() ? fallback : EntryDirection.fromConfig(value);
        }

        private static BigDecimal decimalOr(BigDecimal value, BigDecimal fallback) {
            return value == null ? fallback : value;
        }

        private static int intOr(Integer value, int fallback) {
            return value == null ? fallback : value;
        }
    }
}
