package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitSdkAccountGateway;
import com.btcgrid.bybit.adapter.BybitSdkFundingFeeQueryGateway;
import com.btcgrid.bybit.adapter.BybitSdkClientFactory;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitSdkOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitSdkPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkTradeGateway;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GridApplication {

    public static void main(String[] args) {
        GridProperties properties = GridConfigLoader.load("application.properties");
        EventLogger eventLogger = new CompositeEventLogger(List.of(
                new StructuredEventLogger(),
                new FileEventLogger(PathResolver.absolutePath(properties.runtimeSettings().logFilePath()))
        ));
        StateStore stateStore = new FileStateStore(PathResolver.absolutePath(properties.runtimeSettings().stateFilePath()));
        FillStore fillStore = new SqliteFillStore(PathResolver.absolutePath(properties.runtimeSettings().fillsDbPath()));
        GridStrategyConfig config = chooseStartupConfig(properties.strategyConfig(), stateStore, fillStore, eventLogger);
        if (!config.equals(properties.strategyConfig())) {
            properties = new GridProperties(
                    config,
                    properties.rebalanceSettings(),
                    properties.bybitSdkConfig(),
                    properties.runtimeSettings(),
                    properties.rawProperties()
            );
        }
        BybitSdkClientFactory sdkClientFactory = new BybitSdkClientFactory(properties.bybitSdkConfig());
        BybitSdkMarketDataGateway marketDataGateway = new BybitSdkMarketDataGateway(
                sdkClientFactory,
                config.category(),
                new FourHourIndicatorCalculator(),
                config.emaLength4h(),
                config.bollLength4h(),
                config.bollStdDevMultiplier()
        );
        InstrumentSpec instrumentSpec = marketDataGateway.loadInstrumentSpec(config.symbol());
        MarketSnapshot snapshot = marketDataGateway.latestSnapshot(config.symbol());
        StrategyLedger strategyLedger = new InMemoryStrategyLedger(config.symbol());
        BigDecimal profitSizingFeeRate = profitSizingEstimatedFeeRate(properties);
        BigDecimal lossAddTriggerRate = lossAddTriggerRate(properties);
        GridStrategyEngine engine = new GridStrategyEngine(
                config,
                instrumentSpec,
                strategyLedger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(
                        new ClientOrderIdFactory(),
                        profitableReduceSideMultiplier(properties),
                        (market, ledger, orderPrice, orderQty) -> afterEstimatedOrderFee(
                                currentCycleMarketPnl(fillStore, market, orderPrice),
                                orderPrice,
                                orderQty,
                                profitSizingFeeRate
                        ).signum() > 0,
                        (market, ledger, orderPrice, orderQty) -> currentCycleLossRateAfterEstimatedFee(
                                fillStore,
                                market,
                                orderPrice,
                                orderQty,
                                profitSizingFeeRate
                        ).compareTo(lossAddTriggerRate.negate()) < 0,
                        fillStore::profitSizingState
                ),
                true
        );
        AlertService alertService = new AlertService(eventLogger);
        BybitSdkTradeGateway tradeGateway = new BybitSdkTradeGateway(sdkClientFactory, config.category());
        StatusStore statusStore = new FileStatusStore(PathResolver.absolutePath(properties.runtimeSettings().statusFilePath()));
        WorkingOrderRegistry workingOrderRegistry = new WorkingOrderRegistry();
        RecoveryState recoveryState = new RecoveryBootstrap(stateStore, eventLogger).restore(engine, strategyLedger);
        if (recoveryState.trackedWorkingOrders().isEmpty()
                && engine.runtimeState().state() == GridState.INIT
                && strategyLedger.snapshot().netQty().signum() == 0) {
            restoreOpenCycleFromFills(config.symbol(), fillStore, snapshot, engine, strategyLedger, eventLogger);
        }
        workingOrderRegistry.replaceAll(recoveryState.trackedWorkingOrders());
        StartupReconciliationService reconciliationService = new StartupReconciliationService(
                new BybitSdkOrderQueryGateway(sdkClientFactory, config.category()),
                !properties.bybitSdkConfig().apiKey().isBlank() && !properties.bybitSdkConfig().apiSecret().isBlank()
        );
        try {
            workingOrderRegistry.replaceAll(reconciliationService.reconcile(config.symbol(), workingOrderRegistry.snapshot()));
        } catch (RuntimeException e) {
            eventLogger.error("startup.reconcile.failed", java.util.Map.of(
                    "symbol", config.symbol(),
                    "message", e.getMessage()
            ));
            alertService.warning("STARTUP_RECONCILE_FAILED", "Startup order reconciliation failed", java.util.Map.of(
                    "symbol", config.symbol(),
                    "message", e.getMessage()
            ));
        }
        RebalanceService rebalanceService = buildRebalanceService(
                properties,
                sdkClientFactory,
                workingOrderRegistry,
                eventLogger,
                alertService
        );
        repairFilledWorkingOrders(config.symbol(), fillStore, workingOrderRegistry);
        RuntimeConsistencyResult consistencyResult = new ActivePairConsistencyService()
                .reconcile(engine.runtimeState(), workingOrderRegistry.snapshot());
        if (consistencyResult.corrected()) {
            engine.restoreRuntimeState(consistencyResult.runtimeState());
            eventLogger.warn("runtime.corrected", java.util.Map.of(
                    "state", engine.runtimeState().state().name(),
                    "trackedOrders", workingOrderRegistry.snapshot().size()
            ));
            alertService.warning("RUNTIME_CORRECTED", "Runtime state corrected during startup reconciliation", java.util.Map.of(
                    "state", engine.runtimeState().state().name(),
                    "trackedOrders", workingOrderRegistry.snapshot().size()
            ));
        }
        Set<String> processedExecutionIds = recoveryState.processedExecutionIds();
        stateStore.save(new StrategyStateSnapshot(
                strategyLedger.snapshot(),
                engine.runtimeState(),
                engine.activeSpacing(),
                Set.copyOf(processedExecutionIds),
                List.copyOf(workingOrderRegistry.snapshot()),
                config
        ));
        eventLogger.info("startup.ready", java.util.Map.of(
                "symbol", config.symbol(),
                "state", engine.runtimeState().state().name(),
                "trackedOrders", workingOrderRegistry.snapshot().size(),
                "stateFile", properties.runtimeSettings().stateFilePath(),
                "logFile", properties.runtimeSettings().logFilePath(),
                "statusFile", properties.runtimeSettings().statusFilePath(),
                "httpEnabled", properties.runtimeSettings().httpEnabled(),
                "httpHost", properties.runtimeSettings().httpHost(),
                "httpPort", properties.runtimeSettings().httpPort()
        ));
        AtomicReference<MarketSnapshot> latestSnapshotRef = new AtomicReference<>(snapshot);
        RuntimeStatusProvider runtimeStatusProvider = new RuntimeStatusProvider(
                config.symbol(),
                engine,
                strategyLedger,
                workingOrderRegistry,
                latestSnapshotRef,
                processedExecutionIds
        );
        LiveControlService liveControlService = new LiveControlService(
                properties,
                sdkClientFactory,
                marketDataGateway,
                engine,
                strategyLedger,
                processedExecutionIds,
                runtimeStatusProvider,
                eventLogger,
                alertService,
                stateStore,
                statusStore,
                workingOrderRegistry,
                fillStore
        );
        new RuntimeStatusPublisher(
                config.symbol(),
                engine,
                strategyLedger,
                workingOrderRegistry,
                statusStore,
                latestSnapshotRef,
                processedExecutionIds
        ).publish();
        HttpStatusServer httpStatusServer = null;
        if (properties.runtimeSettings().httpEnabled()) {
            httpStatusServer = new HttpStatusServer(
                    properties.runtimeSettings().httpHost(),
                    properties.runtimeSettings().httpPort(),
                    liveControlService::snapshot,
                    liveControlService,
                    eventLogger
            );
            httpStatusServer.start();
        }
        final HttpStatusServer shutdownHttpServer = httpStatusServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (shutdownHttpServer != null) {
                shutdownHttpServer.close();
            }
            eventLogger.info("shutdown.hook", java.util.Map.of("symbol", config.symbol()));
        }, "btc-grid-shutdown"));

        System.out.println("Grid app ready. Runtime state: " + engine.runtimeState().state());
        System.out.println("Loaded instrument spec: " + instrumentSpec);
        System.out.println("Latest snapshot price=" + snapshot.lastPrice() + ", baseline4h=" + snapshot.indicators().baseline4h());

        if (properties.runtimeSettings().autoStartLive()) {
            try (GridLiveRunner liveRunner = new GridLiveRunner(
                    properties,
                    marketDataGateway,
                    new BybitSdkPrivateStreamGateway(properties.bybitSdkConfig()),
                    engine,
                    strategyLedger,
                    new LiveCommandExecutor(tradeGateway, workingOrderRegistry, new RiskGuardService(config), eventLogger, alertService),
                    new BybitSdkOrderQueryGateway(sdkClientFactory, config.category()),
                    new BybitSdkFundingFeeQueryGateway(sdkClientFactory, config.category()),
                    stateStore,
                    workingOrderRegistry,
                    processedExecutionIds,
                    eventLogger,
                    alertService,
                    statusStore,
                    liveControlService::recordFill,
                    liveControlService::recordFundingFee
            );
                 HttpStatusServer server = httpStatusServer) {
                liveRunner.start();
                Thread.currentThread().join();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        } else if (httpStatusServer != null) {
            // Keep the local status server alive for manual inspection in non-live mode.
            try (HttpStatusServer server = httpStatusServer) {
                Thread.currentThread().join();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RebalanceService buildRebalanceService(
            GridProperties properties,
            BybitSdkClientFactory sdkClientFactory,
            WorkingOrderRegistry workingOrderRegistry,
            EventLogger eventLogger,
            AlertService alertService
    ) {
        RebalanceSettings settings = properties.rebalanceSettings();
        BybitSdkMarketDataGateway rebalanceMarketDataGateway = new BybitSdkMarketDataGateway(
                sdkClientFactory,
                settings.category(),
                new FourHourIndicatorCalculator(),
                properties.strategyConfig().emaLength4h(),
                properties.strategyConfig().bollLength4h(),
                properties.strategyConfig().bollStdDevMultiplier()
        );
        return new RebalanceService(
                settings,
                rebalanceMarketDataGateway,
                new BybitSdkAccountGateway(sdkClientFactory),
                new BybitSdkTradeGateway(sdkClientFactory, settings.category()),
                workingOrderRegistry,
                new FileRebalanceStateStore(PathResolver.absolutePath(settings.stateFilePath())),
                eventLogger,
                alertService
        );
    }

    private static GridStrategyConfig chooseStartupConfig(
            GridStrategyConfig configured,
            StateStore stateStore,
            FillStore fillStore,
            EventLogger eventLogger
    ) {
        CurrentCycleSummary openCycle = fillStore.latestOpenCycle(Instant.now(), null);
        String stateSymbol = stateStore.load()
                .filter(GridApplication::isResumableCycleState)
                .map(StrategyStateSnapshot::ledgerSnapshot)
                .map(snapshot -> snapshot.symbol() == null ? "" : snapshot.symbol().trim().toUpperCase())
                .filter(symbol -> !symbol.isBlank())
                .orElse("");
        GridStrategyConfig persistedStateConfig = stateStore.load()
                .filter(GridApplication::isResumableCycleState)
                .map(StrategyStateSnapshot::cycleConfig)
                .filter(stateConfig -> stateConfig != null
                        && stateConfig.symbol() != null
                        && !stateConfig.symbol().isBlank())
                .orElse(null);
        if (persistedStateConfig != null) {
            if (!persistedStateConfig.symbol().equalsIgnoreCase(configured.symbol())) {
                eventLogger.warn("startup.config.from-state", java.util.Map.of(
                        "configuredSymbol", configured.symbol(),
                        "stateSymbol", persistedStateConfig.symbol(),
                        "baseGridQty", persistedStateConfig.baseGridQty(),
                        "maxOrderQty", persistedStateConfig.maxOrderQty(),
                        "maxInventoryQty", persistedStateConfig.maxInventoryQty(),
                        "maxDirectionalInventoryQty", persistedStateConfig.maxDirectionalInventoryQty(),
                        "reason", "persisted cycle config"
                ));
            }
            return persistedStateConfig;
        }
        if (!stateSymbol.isBlank()) {
            GridStrategyConfig symbolConfig = stateSymbol.equalsIgnoreCase(configured.symbol())
                    ? configured
                    : withSymbol(configured, stateSymbol);
            if (!stateSymbol.equalsIgnoreCase(configured.symbol())) {
                eventLogger.warn("startup.symbol.from-state", java.util.Map.of(
                        "configuredSymbol", configured.symbol(),
                        "stateSymbol", stateSymbol,
                        "reason", "resumable cycle state"
                ));
            }
            if (openCycle != null && stateSymbol.equalsIgnoreCase(openCycle.symbol())) {
                GridStrategyConfig cycleConfig = withCycleSizing(symbolConfig, openCycle, fillStore.latestTradingFill(stateSymbol));
                if (!cycleConfig.equals(symbolConfig)) {
                    eventLogger.warn("startup.symbol.from-state", java.util.Map.of(
                            "configuredSymbol", configured.symbol(),
                            "stateSymbol", stateSymbol,
                            "baseGridQty", cycleConfig.baseGridQty(),
                            "maxOrderQty", cycleConfig.maxOrderQty(),
                            "maxInventoryQty", cycleConfig.maxInventoryQty(),
                            "maxDirectionalInventoryQty", cycleConfig.maxDirectionalInventoryQty(),
                            "reason", "resumable cycle state with open cycle sizing"
                    ));
                }
                return cycleConfig;
            }
            return symbolConfig;
        }
        if (openCycle == null || openCycle.symbol() == null || openCycle.symbol().isBlank()) {
            return configured;
        }
        String symbol = openCycle.symbol().trim().toUpperCase();
        GridStrategyConfig cycleConfig = withCycleSizing(configured, openCycle, fillStore.latestTradingFill(symbol));
        if (symbol.equalsIgnoreCase(configured.symbol()) && cycleConfig.equals(configured)) {
            return configured;
        }
        eventLogger.warn("startup.config.from-cycle", java.util.Map.of(
                "configuredSymbol", configured.symbol(),
                "cycleSymbol", symbol,
                "baseGridQty", cycleConfig.baseGridQty(),
                "maxOrderQty", cycleConfig.maxOrderQty(),
                "maxInventoryQty", cycleConfig.maxInventoryQty(),
                "maxDirectionalInventoryQty", cycleConfig.maxDirectionalInventoryQty(),
                "reason", "open cycle in fill store"
        ));
        return cycleConfig;
    }

    private static boolean isResumableCycleState(StrategyStateSnapshot snapshot) {
        if (snapshot == null || snapshot.ledgerSnapshot() == null || snapshot.runtimeState() == null) {
            return false;
        }
        return snapshot.runtimeState().state() != GridState.INIT
                && (snapshot.ledgerSnapshot().netQty().signum() != 0 || snapshot.runtimeState().activeOrderPair() != null);
    }

    private static GridStrategyConfig withSymbol(GridStrategyConfig config, String symbol) {
        return new GridStrategyConfig(
                config.category(),
                symbol,
                config.entryDirection(),
                config.baseGridSpacing(),
                config.gridRequoteShrinkFactor(),
                config.baseGridQty(),
                config.maxInitialSteps(),
                config.maxOrderQty(),
                config.maxInventoryQty(),
                config.maxDirectionalInventoryQty(),
                config.maxConsecutiveSameSideFills(),
                config.emaLength4h(),
                config.bollLength4h(),
                config.bollStdDevMultiplier(),
                config.deinventoryPnlThresholdRate(),
                config.deinventoryBoostSlope(),
                config.deinventoryBoostCap(),
                config.classicReduceSizing(),
                config.pairOrderTimeout(),
                config.triggerCooldown()
        );
    }

    private static GridStrategyConfig withCycleSizing(
            GridStrategyConfig config,
            CurrentCycleSummary cycle,
            FillRecord latestFill
    ) {
        String symbol = cycle.symbol().trim().toUpperCase();
        BigDecimal inferredBaseQty = inferBaseGridQty(config, cycle, latestFill);
        BigDecimal currentAbsQty = cycle.cumulativeQty().abs();
        BigDecimal maxOrderQty = config.maxOrderQty().max(inferredBaseQty);
        BigDecimal inventoryLimit = currentAbsQty.add(inferredBaseQty).max(config.maxInventoryQty());
        BigDecimal directionalLimit = currentAbsQty.add(inferredBaseQty).max(config.maxDirectionalInventoryQty());
        return new GridStrategyConfig(
                config.category(),
                symbol,
                config.entryDirection(),
                config.baseGridSpacing(),
                config.gridRequoteShrinkFactor(),
                inferredBaseQty,
                config.maxInitialSteps(),
                maxOrderQty,
                inventoryLimit,
                directionalLimit,
                config.maxConsecutiveSameSideFills(),
                config.emaLength4h(),
                config.bollLength4h(),
                config.bollStdDevMultiplier(),
                config.deinventoryPnlThresholdRate(),
                config.deinventoryBoostSlope(),
                config.deinventoryBoostCap(),
                config.classicReduceSizing(),
                config.pairOrderTimeout(),
                config.triggerCooldown()
        );
    }

    private static BigDecimal inferBaseGridQty(
            GridStrategyConfig config,
            CurrentCycleSummary cycle,
            FillRecord latestFill
    ) {
        if (latestFill != null
                && latestFill.qty() != null
                && latestFill.qty().signum() > 0
                && !com.btcgrid.common.model.ClientOrderIds.isInitialPositionOrder(latestFill.clientOrderId())) {
            return latestFill.qty().max(config.baseGridQty());
        }
        if (cycle.fillCount() > 0 && cycle.cumulativeQty() != null && cycle.cumulativeQty().signum() != 0) {
            BigDecimal averageCycleQty = cycle.buyQty().add(cycle.sellQty())
                    .divide(new BigDecimal(cycle.fillCount()), java.math.MathContext.DECIMAL64);
            if (averageCycleQty.signum() > 0) {
                return averageCycleQty.max(config.baseGridQty());
            }
        }
        return config.baseGridQty();
    }

    private static void restoreOpenCycleFromFills(
            String symbol,
            FillStore fillStore,
            MarketSnapshot marketSnapshot,
            GridStrategyEngine engine,
            StrategyLedger strategyLedger,
            EventLogger eventLogger
    ) {
        CurrentCycleSummary cycle = fillStore.currentCycle(
                symbol,
                Instant.now(),
                marketSnapshot == null ? null : marketSnapshot.markPrice()
        );
        if (cycle == null || cycle.cumulativeQty() == null || cycle.cumulativeQty().signum() == 0) {
            return;
        }
        TradeSide side = cycle.cumulativeQty().signum() > 0 ? TradeSide.BUY : TradeSide.SELL;
        java.math.BigDecimal avgEntryPrice = cycle.cumulativeQty().signum() == 0
                ? BigDecimal.ZERO
                : cycle.cumulativeValue().divide(cycle.cumulativeQty(), java.math.MathContext.DECIMAL64);
        java.math.BigDecimal markPrice = marketSnapshot == null ? cycle.latestFillPrice() : marketSnapshot.markPrice();
        java.math.BigDecimal floatingPnl = cycle.cumulativeQty()
                .multiply(markPrice.subtract(avgEntryPrice, java.math.MathContext.DECIMAL64), java.math.MathContext.DECIMAL64);
        strategyLedger.restore(new StrategyLedgerSnapshot(
                symbol,
                cycle.cumulativeQty(),
                avgEntryPrice.abs(),
                BigDecimal.ZERO,
                floatingPnl,
                BigDecimal.ZERO,
                cycle.latestFillPrice(),
                side,
                BandZone.INSIDE_BAND,
                0,
                0,
                InventoryMode.NORMAL,
                cycle.latestFillAt()
        ));
        engine.restore(new StrategyRuntimeState(GridState.WAITING_TRIGGER, null, cycle.latestFillAt()), null);
        eventLogger.warn("startup.state.rebuilt-from-cycle", java.util.Map.of(
                "symbol", symbol,
                "netQty", cycle.cumulativeQty(),
                "avgEntryPrice", avgEntryPrice.abs(),
                "latestFillAt", cycle.latestFillAt().toString()
        ));
    }

    private static BigDecimal profitableReduceSideMultiplier(GridProperties properties) {
        return new BigDecimal(properties.rawProperties().getProperty("grid.profitable-reduce-side-multiplier", "1.10").trim());
    }

    private static BigDecimal profitSizingEstimatedFeeRate(GridProperties properties) {
        return new BigDecimal(properties.rawProperties()
                .getProperty("grid.profit-sizing-estimated-fee-rate", "0.0002")
                .trim());
    }

    private static BigDecimal lossAddTriggerRate(GridProperties properties) {
        return new BigDecimal(properties.rawProperties()
                .getProperty("grid.loss-add-trigger-rate", "0.01")
                .trim()).max(BigDecimal.ZERO);
    }

    static BigDecimal afterEstimatedOrderFee(
            BigDecimal cyclePnl,
            BigDecimal orderPrice,
            BigDecimal orderQty,
            BigDecimal feeRate
    ) {
        BigDecimal pnl = cyclePnl == null ? BigDecimal.ZERO : cyclePnl;
        if (orderPrice == null || orderQty == null || feeRate == null
                || orderPrice.signum() <= 0 || orderQty.signum() <= 0 || feeRate.signum() <= 0) {
            return pnl;
        }
        return pnl.subtract(orderPrice.multiply(orderQty).multiply(feeRate));
    }

    static BigDecimal lossRateAfterEstimatedOrderFee(
            BigDecimal cyclePnl,
            BigDecimal cumulativeValue,
            BigDecimal orderPrice,
            BigDecimal orderQty,
            BigDecimal feeRate
    ) {
        if (cumulativeValue == null || cumulativeValue.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return afterEstimatedOrderFee(cyclePnl, orderPrice, orderQty, feeRate)
                .divide(cumulativeValue.abs(), 8, java.math.RoundingMode.HALF_UP);
    }

    static BigDecimal currentCycleLossRateAfterEstimatedFee(
            FillStore fillStore,
            MarketSnapshot marketSnapshot,
            BigDecimal price,
            BigDecimal orderQty,
            BigDecimal feeRate
    ) {
        if (fillStore == null || marketSnapshot == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnlPrice = price == null || price.signum() <= 0 ? marketSnapshot.markPrice() : price;
        CurrentCycleSummary cycle = fillStore.currentCycle(
                marketSnapshot.symbol(),
                Instant.now(),
                pnlPrice
        );
        if (cycle == null) {
            return BigDecimal.ZERO;
        }
        return lossRateAfterEstimatedOrderFee(
                cycle.markPricePnl(),
                cycle.cumulativeValue(),
                pnlPrice,
                orderQty,
                feeRate
        );
    }

    private static BigDecimal currentCycleMarketPnl(FillStore fillStore, MarketSnapshot marketSnapshot, BigDecimal price) {
        if (fillStore == null || marketSnapshot == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnlPrice = price == null || price.signum() <= 0 ? marketSnapshot.markPrice() : price;
        CurrentCycleSummary cycle = fillStore.currentCycle(marketSnapshot.symbol(), Instant.now(), pnlPrice);
        return cycle == null || cycle.markPricePnl() == null ? BigDecimal.ZERO : cycle.markPricePnl();
    }

    private static void repairFilledWorkingOrders(String symbol, FillStore fillStore, WorkingOrderRegistry workingOrderRegistry) {
        Collection<String> openClientOrderIds = workingOrderRegistry.snapshot().stream()
                .filter(order -> order.status() == com.btcgrid.common.model.OrderStatus.NEW)
                .map(order -> order.clientOrderId())
                .collect(Collectors.toSet());
        Set<String> filledClientOrderIds = fillStore.filledClientOrderIds(symbol, openClientOrderIds);
        if (filledClientOrderIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        filledClientOrderIds.forEach(clientOrderId -> workingOrderRegistry.markFilled(clientOrderId, now));
    }
}
