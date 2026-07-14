package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitSdkAccountGateway;
import com.btcgrid.bybit.adapter.BybitSdkFundingFeeQueryGateway;
import com.btcgrid.bybit.adapter.BybitSdkClientFactory;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitSdkOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitSdkPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkTradeGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.ClientOrderIds;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.FundingFeeEvent;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class LiveControlService {

    private final GridProperties baseProperties;
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    private final WorkingOrderRegistry rebalanceWorkingOrderRegistry = new WorkingOrderRegistry();
    private final FillStore fillStore;
    private final Map<String, String> orderReasonCache = new ConcurrentHashMap<>();
    private final ExecutorService flattenStopExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "flatten-stop-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<FlattenStopStatus> flattenStopStatus = new AtomicReference<>(FlattenStopStatus.idle());
    private volatile GridLiveRunner activeRunner;
    private volatile RebalanceLiveRunner activeRebalanceRunner;
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
        RebalanceSettings rebalanceSettings = currentRebalanceSettings();
        if (rebalanceSettings.enabled()) {
            startRebalanceRunner(rebalanceSettings);
        }
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
        RebalanceSettings rebalanceSettings = currentRebalanceSettings();
        GridProperties properties = new GridProperties(
                config,
                rebalanceSettings,
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
                ordinaryGridOrderPlanner(),
                true
        );
        restoreSpotLongInventoryIfEnough(config, strategyLedger, engine, marketDataGateway);
        WorkingOrderRegistry workingOrderRegistry = new WorkingOrderRegistry();
        Set<String> processedExecutionIds = ConcurrentHashMap.newKeySet();
        BybitSdkTradeGateway tradeGateway = new BybitSdkTradeGateway(clientFactory, config.category());
        GridLiveRunner runner = new GridLiveRunner(
                properties,
                marketDataGateway,
                new BybitSdkPrivateStreamGateway(properties.bybitSdkConfig()),
                engine,
                strategyLedger,
                new LiveCommandExecutor(
                        tradeGateway,
                        workingOrderRegistry,
                        new RiskGuardService(config),
                        eventLogger,
                        alertService,
                        config.category(),
                        new BybitSdkAccountGateway(clientFactory),
                        this::recordSpotInventoryDepletedCycleEnd
                ),
                new BybitSdkOrderQueryGateway(clientFactory, config.category()),
                new BybitSdkFundingFeeQueryGateway(clientFactory, config.category()),
                stateStore,
                workingOrderRegistry,
                processedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                this::recordFill,
                this::recordFundingFee,
                null
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

    private void restoreSpotLongInventoryIfEnough(
            GridStrategyConfig config,
            StrategyLedger strategyLedger,
            GridStrategyEngine engine,
            BybitSdkMarketDataGateway marketDataGateway
    ) {
        if (!config.entryDirection().isSpotLongOnly() || config.category() != ProductCategory.SPOT) {
            return;
        }
        BigDecimal targetInitialQty = config.baseGridQty().multiply(config.maxInitialSteps(), MATH_CONTEXT);
        if (targetInitialQty.signum() <= 0) {
            return;
        }
        CurrentCycleSummary openCycle = fillStore.currentCycle(config.symbol(), Instant.now(), null);
        if (openCycle != null && openCycle.cumulativeQty() != null && openCycle.cumulativeQty().signum() != 0) {
            restoreSpotLongOpenCycle(config, strategyLedger, engine, marketDataGateway, openCycle);
            eventLogger.info("spot.inventory.start.skip", Map.of(
                    "symbol", config.symbol(),
                    "cycleQty", openCycle.cumulativeQty(),
                    "reason", "open cycle already exists"
            ));
            return;
        }
        String baseCoin = FillEvent.baseCoin(config.symbol());
        String quoteCoin = quoteCoin(config.symbol());
        if (baseCoin == null || quoteCoin == null) {
            return;
        }
        AccountAssetSnapshot accountSnapshot = new BybitSdkAccountGateway(clientFactory).tradingWalletSnapshot(
                "UNIFIED",
                baseCoin,
                quoteCoin,
                config.category(),
                config.symbol()
        );
        BigDecimal walletQty = accountSnapshot.baseWalletBalance();
        if (walletQty == null || walletQty.compareTo(targetInitialQty) < 0) {
            eventLogger.info("spot.inventory.start.skip", Map.of(
                    "symbol", config.symbol(),
                    "baseCoin", baseCoin,
                    "walletQty", walletQty == null ? BigDecimal.ZERO : walletQty,
                    "targetInitialQty", targetInitialQty,
                    "reason", "wallet below target initial quantity"
            ));
            return;
        }

        MarketSnapshot marketSnapshot = marketDataGateway.latestSnapshot(config.symbol());
        BigDecimal price = marketSnapshot.markPrice().signum() > 0 ? marketSnapshot.markPrice() : marketSnapshot.lastPrice();
        strategyLedger.restore(new StrategyLedgerSnapshot(
                config.symbol(),
                walletQty,
                price,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                price,
                TradeSide.BUY,
                BandZone.INSIDE_BAND,
                0,
                0,
                com.btcgrid.common.model.InventoryMode.NORMAL,
                accountSnapshot.timestamp()
        ));
        recordSpotInventoryStart(config, accountSnapshot.timestamp(), walletQty, price, targetInitialQty, baseCoin);
        engine.restoreRuntimeState(new StrategyRuntimeState(GridState.WAITING_TRIGGER, null, Instant.EPOCH));
        eventLogger.info("spot.inventory.start.restored", Map.of(
                "symbol", config.symbol(),
                "baseCoin", baseCoin,
                "walletQty", walletQty,
                "targetInitialQty", targetInitialQty,
                "referencePrice", price,
                "reason", "wallet inventory is enough; skipped initial market buy"
        ));
    }

    private void restoreSpotLongOpenCycle(
            GridStrategyConfig config,
            StrategyLedger strategyLedger,
            GridStrategyEngine engine,
            BybitSdkMarketDataGateway marketDataGateway,
            CurrentCycleSummary openCycle
    ) {
        MarketSnapshot marketSnapshot = marketDataGateway.latestSnapshot(config.symbol());
        BigDecimal avgEntryPrice = openCycle.cumulativeQty().signum() == 0
                ? BigDecimal.ZERO
                : openCycle.cumulativeValue().divide(openCycle.cumulativeQty(), MATH_CONTEXT).abs();
        BigDecimal referencePrice = marketSnapshot.markPrice().signum() > 0
                ? marketSnapshot.markPrice()
                : marketSnapshot.lastPrice();
        BigDecimal floatingPnl = openCycle.cumulativeQty()
                .multiply(referencePrice.subtract(avgEntryPrice, MATH_CONTEXT), MATH_CONTEXT);
        strategyLedger.restore(new StrategyLedgerSnapshot(
                config.symbol(),
                openCycle.cumulativeQty(),
                avgEntryPrice,
                BigDecimal.ZERO,
                floatingPnl,
                BigDecimal.ZERO,
                openCycle.latestFillPrice(),
                openCycle.cumulativeQty().signum() >= 0 ? TradeSide.BUY : TradeSide.SELL,
                BandZone.INSIDE_BAND,
                openCycle.buyCount(),
                openCycle.sellCount(),
                com.btcgrid.common.model.InventoryMode.NORMAL,
                Instant.now()
        ));
        engine.restoreRuntimeState(new StrategyRuntimeState(GridState.WAITING_TRIGGER, null, Instant.EPOCH));
    }

    private void recordSpotInventoryStart(
            GridStrategyConfig config,
            Instant time,
            BigDecimal walletQty,
            BigDecimal price,
            BigDecimal targetInitialQty,
            String baseCoin
    ) {
        BigDecimal value = walletQty.multiply(price, MATH_CONTEXT);
        String suffix = Long.toString((time == null ? Instant.now() : time).toEpochMilli(), 36);
        String clientOrderId = "GRID_" + config.symbol() + "_I_INV_" + suffix;
        String executionId = "SPOT_INVENTORY_START_" + config.symbol() + "_" + suffix;
        String metadataJson = spotInventoryStartMetadataJson(walletQty, targetInitialQty, baseCoin);
        fillStore.save(new FillRecord(
                time == null ? Instant.now() : time,
                config.symbol(),
                "BUY",
                walletQty,
                price,
                value,
                BigDecimal.ZERO,
                baseCoin,
                walletQty,
                value,
                BigDecimal.ZERO,
                walletQty,
                price,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                clientOrderId,
                executionId,
                metadataJson
        ));
    }

    private String spotInventoryStartMetadataJson(BigDecimal walletQty, BigDecimal targetInitialQty, String baseCoin) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("inventoryStart", true);
        meta.put("walletQty", walletQty);
        meta.put("targetInitialQty", targetInitialQty);
        meta.put("baseCoin", baseCoin);
        try {
            return OBJECT_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public synchronized Map<String, Object> flattenAndStop() {
        GridLiveRunner runner = activeRunner;
        if (runner == null || !runner.isRunning()) {
            return Map.of("ok", false, "message", "strategy not running");
        }
        FlattenStopStatus current = flattenStopStatus.get();
        if (current.running()) {
            return Map.of("ok", true, "message", "平仓并停止正在执行", "flattenStop", current);
        }

        FlattenStopStatus submitted = FlattenStopStatus.running(Instant.now(), "撤单和平仓任务已提交");
        flattenStopStatus.set(submitted);
        flattenStopExecutor.submit(() -> runFlattenAndStop(runner, submitted.startedAt()));
        return Map.of("ok", true, "message", "平仓并停止已提交，后台执行中", "flattenStop", submitted);
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
        InstrumentSpec instrumentSpec = resumeMarketDataGateway.loadInstrumentSpec(config.symbol());
        GridStrategyEngine liveResumeEngine = new GridStrategyEngine(
                config,
                instrumentSpec,
                resumeLedger,
                new GridTriggerEvaluator(),
                ordinaryGridOrderPlanner(),
                false
        );
        liveResumeEngine.restore(resumeEngine.runtimeState(), resumeEngine.activeSpacing());
        BybitSdkTradeGateway tradeGateway = new BybitSdkTradeGateway(clientFactory, config.category());
        RebalanceSettings rebalanceSettings = currentRebalanceSettings();
        GridProperties properties = new GridProperties(
                config,
                rebalanceSettings,
                baseProperties.bybitSdkConfig(),
                baseProperties.runtimeSettings(),
                baseProperties.rawProperties()
        );
        GridLiveRunner runner = new GridLiveRunner(
                properties,
                resumeMarketDataGateway,
                new BybitSdkPrivateStreamGateway(properties.bybitSdkConfig()),
                liveResumeEngine,
                resumeLedger,
                new LiveCommandExecutor(
                        tradeGateway,
                        fallbackWorkingOrderRegistry,
                        new RiskGuardService(config),
                        eventLogger,
                        alertService,
                        config.category(),
                        new BybitSdkAccountGateway(clientFactory),
                        this::recordSpotInventoryDepletedCycleEnd
                ),
                new BybitSdkOrderQueryGateway(clientFactory, config.category()),
                new BybitSdkFundingFeeQueryGateway(clientFactory, config.category()),
                stateStore,
                fallbackWorkingOrderRegistry,
                resumeProcessedExecutionIds,
                eventLogger,
                alertService,
                statusStore,
                this::recordFill,
                this::recordFundingFee,
                null
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
        return Map.of("ok", true, "message", "resumed", "symbol", config.symbol(), "state", liveResumeEngine.runtimeState().state().name());
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
                "rebalanceRunning", activeRebalanceRunner != null && activeRebalanceRunner.isRunning(),
                "canResume", (runner == null || !runner.isRunning()) && canResume(snapshot),
                "stopAfterCycle", runner != null && runner.isStopAfterCycleRequested(),
                "flattenStop", flattenStopStatus.get(),
                "config", lastConfig
        );
    }

    private void recordSpotInventoryDepletedCycleEnd(
            String symbol,
            String clientOrderId,
            StrategyLedgerSnapshot ledgerSnapshot
    ) {
        recordSpotCycleEnd(symbol, "spot inventory depleted");
        eventLogger.warn("spot.inventory.depleted", Map.of(
                "symbol", symbol == null ? "" : symbol,
                "clientOrderId", clientOrderId == null ? "" : clientOrderId,
                "netQty", ledgerSnapshot == null ? BigDecimal.ZERO : ledgerSnapshot.netQty()
        ));
        alertService.warning("SPOT_INVENTORY_DEPLETED", "Spot inventory is insufficient; stopping grid", Map.of(
                "symbol", symbol == null ? "" : symbol,
                "clientOrderId", clientOrderId == null ? "" : clientOrderId
        ));
        GridLiveRunner runner = activeRunner;
        if (runner != null && runner.isRunning()) {
            runner.stopNow();
        }
    }

    private void recordSpotCycleEnd(String symbol, String reason) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        List<FillRecord> fills = fillStore.currentCycleFills(symbol);
        if (fills.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        FillRecord first = fills.get(0);
        FillRecord last = fills.get(fills.size() - 1);
        BigDecimal price = last.price() == null ? BigDecimal.ZERO : last.price();
        BigDecimal totalPnl = cumulativeProfit(last.cumulativeQty(), last.cumulativeValue(), last.cumulativeFee(), price);
        String executionId = "CYCLE_END_" + last.clientOrderId() + "_" + now.toEpochMilli();
        fillStore.save(new FillRecord(
                now,
                symbol.trim().toUpperCase(),
                "CYCLE_END",
                BigDecimal.ZERO,
                price,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                last.feeCurrency() == null || last.feeCurrency().isBlank() ? "USDT" : last.feeCurrency(),
                last.cumulativeQty(),
                last.cumulativeValue(),
                last.cumulativeFee(),
                last.netQty(),
                last.avgEntryPrice(),
                last.realizedPnl(),
                BigDecimal.ZERO,
                totalPnl,
                "CYCLE_END_" + last.clientOrderId(),
                executionId,
                configJson(lastConfig)
        ));
        eventLogger.info("cycle.end", Map.of(
                "symbol", symbol.trim().toUpperCase(),
                "reason", reason == null ? "" : reason,
                "executionId", executionId,
                "totalPnl", totalPnl,
                "startPrice", first.price(),
                "durationSeconds", Duration.between(first.time(), now).toSeconds(),
                "buyCount", countSide(fills, "BUY"),
                "sellCount", countSide(fills, "SELL")
        ));
    }

    public List<WorkingOrder> rebalanceOrders() {
        return rebalanceWorkingOrderRegistry.snapshot().stream()
                .sorted(Comparator.comparing(WorkingOrder::updatedAt).reversed())
                .toList();
    }

    public List<FillRecord> rebalanceFills() {
        return fillStore.currentCycleFills(rebalanceLedgerSymbol(currentRebalanceSettings().symbol()));
    }

    public PageResult<FillRecord> rebalanceFillsPage(int limit, int offset) {
        int normalizedLimit = normalizePageLimit(limit);
        int normalizedOffset = Math.max(0, offset);
        List<FillRecord> records = new ArrayList<>(fillStore.currentCycleFills(
                rebalanceLedgerSymbol(currentRebalanceSettings().symbol())
        ));
        java.util.Collections.reverse(records);
        if (normalizedOffset >= records.size()) {
            return new PageResult<>(List.of(), normalizedLimit, normalizedOffset, false);
        }
        int endExclusive = Math.min(records.size(), normalizedOffset + normalizedLimit + 1);
        List<FillRecord> page = records.subList(normalizedOffset, endExclusive);
        boolean hasMore = page.size() > normalizedLimit;
        return new PageResult<>(
                hasMore ? page.subList(0, normalizedLimit) : page,
                normalizedLimit,
                normalizedOffset,
                hasMore
        );
    }

    public List<FillRecord> rebalanceFundingFees() {
        List<FillRecord> records = new ArrayList<>(fillStore.currentCycleFills(
                rebalanceLedgerSymbol(currentRebalanceSettings().symbol())
        ).stream()
                .filter(fill -> fill != null && "FUNDING_FEE".equals(fill.side()))
                .toList());
        java.util.Collections.reverse(records);
        return records;
    }

    public List<Map<String, Object>> rebalanceCycles() {
        return rebalanceCyclesPage(200, 0).items();
    }

    public PageResult<Map<String, Object>> rebalanceCyclesPage(int limit, int offset) {
        Map<String, String> reasons = rebalanceCycleEndReasons();
        int normalizedLimit = normalizePageLimit(limit);
        int normalizedOffset = Math.max(0, offset);
        List<Map<String, Object>> rows = fillStore.recentCycles(
                        rebalanceLedgerSymbol(currentRebalanceSettings().symbol()),
                        normalizedLimit + 1,
                        normalizedOffset
                ).stream()
                .map(cycleEnd -> rebalanceCycleRow(cycleEnd, reasons.get(cycleEnd.executionId())))
                .toList();
        boolean hasMore = rows.size() > normalizedLimit;
        return new PageResult<>(
                hasMore ? rows.subList(0, normalizedLimit) : rows,
                normalizedLimit,
                normalizedOffset,
                hasMore
        );
    }

    public List<FillRecord> rebalanceCycleFills(String cycleExecutionId) {
        return fillStore.cycleFills(cycleExecutionId);
    }

    public CurrentCycleSummary rebalanceCurrentCycle() {
        RebalanceSettings settings = currentRebalanceSettings();
        BigDecimal markPrice = null;
        try {
            markPrice = new BybitSdkMarketDataGateway(
                    clientFactory,
                    settings.category(),
                    new FourHourIndicatorCalculator(),
                    baseProperties.strategyConfig().emaLength4h(),
                    baseProperties.strategyConfig().bollLength4h(),
                    baseProperties.strategyConfig().bollStdDevMultiplier()
            ).latestSnapshot(settings.symbol()).markPrice();
        } catch (RuntimeException ignored) {
            // Use latest fill price when live market data is temporarily unavailable.
        }
        return fillStore.currentCycle(rebalanceLedgerSymbol(settings.symbol()), Instant.now(), markPrice);
    }

    public List<Map<String, Object>> rebalanceHistory() {
        Path path = PathResolver.absolutePath(baseProperties.runtimeSettings().logFilePath());
        if (!Files.exists(path)) {
            return List.of();
        }
        List<Map<String, Object>> history = new ArrayList<>();
        try (var lines = Files.lines(path)) {
            lines.forEach(line -> appendRebalanceHistory(line, history));
        } catch (java.io.IOException e) {
            eventLogger.warn("rebalance.history.load.failed", Map.of("path", path.toString(), "message", e.getMessage()));
            return List.of();
        }
        return history.stream()
                .sorted((left, right) -> Double.compare(number(right.get("ts")), number(left.get("ts"))))
                .limit(200)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void appendRebalanceHistory(String line, List<Map<String, Object>> history) {
        if (line == null || !line.contains(RebalanceService.CLIENT_ORDER_PREFIX)) {
            return;
        }
        try {
            Map<String, Object> event = OBJECT_MAPPER.readValue(line, Map.class);
            Object payloadValue = event.get("payload");
            if (!(payloadValue instanceof Map<?, ?> rawPayload)) {
                return;
            }
            Map<String, Object> payload = (Map<String, Object>) rawPayload;
            String eventName = string(event.get("event"));
            String clientOrderId = string(payload.get("clientOrderId"));
            if (!RebalanceService.isRebalanceClientOrder(clientOrderId)) {
                return;
            }
            if (!("command.place".equals(eventName)
                    || "command.cancel".equals(eventName)
                    || "rebalance.fill.received".equals(eventName))) {
                return;
            }
            history.add(Map.ofEntries(
                    Map.entry("ts", event.getOrDefault("ts", 0)),
                    Map.entry("event", eventName),
                    Map.entry("symbol", string(payload.get("symbol"))),
                    Map.entry("side", string(payload.get("side"))),
                    Map.entry("qty", payload.getOrDefault("qty", "")),
                    Map.entry("price", payload.getOrDefault("price", "")),
                    Map.entry("clientOrderId", clientOrderId),
                    Map.entry("exchangeOrderId", string(payload.get("exchangeOrderId"))),
                    Map.entry("executionId", string(payload.get("executionId"))),
                    Map.entry("reason", string(payload.get("reason")))
            ));
        } catch (RuntimeException | java.io.IOException ignored) {
            // Ignore malformed legacy log lines; history is best-effort.
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException e) {
            return 0D;
        }
    }

    private GridOrderPlanner ordinaryGridOrderPlanner() {
        return new GridOrderPlanner(
                new ClientOrderIdFactory(),
                new BigDecimal(baseProperties.rawProperties().getProperty("grid.profitable-reduce-side-multiplier", "1.10").trim()),
                (market, ledger, orderPrice) -> currentCycleMarketPnl(market, orderPrice).signum() > 0
        );
    }

    private BigDecimal currentCycleMarketPnl(com.btcgrid.common.model.MarketSnapshot marketSnapshot, BigDecimal price) {
        if (marketSnapshot == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnlPrice = price == null || price.signum() <= 0 ? marketSnapshot.markPrice() : price;
        CurrentCycleSummary cycle = fillStore.currentCycle(marketSnapshot.symbol(), Instant.now(), pnlPrice);
        return cycle == null || cycle.markPricePnl() == null ? BigDecimal.ZERO : cycle.markPricePnl();
    }

    public Map<String, Object> rebalanceConfig() {
        return Map.of(
                "ok", true,
                "config", currentRebalanceConfig()
        );
    }

    public Map<String, Object> rebalanceState() {
        try {
            RebalanceSettings settings = currentRebalanceSettings();
            BybitSdkMarketDataGateway marketDataGateway = new BybitSdkMarketDataGateway(
                    clientFactory,
                    settings.category(),
                    new FourHourIndicatorCalculator(),
                    baseProperties.strategyConfig().emaLength4h(),
                    baseProperties.strategyConfig().bollLength4h(),
                    baseProperties.strategyConfig().bollStdDevMultiplier()
            );
            var marketSnapshot = marketDataGateway.latestSnapshot(settings.symbol());
            var accountSnapshot = new BybitSdkAccountGateway(clientFactory).walletSnapshot(
                    settings.accountType(),
                    settings.baseCoin(),
                    settings.quoteCoin(),
                    settings.category(),
                    settings.symbol()
            );
            BigDecimal currentRatio = accountSnapshot.totalEquityUsdt().signum() <= 0
                    ? BigDecimal.ZERO
                    : accountSnapshot.baseValue(marketSnapshot.lastPrice()).divide(accountSnapshot.totalEquityUsdt(), 8, java.math.RoundingMode.HALF_UP);
            RebalanceState savedState = new FileRebalanceStateStore(PathResolver.absolutePath(settings.stateFilePath()))
                    .load()
                    .orElse(null);
            boolean runningState = savedState != null
                    && savedState.status() != null
                    && !savedState.status().isBlank()
                    && !"DISABLED".equals(savedState.status())
                    && !"PREVIEW".equals(savedState.status())
                    && !"STOPPED_BY_USER".equals(savedState.status());
            boolean stoppedByUser = savedState != null && "STOPPED_BY_USER".equals(savedState.status());
            BigDecimal targetRatio = runningState && savedState.targetRatio() != null && savedState.targetRatio().signum() > 0
                    ? savedState.targetRatio()
                    : rebalanceTargetRatio(settings, marketSnapshot.lastPrice());
            RebalanceState state = new RebalanceState(
                    settings.enabled(),
                    settings.symbol(),
                    stoppedByUser ? "STOPPED_BY_USER" : (runningState ? savedState.status() : (settings.enabled() ? "PREVIEW" : "DISABLED")),
                    targetRatio,
                    currentRatio,
                    accountSnapshot.baseWalletBalance(),
                    accountSnapshot.contractPositionQty(),
                    accountSnapshot.netBaseQty(),
                    accountSnapshot.totalEquityUsdt(),
                    marketSnapshot.lastPrice(),
                    runningState ? savedState.activeSide() : null,
                    runningState ? savedState.activeClientOrderId() : null,
                    runningState ? savedState.activeOrderCreatedAt() : null,
                    runningState ? savedState.activeOrder() : null,
                    runningState && savedState.ratioSamples() != null ? savedState.ratioSamples() : List.of(currentRatio),
                    Instant.now(),
                    stoppedByUser ? savedState.message() : (runningState ? savedState.message() : "preview from wallet and position")
            );
            return Map.of("ok", true, "state", state);
        } catch (RuntimeException e) {
            eventLogger.warn("rebalance.state.preview.failed", Map.of("message", e.getMessage()));
        }
        return new FileRebalanceStateStore(PathResolver.absolutePath(baseProperties.rebalanceSettings().stateFilePath()))
                .load()
                .<Map<String, Object>>map(state -> Map.of("ok", true, "state", state))
                .orElseGet(() -> Map.of(
                        "ok", true,
                        "state", RebalanceState.initial(baseProperties.rebalanceSettings().enabled(), baseProperties.rebalanceSettings().symbol())
                ));
    }

    private BigDecimal rebalanceTargetRatio(RebalanceSettings settings, BigDecimal price) {
        return settings.bands().stream()
                .sorted(Comparator.comparing(RebalanceBand::priceFloor))
                .filter(band -> price.compareTo(band.priceFloor()) >= 0)
                .reduce((left, right) -> right)
                .or(() -> settings.bands().stream().min(Comparator.comparing(RebalanceBand::priceFloor)))
                .map(RebalanceBand::targetRatio)
                .orElse(BigDecimal.ZERO);
    }

    private RebalanceState currentRebalanceState() {
        RebalanceSettings settings = currentRebalanceSettings();
        return new FileRebalanceStateStore(PathResolver.absolutePath(settings.stateFilePath()))
                .load()
                .orElseGet(() -> RebalanceState.initial(settings.enabled(), settings.symbol()));
    }

    private RebalanceConfigRequest currentRebalanceConfig() {
        Path path = PathResolver.absolutePath(baseProperties.rebalanceSettings().configFilePath());
        if (!Files.exists(path)) {
            return RebalanceConfigRequest.from(baseProperties.rebalanceSettings());
        }
        try (java.io.InputStream inputStream = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(inputStream);
            RebalanceConfigRequest defaults = RebalanceConfigRequest.from(baseProperties.rebalanceSettings());
            return new RebalanceConfigRequest(
                    Boolean.parseBoolean(properties.getProperty("rebalance.enabled", String.valueOf(defaults.enabled()))),
                    properties.getProperty("rebalance.mode", defaults.mode()),
                    properties.getProperty("rebalance.auto-min-total-btc", defaults.autoMinTotalBtc()),
                    properties.getProperty("rebalance.auto-base-grid-qty", defaults.autoBaseGridQty()),
                    properties.getProperty("rebalance.auto-initial-steps", defaults.autoInitialSteps()),
                    properties.getProperty("rebalance.category", defaults.category()),
                    properties.getProperty("rebalance.symbol", defaults.symbol()),
                    properties.getProperty("rebalance.account-type", defaults.accountType()),
                    properties.getProperty("rebalance.base-coin", defaults.baseCoin()),
                    properties.getProperty("rebalance.quote-coin", defaults.quoteCoin()),
                    properties.getProperty("rebalance.price-targets", defaults.priceTargets()),
                    properties.getProperty("rebalance.ratio-threshold", defaults.ratioThreshold()),
                    properties.getProperty("rebalance.base-grid-spacing", defaults.baseGridSpacing()),
                    properties.getProperty("rebalance.grid-requote-shrink-factor", defaults.gridRequoteShrinkFactor()),
                    properties.getProperty("rebalance.base-grid-qty", defaults.baseGridQty()),
                    properties.getProperty("rebalance.max-initial-steps", defaults.maxInitialSteps()),
                    properties.getProperty("rebalance.max-order-qty", defaults.maxOrderQty()),
                    properties.getProperty("rebalance.max-inventory-qty", defaults.maxInventoryQty()),
                    properties.getProperty("rebalance.price-offset-ratio", defaults.priceOffsetRatio()),
                    properties.getProperty("rebalance.band-hysteresis-ratio", defaults.bandHysteresisRatio()),
                    properties.getProperty("rebalance.order-ttl-minutes", defaults.orderTtlMinutes()),
                    properties.getProperty("rebalance.confirmation-samples", defaults.confirmationSamples()),
                    defaults.configFilePath(),
                    defaults.stateFilePath()
            ).normalized();
        } catch (java.io.IOException | RuntimeException e) {
            eventLogger.warn("rebalance.config.load.failed", Map.of("path", path.toString(), "message", e.getMessage()));
            return RebalanceConfigRequest.from(baseProperties.rebalanceSettings());
        }
    }

    private RebalanceSettings currentRebalanceSettings() {
        RebalanceConfigRequest config = currentRebalanceConfig();
        return new RebalanceSettings(
                config.enabled(),
                RebalanceMode.fromConfig(config.mode()),
                new BigDecimal(config.autoMinTotalBtc()),
                new BigDecimal(config.autoBaseGridQty()),
                new BigDecimal(config.autoInitialSteps()),
                ProductCategory.fromConfig(config.category()),
                config.symbol(),
                config.accountType(),
                config.baseCoin(),
                config.quoteCoin(),
                parseRebalanceBands(config.priceTargets()),
                new BigDecimal(config.ratioThreshold()),
                new BigDecimal(config.baseGridSpacing()),
                new BigDecimal(config.gridRequoteShrinkFactor()),
                new BigDecimal(config.baseGridQty()),
                new BigDecimal(config.maxInitialSteps()),
                new BigDecimal(config.maxOrderQty()),
                new BigDecimal(config.maxInventoryQty()),
                new BigDecimal(config.priceOffsetRatio()),
                new BigDecimal(config.bandHysteresisRatio()),
                Duration.ofMinutes(Long.parseLong(config.orderTtlMinutes())),
                Integer.parseInt(config.confirmationSamples()),
                config.configFilePath(),
                config.stateFilePath()
        );
    }

    private RebalanceService buildRebalanceService(
            RebalanceSettings settings,
            WorkingOrderRegistry workingOrderRegistry
    ) {
        BybitSdkMarketDataGateway rebalanceMarketDataGateway = new BybitSdkMarketDataGateway(
                clientFactory,
                settings.category(),
                new FourHourIndicatorCalculator(),
                baseProperties.strategyConfig().emaLength4h(),
                baseProperties.strategyConfig().bollLength4h(),
                baseProperties.strategyConfig().bollStdDevMultiplier()
        );
        return new RebalanceService(
                settings,
                rebalanceMarketDataGateway,
                new BybitSdkAccountGateway(clientFactory),
                new BybitSdkTradeGateway(clientFactory, settings.category()),
                workingOrderRegistry,
                new FileRebalanceStateStore(PathResolver.absolutePath(settings.stateFilePath())),
                eventLogger,
                alertService,
                reason -> recordRebalanceCycleEnd(settings.symbol(), reason)
        );
    }

    private List<RebalanceBand> parseRebalanceBands(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<RebalanceBand> bands = new ArrayList<>();
        for (String token : value.split(",")) {
            String[] parts = token.trim().split(":");
            if (parts.length == 2) {
                bands.add(new RebalanceBand(new BigDecimal(parts[0].trim()), new BigDecimal(parts[1].trim())));
            }
        }
        return List.copyOf(bands);
    }

    public synchronized Map<String, Object> saveRebalanceConfig(RebalanceConfigRequest request) {
        RebalanceConfigRequest normalized = (request == null
                ? RebalanceConfigRequest.from(baseProperties.rebalanceSettings())
                : request).normalized();
        RebalanceConfigRequest existing = currentRebalanceConfig();
        RebalanceState runningState = currentRebalanceState();
        if (!RebalanceMode.fromConfig(existing.mode()).equals(RebalanceMode.fromConfig(normalized.mode()))
                && isActiveRebalanceCycleStatus(runningState.status())) {
            return Map.of(
                    "ok", false,
                    "message", "当前再平衡 Cycle 正在运行，请等待本轮结束后再切换模式"
            );
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("rebalance.enabled", String.valueOf(normalized.enabled()));
            properties.setProperty("rebalance.mode", normalized.mode());
            properties.setProperty("rebalance.auto-min-total-btc", normalized.autoMinTotalBtc());
            properties.setProperty("rebalance.auto-base-grid-qty", normalized.autoBaseGridQty());
            properties.setProperty("rebalance.auto-initial-steps", normalized.autoInitialSteps());
            properties.setProperty("rebalance.category", normalized.category());
            properties.setProperty("rebalance.symbol", normalized.symbol());
            properties.setProperty("rebalance.account-type", normalized.accountType());
            properties.setProperty("rebalance.base-coin", normalized.baseCoin());
            properties.setProperty("rebalance.quote-coin", normalized.quoteCoin());
            properties.setProperty("rebalance.price-targets", normalized.priceTargets());
            properties.setProperty("rebalance.ratio-threshold", normalized.ratioThreshold());
            properties.setProperty("rebalance.base-grid-spacing", normalized.baseGridSpacing());
            properties.setProperty("rebalance.grid-requote-shrink-factor", normalized.gridRequoteShrinkFactor());
            properties.setProperty("rebalance.base-grid-qty", normalized.baseGridQty());
            properties.setProperty("rebalance.max-initial-steps", normalized.maxInitialSteps());
            properties.setProperty("rebalance.max-order-qty", normalized.maxOrderQty());
            properties.setProperty("rebalance.max-inventory-qty", normalized.maxInventoryQty());
            properties.setProperty("rebalance.price-offset-ratio", normalized.priceOffsetRatio());
            properties.setProperty("rebalance.band-hysteresis-ratio", normalized.bandHysteresisRatio());
            properties.setProperty("rebalance.order-ttl-minutes", normalized.orderTtlMinutes());
            properties.setProperty("rebalance.confirmation-samples", normalized.confirmationSamples());
            Path path = PathResolver.absolutePath(baseProperties.rebalanceSettings().configFilePath());
            Files.createDirectories(path.getParent());
            try (java.io.OutputStream outputStream = Files.newOutputStream(path)) {
                properties.store(outputStream, "btc_grid runtime rebalance config");
            }
            return Map.of(
                    "ok", true,
                    "config", normalized,
                    "path", path.toString(),
                    "message", applyRebalanceRuntime(normalized)
            );
        } catch (RuntimeException | java.io.IOException e) {
            return Map.of("ok", false, "message", e.getMessage() == null ? "保存失败" : e.getMessage());
        }
    }

    private boolean isActiveRebalanceCycleStatus(String status) {
        return "GRID_OPENING".equals(status) || "GRID_ACTIVE".equals(status);
    }

    public synchronized Map<String, Object> startRebalance() {
        RebalanceSettings settings = currentRebalanceSettings();
        if (!settings.enabled()) {
            return Map.of("ok", false, "message", "请先勾选并保存启用再平衡");
        }
        startRebalanceRunner(settings);
        return Map.of("ok", true, "message", "再平衡监控已启动");
    }

    public synchronized Map<String, Object> stopRebalance() {
        stopRebalanceRunner();
        markRebalanceStoppedByUser();
        return Map.of("ok", true, "message", "再平衡监控已停止，状态已标记为手动停止");
    }

    private void markRebalanceStoppedByUser() {
        RebalanceSettings settings = currentRebalanceSettings();
        FileRebalanceStateStore stateStore = new FileRebalanceStateStore(PathResolver.absolutePath(settings.stateFilePath()));
        RebalanceState previous = stateStore.load().orElse(RebalanceState.initial(settings.enabled(), settings.symbol()));
        stateStore.save(new RebalanceState(
                settings.enabled(),
                settings.symbol(),
                "STOPPED_BY_USER",
                previous.targetRatio(),
                previous.currentRatio(),
                previous.baseBalance(),
                previous.contractPositionQty(),
                previous.netBaseQty(),
                previous.totalEquityUsdt(),
                previous.lastPrice(),
                previous.activeSide(),
                previous.activeClientOrderId(),
                previous.activeOrderCreatedAt(),
                previous.activeOrder(),
                previous.ratioSamples(),
                Instant.now(),
                "user stopped rebalance monitor"
        ));
    }

    private String applyRebalanceRuntime(RebalanceConfigRequest config) {
        if (config.enabled()) {
            stopRebalanceRunner();
            startRebalanceRunner(currentRebalanceSettings());
            return "再平衡配置已保存，监控已启动";
        }
        stopRebalanceRunner();
        return "再平衡配置已保存，监控已停止";
    }

    private void startRebalanceRunner(RebalanceSettings settings) {
        if (activeRebalanceRunner != null && activeRebalanceRunner.isRunning()) {
            return;
        }
        if (baseProperties.bybitSdkConfig().apiKey().isBlank() || baseProperties.bybitSdkConfig().apiSecret().isBlank()) {
            eventLogger.warn("rebalance.live.start.skipped", Map.of("reason", "missing bybit api key or secret"));
            return;
        }
        recoverRebalanceOpenOrders(settings);
        RebalanceService rebalanceService = buildRebalanceService(settings, rebalanceWorkingOrderRegistry);
        List<WorkingOrder> activeOpenOrders = rebalanceOrders().stream()
                .filter(order -> order.status() == OrderStatus.NEW)
                .toList();
        RebalanceState persistedState = new FileRebalanceStateStore(PathResolver.absolutePath(settings.stateFilePath()))
                .load()
                .orElseGet(() -> RebalanceState.initial(settings.enabled(), settings.symbol()));
        if (isActiveRebalanceCycleStatus(persistedState.status()) || !activeOpenOrders.isEmpty()) {
            rebalanceService.restoreActiveCycle(
                    latestRebalanceCycleFills(settings.symbol()),
                    activeOpenOrders
            );
        }
        RebalanceLiveRunner runner = new RebalanceLiveRunner(
                rebalanceService,
                new BybitSdkPrivateStreamGateway(baseProperties.bybitSdkConfig()),
                new BybitSdkOrderQueryGateway(clientFactory, settings.category()),
                new BybitSdkFundingFeeQueryGateway(clientFactory, settings.category()),
                rebalanceWorkingOrderRegistry,
                eventLogger,
                alertService,
                baseProperties.runtimeSettings().marketPollInterval(),
                this::recordRebalanceFill,
                this::recordRebalanceFundingFee
        );
        activeRebalanceRunner = runner;
        try {
            runner.start();
        } catch (RuntimeException e) {
            activeRebalanceRunner = null;
            try {
                runner.close();
            } catch (RuntimeException closeException) {
                e.addSuppressed(closeException);
            }
            eventLogger.error("rebalance.live.start.failed", Map.of("message", e.getMessage()));
            alertService.warning("REBALANCE_LIVE_START_FAILED", "Rebalance live start failed", Map.of("message", e.getMessage()));
            throw e;
        }
    }

    private void recoverRebalanceOpenOrders(RebalanceSettings settings) {
        try {
            List<WorkingOrder> openOrders = new BybitSdkOrderQueryGateway(clientFactory, settings.category())
                    .fetchOpenOrders(settings.symbol())
                    .stream()
                    .filter(order -> RebalanceService.isRebalanceClientOrder(order.clientOrderId()))
                    .toList();
            for (WorkingOrder order : openOrders) {
                rebalanceWorkingOrderRegistry.trackRemote(order);
            }
            if (!openOrders.isEmpty()) {
                eventLogger.info("rebalance.open-orders.recovered", Map.of(
                        "symbol", settings.symbol(),
                        "count", openOrders.size()
                ));
            }
        } catch (RuntimeException e) {
            eventLogger.warn("rebalance.open-orders.recover.failed", Map.of(
                    "symbol", settings.symbol(),
                    "message", e.getMessage()
            ));
        }
    }

    private void stopRebalanceRunner() {
        RebalanceLiveRunner runner = activeRebalanceRunner;
        activeRebalanceRunner = null;
        if (runner != null) {
            runner.close();
        }
    }

    private void runFlattenAndStop(GridLiveRunner runner, Instant startedAt) {
        try {
            eventLogger.info("flatten.stop.started", Map.of(
                    "symbol", runner.snapshot().symbol(),
                    "startedAt", startedAt.toString()
            ));
            runner.flattenAndStop();
            FlattenStopStatus completed = FlattenStopStatus.completed(startedAt, Instant.now(), "平仓并停止已完成");
            flattenStopStatus.set(completed);
            eventLogger.info("flatten.stop.completed", Map.of(
                    "symbol", runner.snapshot().symbol(),
                    "startedAt", startedAt.toString(),
                    "finishedAt", completed.finishedAt().toString()
            ));
        } catch (RuntimeException e) {
            FlattenStopStatus failed = FlattenStopStatus.failed(startedAt, Instant.now(), e.getMessage());
            flattenStopStatus.set(failed);
            eventLogger.error("flatten.stop.failed", Map.of(
                    "startedAt", startedAt.toString(),
                    "finishedAt", failed.finishedAt().toString(),
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
            alertService.warning("FLATTEN_STOP_FAILED", "Flatten and stop failed", Map.of(
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
        }
    }

    public List<FillRecord> recentFills() {
        return fillStore.currentCycleFills(snapshot().symbol());
    }

    public List<FillRecord> recentCycles() {
        return fillStore.recentCycles(200);
    }

    public List<FillRecord> cycleFills(String cycleExecutionId) {
        return fillStore.cycleFills(cycleExecutionId);
    }

    private static int normalizePageLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(500, Math.max(1, limit));
    }

    public Map<String, Object> cycleConfig(String cycleExecutionId) {
        Optional<FillRecord> cycleEnd = fillStore.recentCycles(1000).stream()
                .filter(record -> record.executionId() != null && record.executionId().equals(cycleExecutionId))
                .findFirst();
        if (cycleEnd.isEmpty()) {
            return Map.of("ok", false, "message", "cycle not found");
        }
        String configJson = cycleEnd.get().configJson();
        if (configJson != null && !configJson.isBlank()) {
            try {
                return Map.of(
                        "ok", true,
                        "exact", true,
                        "config", OBJECT_MAPPER.readValue(configJson, StartRequest.class)
                );
            } catch (JsonProcessingException e) {
                return Map.of(
                        "ok", false,
                        "message", "failed to read saved cycle config: " + e.getOriginalMessage()
                );
            }
        }
        StartRequest inferred = inferCycleConfig(cycleEnd.get(), fillStore.cycleFills(cycleExecutionId));
        return Map.of(
                "ok", true,
                "exact", false,
                "config", inferred,
                "message", "该历史 Cycle 没有保存完整参数，已从成交记录尽量回填；请检查后再启动"
        );
    }

    public CurrentCycleSummary currentCycle() {
        RuntimeStatusSnapshot snapshot = snapshot();
        BigDecimal markPrice = snapshot.latestMarketSnapshot() == null
                ? null
                : snapshot.latestMarketSnapshot().markPrice();
        return fillStore.currentCycle(snapshot.symbol(), Instant.now(), markPrice);
    }

    void recordFill(FillEvent fillEvent) {
        RuntimeStatusSnapshot runtimeSnapshot = currentRuntimeSnapshot();
        if (fillEvent.symbol() == null || !fillEvent.symbol().equalsIgnoreCase(runtimeSnapshot.symbol())) {
            return;
        }
        StrategyLedgerSnapshot ledgerSnapshot = runtimeSnapshot.ledgerSnapshot();
        boolean startsNewCycle = ClientOrderIds.isInitialPositionOrder(fillEvent.clientOrderId());
        List<FillRecord> currentCycleFills = startsNewCycle ? List.of() : fillStore.currentCycleFills(fillEvent.symbol());
        FillRecord previous = currentCycleFills.isEmpty() ? null : currentCycleFills.get(currentCycleFills.size() - 1);
        BigDecimal value = fillValue(fillEvent);
        BigDecimal fee = fillEvent.fee() == null ? BigDecimal.ZERO : fillEvent.fee();
        BigDecimal cumulativeQty = previous == null ? BigDecimal.ZERO : previous.cumulativeQty();
        BigDecimal cumulativeValue = previous == null ? BigDecimal.ZERO : previous.cumulativeValue();
        BigDecimal cumulativeFee = previous == null ? BigDecimal.ZERO : previous.cumulativeFee();
        BigDecimal netQtyDelta = fillEvent.netQtyDelta();
        if (fillEvent.side() == com.btcgrid.common.model.TradeSide.BUY) {
            cumulativeQty = cumulativeQty.add(netQtyDelta, MATH_CONTEXT);
            cumulativeValue = cumulativeValue.add(value, MATH_CONTEXT);
        } else {
            cumulativeQty = cumulativeQty.add(netQtyDelta, MATH_CONTEXT);
            cumulativeValue = cumulativeValue.subtract(value, MATH_CONTEXT);
        }
        cumulativeFee = cumulativeFee.add(fee, MATH_CONTEXT);
        BigDecimal markPrice = runtimeSnapshot.latestMarketSnapshot() == null
                ? fillEvent.fillPrice()
                : runtimeSnapshot.latestMarketSnapshot().markPrice();
        BigDecimal floatingPnlAtFill = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, fillEvent.fillPrice());
        BigDecimal totalPnlAtFill = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, markPrice);
        String fillMetadataJson = profitReduceBoostMetadataJson(fillEvent, currentCycleFills, runtimeSnapshot);
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
                fillEvent.executionId(),
                fillMetadataJson
        );
        fillStore.save(record);
        if (isCycleEnd(runtimeSnapshot)) {
            fillStore.save(cycleEndRecord(fillEvent, ledgerSnapshot, totalPnlAtFill, cumulativeQty, cumulativeValue, cumulativeFee));
        }
    }

    void recordFundingFee(FundingFeeEvent fundingFeeEvent) {
        RuntimeStatusSnapshot runtimeSnapshot = currentRuntimeSnapshot();
        if (fundingFeeEvent.symbol() == null || !fundingFeeEvent.symbol().equalsIgnoreCase(runtimeSnapshot.symbol())) {
            return;
        }
        StrategyLedgerSnapshot ledgerSnapshot = runtimeSnapshot.ledgerSnapshot();
        List<FillRecord> currentCycleFills = fillStore.currentCycleFills(fundingFeeEvent.symbol());
        FillRecord previous = currentCycleFills.isEmpty() ? null : currentCycleFills.get(currentCycleFills.size() - 1);
        BigDecimal cumulativeQty = previous == null ? ledgerSnapshot.netQty() : previous.cumulativeQty();
        BigDecimal cumulativeValue = previous == null ? BigDecimal.ZERO : previous.cumulativeValue();
        BigDecimal cumulativeFee = previous == null ? BigDecimal.ZERO : previous.cumulativeFee();
        BigDecimal fundingFee = fundingFeeEvent.fee() == null ? BigDecimal.ZERO : fundingFeeEvent.fee();
        cumulativeFee = cumulativeFee.subtract(fundingFee, MATH_CONTEXT);
        BigDecimal markPrice = runtimeSnapshot.latestMarketSnapshot() == null
                ? ledgerSnapshot.lastFillPrice()
                : runtimeSnapshot.latestMarketSnapshot().markPrice();
        BigDecimal fundingValue = ledgerSnapshot.netQty().abs().multiply(markPrice == null ? BigDecimal.ZERO : markPrice, MATH_CONTEXT);
        BigDecimal pnlAtFunding = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, markPrice);
        String eventTime = String.valueOf(toEpochMillis(fundingFeeEvent.time()));
        fillStore.save(new FillRecord(
                fundingFeeEvent.time(),
                fundingFeeEvent.symbol(),
                "FUNDING_FEE",
                ledgerSnapshot.netQty().abs(),
                markPrice,
                fundingValue,
                fundingFee,
                fundingFeeEvent.feeCurrency(),
                cumulativeQty,
                cumulativeValue,
                cumulativeFee,
                ledgerSnapshot.netQty(),
                ledgerSnapshot.avgEntryPrice(),
                ledgerSnapshot.realizedPnl(),
                pnlAtFunding,
                pnlAtFunding,
                "FUNDING_FEE_" + fundingFeeEvent.symbol() + "_" + eventTime,
                fundingFeeEvent.executionId()
        ));
    }

    private void recordRebalanceFill(FillEvent fillEvent) {
        if (fillEvent == null || fillEvent.symbol() == null || !RebalanceService.isRebalanceClientOrder(fillEvent.clientOrderId())) {
            return;
        }
        String ledgerSymbol = rebalanceLedgerSymbol(fillEvent.symbol());
        List<FillRecord> currentCycleFills = fillStore.currentCycleFills(ledgerSymbol);
        FillRecord previous = currentCycleFills.isEmpty() ? null : currentCycleFills.get(currentCycleFills.size() - 1);
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
        BigDecimal totalPnlAtFill = cumulativeProfit(cumulativeQty, cumulativeValue, cumulativeFee, fillEvent.fillPrice());
        String configJson = previous == null ? rebalanceStartMetadataJson() : null;
        fillStore.save(new FillRecord(
                fillEvent.fillTime(),
                ledgerSymbol,
                fillEvent.side().name(),
                fillEvent.fillQty(),
                fillEvent.fillPrice(),
                value,
                fee,
                fillEvent.feeCurrency(),
                cumulativeQty,
                cumulativeValue,
                cumulativeFee,
                cumulativeQty,
                averagePrice(cumulativeQty, cumulativeValue),
                BigDecimal.ZERO,
                totalPnlAtFill,
                totalPnlAtFill,
                fillEvent.clientOrderId(),
                fillEvent.executionId(),
                configJson
        ));
    }

    private String rebalanceStartMetadataJson() {
        RebalanceState state = currentRebalanceState();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("startRatio", state.currentRatio());
        meta.put("targetRatio", state.targetRatio());
        try {
            return OBJECT_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void recordRebalanceFundingFee(FundingFeeEvent fundingFeeEvent) {
        if (fundingFeeEvent == null || fundingFeeEvent.symbol() == null) {
            return;
        }
        String ledgerSymbol = rebalanceLedgerSymbol(fundingFeeEvent.symbol());
        List<FillRecord> currentCycleFills = fillStore.currentCycleFills(ledgerSymbol);
        if (currentCycleFills.isEmpty()) {
            return;
        }
        FillRecord previous = currentCycleFills.get(currentCycleFills.size() - 1);
        BigDecimal fundingFee = fundingFeeEvent.fee() == null ? BigDecimal.ZERO : fundingFeeEvent.fee();
        BigDecimal cumulativeFee = previous.cumulativeFee().subtract(fundingFee, MATH_CONTEXT);
        BigDecimal price = previous.price();
        BigDecimal pnl = cumulativeProfit(previous.cumulativeQty(), previous.cumulativeValue(), cumulativeFee, price);
        String eventTime = String.valueOf(toEpochMillis(fundingFeeEvent.time()));
        fillStore.save(new FillRecord(
                fundingFeeEvent.time(),
                ledgerSymbol,
                "FUNDING_FEE",
                previous.cumulativeQty().abs(),
                price,
                previous.cumulativeQty().abs().multiply(price == null ? BigDecimal.ZERO : price, MATH_CONTEXT),
                fundingFee,
                fundingFeeEvent.feeCurrency(),
                previous.cumulativeQty(),
                previous.cumulativeValue(),
                cumulativeFee,
                previous.netQty(),
                previous.avgEntryPrice(),
                previous.realizedPnl(),
                pnl,
                pnl,
                "FUNDING_FEE_" + ledgerSymbol + "_" + eventTime,
                fundingFeeEvent.executionId()
        ));
    }

    private void recordRebalanceCycleEnd(String symbol, String reason) {
        String ledgerSymbol = rebalanceLedgerSymbol(symbol);
        List<FillRecord> fills = fillStore.currentCycleFills(ledgerSymbol);
        if (fills.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        FillRecord first = fills.get(0);
        FillRecord last = fills.get(fills.size() - 1);
        String executionId = "CYCLE_END_" + last.clientOrderId() + "_" + now.toEpochMilli();
        BigDecimal price = last.price() == null ? BigDecimal.ZERO : last.price();
        BigDecimal totalPnl = cumulativeProfit(last.cumulativeQty(), last.cumulativeValue(), last.cumulativeFee(), price);
        RebalanceState endingState = currentRebalanceState();
        String configJson = rebalanceCycleMetadataJson(fills, now, reason, endingState);
        fillStore.save(new FillRecord(
                now,
                ledgerSymbol,
                "CYCLE_END",
                BigDecimal.ZERO,
                price,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "USDT",
                last.cumulativeQty(),
                last.cumulativeValue(),
                last.cumulativeFee(),
                last.netQty(),
                last.avgEntryPrice(),
                last.realizedPnl(),
                BigDecimal.ZERO,
                totalPnl,
                "CYCLE_END_" + last.clientOrderId(),
                executionId,
                configJson
        ));
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("symbol", symbol);
        eventPayload.put("ledgerSymbol", ledgerSymbol);
        eventPayload.put("reason", reason == null ? "" : reason);
        eventPayload.put("executionId", executionId);
        eventPayload.put("totalPnl", totalPnl);
        eventPayload.put("startPrice", first.price());
        eventPayload.put("startRatio", metadataValue(first, "startRatio"));
        eventPayload.put("entryDirection", rebalanceEntryDirection(first));
        eventPayload.put("durationSeconds", Duration.between(first.time(), now).toSeconds());
        eventPayload.put("buyCount", countSide(fills, "BUY"));
        eventPayload.put("sellCount", countSide(fills, "SELL"));
        eventPayload.put("targetRatio", endingState.targetRatio());
        eventPayload.put("endRatio", endingState.currentRatio());
        eventLogger.info("rebalance.cycle.end", eventPayload);
    }

    private Map<String, Object> rebalanceCycleRow(FillRecord cycleEnd, String loggedReason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", cycleEnd.time());
        row.put("symbol", cycleEnd.symbol());
        row.put("side", cycleEnd.side());
        row.put("qty", cycleEnd.qty());
        row.put("price", cycleEnd.price());
        row.put("totalPnlAtFill", cycleEnd.totalPnlAtFill());
        row.put("clientOrderId", cycleEnd.clientOrderId());
        row.put("executionId", cycleEnd.executionId());
        row.put("targetRatio", null);
        row.put("startRatio", null);
        row.put("startPrice", null);
        row.put("entryDirection", null);
        row.put("durationSeconds", null);
        row.put("buyCount", 0);
        row.put("sellCount", 0);
        row.put("fillCount", 0);
        row.put("endReason", loggedReason == null ? "" : loggedReason);
        row.put("endReasonLabel", endReasonLabel(loggedReason));
        if (cycleEnd.configJson() != null && !cycleEnd.configJson().isBlank()) {
            try {
                Map<String, Object> meta = OBJECT_MAPPER.readValue(cycleEnd.configJson(), Map.class);
                row.putAll(meta);
                Object reason = meta.get("endReason");
                if (reason != null && !String.valueOf(reason).isBlank()) {
                    row.put("endReasonLabel", endReasonLabel(String.valueOf(reason)));
                }
            } catch (Exception ignored) {
                // Fall back to derived fields below.
            }
        }
        List<FillRecord> fills = fillStore.cycleFills(cycleEnd.executionId());
        if (!fills.isEmpty()) {
            FillRecord first = fills.get(0);
            row.putIfAbsent("startPrice", first.price());
            if (row.get("startPrice") == null) {
                row.put("startPrice", first.price());
            }
            if (row.get("entryDirection") == null) {
                row.put("entryDirection", rebalanceEntryDirection(first));
            }
            if (row.get("durationSeconds") == null && first.time() != null && cycleEnd.time() != null) {
                row.put("durationSeconds", Duration.between(first.time(), cycleEnd.time()).toSeconds());
            }
            if (row.get("startRatio") == null) {
                row.put("startRatio", metadataValue(first, "startRatio"));
            }
            row.put("buyCount", countSide(fills, "BUY"));
            row.put("sellCount", countSide(fills, "SELL"));
            row.put("fillCount", tradingFillCount(fills));
            row.put("endReasonLabel", derivedEndReasonLabel(row, fills));
        }
        return row;
    }

    private Map<String, String> rebalanceCycleEndReasons() {
        Path path = PathResolver.absolutePath(baseProperties.runtimeSettings().logFilePath());
        if (!Files.exists(path)) {
            return Map.of();
        }
        Map<String, String> reasons = new HashMap<>();
        try (var lines = Files.lines(path)) {
            lines.forEach(line -> {
                try {
                    Map<String, Object> event = OBJECT_MAPPER.readValue(line, Map.class);
                    if (!"rebalance.cycle.end".equals(event.get("event"))) {
                        return;
                    }
                    Object payloadObject = event.get("payload");
                    if (!(payloadObject instanceof Map<?, ?> payload)) {
                        return;
                    }
                    Object executionId = payload.get("executionId");
                    Object reason = payload.get("reason");
                    if (executionId != null && reason != null) {
                        reasons.put(String.valueOf(executionId), String.valueOf(reason));
                    }
                } catch (Exception ignored) {
                    // Ignore unrelated or malformed log lines.
                }
            });
        } catch (Exception ignored) {
            return Map.of();
        }
        return reasons;
    }

    private String rebalanceCycleMetadataJson(List<FillRecord> fills, Instant endedAt, String reason, RebalanceState endingState) {
        FillRecord first = fills.get(0);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("startPrice", first.price());
        meta.put("startRatio", metadataValue(first, "startRatio"));
        meta.put("entryDirection", rebalanceEntryDirection(first));
        meta.put("durationSeconds", first.time() == null ? 0 : Duration.between(first.time(), endedAt).toSeconds());
        meta.put("endReason", reason == null ? "" : reason);
        meta.put("endReasonLabel", endReasonLabel(reason));
        meta.put("targetRatio", endingState.targetRatio());
        meta.put("endRatio", endingState.currentRatio());
        meta.put("buyCount", countSide(fills, "BUY"));
        meta.put("sellCount", countSide(fills, "SELL"));
        meta.put("fillCount", tradingFillCount(fills));
        try {
            return OBJECT_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Object metadataValue(FillRecord fill, String key) {
        if (fill == null || fill.configJson() == null || fill.configJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> meta = OBJECT_MAPPER.readValue(fill.configJson(), Map.class);
            return meta.get(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long countSide(List<FillRecord> fills, String side) {
        return fills.stream()
                .filter(fill -> fill != null && side.equals(fill.side()))
                .count();
    }

    private long tradingFillCount(List<FillRecord> fills) {
        return fills.stream()
                .filter(fill -> fill != null && ("BUY".equals(fill.side()) || "SELL".equals(fill.side())))
                .count();
    }

    private static String rebalanceEntryDirection(FillRecord first) {
        if (first == null) {
            return "";
        }
        if ("BUY".equals(first.side())) {
            return "LONG";
        }
        if ("SELL".equals(first.side())) {
            return "SHORT";
        }
        return "";
    }

    private static String endReasonLabel(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        if (reason.contains("target ratio reached")) {
            return "达到比例";
        }
        if (reason.contains("position crossed zero")) {
            return "穿过0";
        }
        if (reason.contains("position is flat")) {
            return "持仓为0";
        }
        return reason;
    }

    private static String derivedEndReasonLabel(Map<String, Object> row, List<FillRecord> fills) {
        String reason = string(row.get("endReason"));
        String fallback = endReasonLabel(reason);
        if (!reason.contains("position is flat") || fills == null || fills.isEmpty()) {
            return fallback;
        }
        FillRecord last = fills.get(fills.size() - 1);
        if (last == null || last.cumulativeQty() == null || last.cumulativeQty().signum() == 0) {
            return fallback;
        }
        String entryDirection = string(row.get("entryDirection"));
        if (("SHORT".equals(entryDirection) && last.cumulativeQty().signum() > 0)
                || ("LONG".equals(entryDirection) && last.cumulativeQty().signum() < 0)) {
            return "穿过0";
        }
        return fallback;
    }

    private List<FillRecord> latestRebalanceCycleFills(String symbol) {
        String ledgerSymbol = rebalanceLedgerSymbol(symbol);
        List<FillRecord> recent = fillStore.recent(ledgerSymbol, 1000).stream()
                .filter(fill -> !"CYCLE_END".equals(fill.side()))
                .toList();
        if (recent.isEmpty()) {
            return List.of();
        }
        List<FillRecord> newestFirst = recent;
        List<FillRecord> cycle = new ArrayList<>();
        for (FillRecord fill : newestFirst) {
            cycle.add(fill);
            if (ClientOrderIds.isInitialPositionOrder(fill.clientOrderId())) {
                break;
            }
        }
        if (cycle.isEmpty() || !ClientOrderIds.isInitialPositionOrder(cycle.get(cycle.size() - 1).clientOrderId())) {
            return List.of();
        }
        java.util.Collections.reverse(cycle);
        return cycle;
    }

    private static String rebalanceLedgerSymbol(String symbol) {
        return "RBAL:" + (symbol == null ? "" : symbol.trim().toUpperCase());
    }

    private static String displaySymbol(String symbol) {
        return symbol != null && symbol.startsWith("RBAL:") ? symbol.substring("RBAL:".length()) : symbol;
    }

    private static BigDecimal averagePrice(BigDecimal qty, BigDecimal value) {
        if (qty == null || qty.signum() == 0 || value == null) {
            return BigDecimal.ZERO;
        }
        return value.divide(qty, MATH_CONTEXT).abs();
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
                executionId,
                configJson(lastConfig)
        );
    }

    private StartRequest inferCycleConfig(FillRecord cycleEnd, List<FillRecord> fills) {
        StartRequest defaults = StartRequest.fromConfig(baseProperties.strategyConfig());
        BigDecimal baseQty = mostCommonQty(fills).orElse(defaults.baseGridQty());
        BigDecimal maxInventoryQty = fills.stream()
                .map(FillRecord::cumulativeQty)
                .filter(value -> value != null)
                .map(BigDecimal::abs)
                .max(Comparator.naturalOrder())
                .orElse(defaults.maxInventoryQty());
        BigDecimal maxInitialSteps = fills.stream()
                .filter(fill -> ClientOrderIds.isInitialPositionOrder(fill.clientOrderId()))
                .findFirst()
                .map(fill -> baseQty.signum() == 0 ? defaults.maxInitialSteps() : fill.qty().divide(baseQty, MATH_CONTEXT))
                .orElse(defaults.maxInitialSteps());
        String entryDirection = fills.stream()
                .filter(fill -> "BUY".equals(fill.side()) || "SELL".equals(fill.side()))
                .findFirst()
                .map(fill -> "BUY".equals(fill.side()) ? "long" : "short")
                .orElse(defaults.entryDirection());
        return new StartRequest(
                defaults.category(),
                cycleEnd.symbol(),
                entryDirection,
                defaults.baseGridSpacing(),
                defaults.gridRequoteShrinkFactor(),
                baseQty,
                maxInitialSteps,
                baseQty,
                maxInventoryQty.max(baseQty),
                maxInventoryQty.max(baseQty),
                defaults.emaLength4h(),
                defaults.bollLength4h(),
                defaults.bollStdDevMultiplier(),
                defaults.pairOrderTimeoutMinutes(),
                defaults.triggerCooldownMinutes()
        );
    }

    private Optional<BigDecimal> mostCommonQty(List<FillRecord> fills) {
        return fills.stream()
                .filter(fill -> !"CYCLE_END".equals(fill.side()))
                .map(FillRecord::qty)
                .filter(qty -> qty != null && qty.signum() > 0)
                .collect(java.util.stream.Collectors.groupingBy(
                        qty -> qty.stripTrailingZeros().toPlainString(),
                        java.util.stream.Collectors.counting()
                ))
                .entrySet()
                .stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new BigDecimal(entry.getKey()));
    }

    private String configJson(GridStrategyConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(StartRequest.fromConfig(config));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize cycle config", e);
        }
    }

    private String profitReduceBoostMetadataJson(
            FillEvent fillEvent,
            List<FillRecord> currentCycleFills,
            RuntimeStatusSnapshot runtimeSnapshot
    ) {
        BigDecimal previousCumulative = latestProfitReduceBoostCumulative(currentCycleFills);
        ProfitReduceBoost boost = profitReduceBoost(fillEvent, runtimeSnapshot);
        BigDecimal extraQty = boost == null ? BigDecimal.ZERO : boost.extraFillQty(fillEvent.fillQty());
        BigDecimal cumulative = previousCumulative.add(extraQty, MATH_CONTEXT);
        if (extraQty.signum() <= 0 && cumulative.signum() <= 0) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("profitReduceBoostExtraQty", extraQty);
        meta.put("cumulativeProfitReduceBoostQty", cumulative);
        if (boost != null) {
            meta.put("profitReduceBoostBaseQty", boost.baseQty());
            meta.put("profitReduceBoostOrderQty", boost.boostedQty());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private BigDecimal latestProfitReduceBoostCumulative(List<FillRecord> fills) {
        if (fills == null || fills.isEmpty()) {
            return BigDecimal.ZERO;
        }
        for (int i = fills.size() - 1; i >= 0; i--) {
            Object value = metadataValue(fills.get(i), "cumulativeProfitReduceBoostQty");
            BigDecimal cumulative = value == null ? null : decimalOrNull(String.valueOf(value));
            if (cumulative != null) {
                return cumulative;
            }
        }
        return BigDecimal.ZERO;
    }

    private ProfitReduceBoost profitReduceBoost(FillEvent fillEvent, RuntimeStatusSnapshot runtimeSnapshot) {
        if (fillEvent == null || runtimeSnapshot == null || runtimeSnapshot.workingOrders() == null) {
            return null;
        }
        Optional<ProfitReduceBoost> currentOrderBoost = runtimeSnapshot.workingOrders().stream()
                .filter(order -> order != null && fillEvent.clientOrderId() != null
                        && fillEvent.clientOrderId().equals(order.clientOrderId()))
                .map(order -> parseProfitReduceBoost(order.reason()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (currentOrderBoost.isPresent()) {
            return currentOrderBoost.get();
        }
        return commandPlaceReason(fillEvent.clientOrderId())
                .flatMap(this::parseProfitReduceBoost)
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Optional<String> commandPlaceReason(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return Optional.empty();
        }
        String cached = orderReasonCache.get(clientOrderId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path path = PathResolver.absolutePath(baseProperties.runtimeSettings().logFilePath());
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        String reason = null;
        try (var lines = Files.lines(path)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line == null || !line.contains(clientOrderId) || !line.contains("command.place")) {
                    continue;
                }
                Map<String, Object> event = OBJECT_MAPPER.readValue(line, Map.class);
                if (!"command.place".equals(string(event.get("event")))) {
                    continue;
                }
                Object payloadValue = event.get("payload");
                if (!(payloadValue instanceof Map<?, ?> rawPayload)) {
                    continue;
                }
                Map<String, Object> payload = (Map<String, Object>) rawPayload;
                if (clientOrderId.equals(string(payload.get("clientOrderId")))) {
                    reason = string(payload.get("reason"));
                }
            }
        } catch (RuntimeException | java.io.IOException e) {
            eventLogger.warn("order.reason.lookup.failed", Map.of(
                    "clientOrderId", clientOrderId,
                    "path", path.toString(),
                    "message", e.getMessage() == null ? "" : e.getMessage()
            ));
            return Optional.empty();
        }
        if (reason == null || reason.isBlank()) {
            return Optional.empty();
        }
        orderReasonCache.put(clientOrderId, reason);
        return Optional.of(reason);
    }

    private Optional<ProfitReduceBoost> parseProfitReduceBoost(String reason) {
        if (reason == null || !reason.contains("profit-reduce-boost:")) {
            return Optional.empty();
        }
        String payload = reason.substring(reason.indexOf("profit-reduce-boost:") + "profit-reduce-boost:".length());
        int end = payload.indexOf('|');
        if (end >= 0) {
            payload = payload.substring(0, end);
        }
        BigDecimal baseQty = null;
        BigDecimal boostedQty = null;
        for (String token : payload.split(",")) {
            String[] parts = token.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            if ("base".equals(parts[0])) {
                baseQty = decimalOrNull(parts[1]);
            } else if ("boosted".equals(parts[0])) {
                boostedQty = decimalOrNull(parts[1]);
            }
        }
        if (baseQty == null || boostedQty == null || boostedQty.compareTo(baseQty) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ProfitReduceBoost(baseQty, boostedQty));
    }

    private BigDecimal metadataDecimal(FillRecord record, String key) {
        Object value = record == null ? null : metadataValue(record, key);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal decimal = decimalOrNull(String.valueOf(value));
        return decimal == null ? BigDecimal.ZERO : decimal;
    }

    private BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record ProfitReduceBoost(BigDecimal baseQty, BigDecimal boostedQty) {
        private BigDecimal extraFillQty(BigDecimal fillQty) {
            if (fillQty == null || fillQty.signum() <= 0 || boostedQty.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal extraOrderQty = boostedQty.subtract(baseQty).max(BigDecimal.ZERO);
            if (extraOrderQty.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal fillRatio = fillQty.divide(boostedQty, MathContext.DECIMAL64).min(BigDecimal.ONE);
            return extraOrderQty.multiply(fillRatio, MathContext.DECIMAL64);
        }
    }

    private BigDecimal fillValue(FillEvent fillEvent) {
        if (fillEvent.fillValue() != null && fillEvent.fillValue().signum() != 0) {
            return fillEvent.fillValue();
        }
        return fillEvent.fillQty().multiply(fillEvent.fillPrice(), MATH_CONTEXT);
    }

    private static String quoteCoin(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String upper = symbol.trim().toUpperCase();
        for (String quote : List.of("USDT", "USDC", "USD", "BTC", "ETH")) {
            if (upper.endsWith(quote) && upper.length() > quote.length()) {
                return quote;
            }
        }
        return null;
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

    private long toEpochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
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

    public record PageResult<T>(List<T> items, int limit, int offset, boolean hasMore) {
    }

    public record RebalanceConfigRequest(
            boolean enabled,
            String mode,
            String autoMinTotalBtc,
            String autoBaseGridQty,
            String autoInitialSteps,
            String category,
            String symbol,
            String accountType,
            String baseCoin,
            String quoteCoin,
            String priceTargets,
            String ratioThreshold,
            String baseGridSpacing,
            String gridRequoteShrinkFactor,
            String baseGridQty,
            String maxInitialSteps,
            String maxOrderQty,
            String maxInventoryQty,
            String priceOffsetRatio,
            String bandHysteresisRatio,
            String orderTtlMinutes,
            String confirmationSamples,
            String configFilePath,
            String stateFilePath
    ) {
        public static RebalanceConfigRequest from(RebalanceSettings settings) {
            return new RebalanceConfigRequest(
                    settings.enabled(),
                    settings.mode().configValue(),
                    settings.autoMinTotalBtc().stripTrailingZeros().toPlainString(),
                    settings.autoBaseGridQty().stripTrailingZeros().toPlainString(),
                    settings.autoInitialSteps().stripTrailingZeros().toPlainString(),
                    settings.category().name().toLowerCase(),
                    settings.symbol(),
                    settings.accountType(),
                    settings.baseCoin(),
                    settings.quoteCoin(),
                    formatBands(settings.bands()),
                    settings.ratioThreshold().stripTrailingZeros().toPlainString(),
                    settings.baseGridSpacing().stripTrailingZeros().toPlainString(),
                    settings.gridRequoteShrinkFactor().stripTrailingZeros().toPlainString(),
                    settings.baseGridQty().stripTrailingZeros().toPlainString(),
                    settings.maxInitialSteps().stripTrailingZeros().toPlainString(),
                    settings.maxOrderQty().stripTrailingZeros().toPlainString(),
                    settings.maxInventoryQty().stripTrailingZeros().toPlainString(),
                    settings.priceOffsetRatio().stripTrailingZeros().toPlainString(),
                    settings.bandHysteresisRatio().stripTrailingZeros().toPlainString(),
                    String.valueOf(settings.orderTtl().toMinutes()),
                    String.valueOf(settings.confirmationSamples()),
                    settings.configFilePath(),
                    settings.stateFilePath()
            );
        }

        public RebalanceConfigRequest normalized() {
            RebalanceMode nextMode = RebalanceMode.fromConfig(mode);
            String nextCategory = blankDefault(category, "spot").trim().toLowerCase();
            ProductCategory.fromConfig(nextCategory);
            validateDecimal("autoMinTotalBtc", autoMinTotalBtc, BigDecimal.ZERO, null);
            validateDecimal("autoBaseGridQty", autoBaseGridQty, BigDecimal.ZERO, null);
            validateDecimal("autoInitialSteps", autoInitialSteps, BigDecimal.ZERO, null);
            validatePriceTargets(priceTargets);
            validateDecimal("ratioThreshold", ratioThreshold, BigDecimal.ZERO, BigDecimal.ONE);
            validateDecimal("baseGridSpacing", baseGridSpacing, BigDecimal.ZERO, null);
            validateDecimal("gridRequoteShrinkFactor", gridRequoteShrinkFactor, BigDecimal.ZERO, BigDecimal.ONE);
            validateDecimal("baseGridQty", baseGridQty, BigDecimal.ZERO, null);
            validateDecimal("maxInitialSteps", maxInitialSteps, BigDecimal.ZERO, null);
            validateDecimal("maxOrderQty", maxOrderQty, BigDecimal.ZERO, null);
            validateDecimal("maxInventoryQty", maxInventoryQty, BigDecimal.ZERO, null);
            validateDecimal("priceOffsetRatio", priceOffsetRatio, BigDecimal.ZERO, BigDecimal.ONE);
            validateDecimal("bandHysteresisRatio", bandHysteresisRatio, BigDecimal.ZERO, BigDecimal.ONE);
            validateInteger("orderTtlMinutes", orderTtlMinutes, 0);
            validateInteger("confirmationSamples", confirmationSamples, 1);
            return new RebalanceConfigRequest(
                    enabled,
                    nextMode.configValue(),
                    blankDefault(autoMinTotalBtc, "7").trim(),
                    blankDefault(autoBaseGridQty, "0.005").trim(),
                    blankDefault(autoInitialSteps, "5").trim(),
                    nextCategory,
                    blankDefault(symbol, "BTCUSDT").trim().toUpperCase(),
                    blankDefault(accountType, "UNIFIED").trim().toUpperCase(),
                    blankDefault(baseCoin, "BTC").trim().toUpperCase(),
                    blankDefault(quoteCoin, "USDT").trim().toUpperCase(),
                    blankDefault(priceTargets, "0:0").trim(),
                    blankDefault(ratioThreshold, "0.01").trim(),
                    blankDefault(baseGridSpacing, "0.0016").trim(),
                    blankDefault(gridRequoteShrinkFactor, "0.85").trim(),
                    blankDefault(baseGridQty, "0.001").trim(),
                    blankDefault(maxInitialSteps, "5").trim(),
                    blankDefault(maxOrderQty, "0.001").trim(),
                    blankDefault(maxInventoryQty, "0.15").trim(),
                    blankDefault(priceOffsetRatio, "0.001").trim(),
                    blankDefault(bandHysteresisRatio, "0.001").trim(),
                    blankDefault(orderTtlMinutes, "15").trim(),
                    blankDefault(confirmationSamples, "3").trim(),
                    configFilePath,
                    stateFilePath
            );
        }

        private static String formatBands(List<RebalanceBand> bands) {
            if (bands == null || bands.isEmpty()) {
                return "0:0";
            }
            List<String> tokens = new ArrayList<>();
            for (RebalanceBand band : bands) {
                tokens.add(band.priceFloor().stripTrailingZeros().toPlainString()
                        + ":" + band.targetRatio().stripTrailingZeros().toPlainString());
            }
            return String.join(",", tokens);
        }

        private static void validatePriceTargets(String raw) {
            String value = blankDefault(raw, "0:0");
            for (String token : value.split(",")) {
                String[] parts = token.trim().split(":");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("priceTargets 格式应为 price:ratio，用逗号分隔");
                }
                validateDecimal("price", parts[0], BigDecimal.ZERO, null);
                validateDecimal("ratio", parts[1], BigDecimal.ZERO, BigDecimal.ONE);
            }
        }

        private static String blankDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        private static void validateDecimal(String name, String raw, BigDecimal min, BigDecimal max) {
            BigDecimal value = new BigDecimal(blankDefault(raw, "0").trim());
            if (min != null && value.compareTo(min) < 0) {
                throw new IllegalArgumentException(name + " 不能小于 " + min);
            }
            if (max != null && value.compareTo(max) > 0) {
                throw new IllegalArgumentException(name + " 不能大于 " + max);
            }
        }

        private static void validateInteger(String name, String raw, int min) {
            int value = Integer.parseInt(blankDefault(raw, String.valueOf(min)).trim());
            if (value < min) {
                throw new IllegalArgumentException(name + " 不能小于 " + min);
            }
        }
    }

    public record FlattenStopStatus(
            String state,
            boolean running,
            Instant startedAt,
            Instant finishedAt,
            String message
    ) {
        private static FlattenStopStatus idle() {
            return new FlattenStopStatus("IDLE", false, null, null, "");
        }

        private static FlattenStopStatus running(Instant startedAt, String message) {
            return new FlattenStopStatus("RUNNING", true, startedAt, null, message);
        }

        private static FlattenStopStatus completed(Instant startedAt, Instant finishedAt, String message) {
            return new FlattenStopStatus("COMPLETED", false, startedAt, finishedAt, message);
        }

        private static FlattenStopStatus failed(Instant startedAt, Instant finishedAt, String message) {
            return new FlattenStopStatus(
                    "FAILED",
                    false,
                    startedAt,
                    finishedAt,
                    message == null || message.isBlank() ? "平仓并停止失败" : message
            );
        }
    }

    public record StartRequest(
            String category,
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
        public static StartRequest fromConfig(GridStrategyConfig config) {
            return new StartRequest(
                    config.category().name().toLowerCase(),
                    config.symbol(),
                    directionText(config.entryDirection()),
                    config.baseGridSpacing(),
                    config.gridRequoteShrinkFactor(),
                    config.baseGridQty(),
                    config.maxInitialSteps(),
                    config.maxOrderQty(),
                    config.maxInventoryQty(),
                    config.maxDirectionalInventoryQty(),
                    config.emaLength4h(),
                    config.bollLength4h(),
                    config.bollStdDevMultiplier(),
                    (int) config.pairOrderTimeout().toMinutes(),
                    (int) config.triggerCooldown().toMinutes()
            );
        }

        private GridStrategyConfig toConfig(GridStrategyConfig defaults) {
            return new GridStrategyConfig(
                    categoryOr(category, defaults.category()),
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

        private static ProductCategory categoryOr(String value, ProductCategory fallback) {
            return value == null || value.isBlank() ? fallback : ProductCategory.fromConfig(value);
        }

        private static String directionText(EntryDirection direction) {
            return switch (direction) {
                case SHORT_ONLY -> "short";
                case BOTH -> "both";
                case LONG_ONLY -> "long";
                case SPOT_LONG_ONLY -> "spot_long_only";
            };
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
