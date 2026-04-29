package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitSdkClientFactory;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitSdkOrderQueryGateway;
import com.btcgrid.bybit.adapter.BybitSdkPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkTradeGateway;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Set;

public class GridApplication {

    public static void main(String[] args) {
        GridProperties properties = GridConfigLoader.load("application.properties");
        GridStrategyConfig config = properties.strategyConfig();
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
        GridStrategyEngine engine = new GridStrategyEngine(
                config,
                instrumentSpec,
                strategyLedger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory())
        );
        EventLogger eventLogger = new CompositeEventLogger(List.of(
                new StructuredEventLogger(),
                new FileEventLogger(PathResolver.absolutePath(properties.runtimeSettings().logFilePath()))
        ));
        AlertService alertService = new AlertService(eventLogger);
        BybitSdkTradeGateway tradeGateway = new BybitSdkTradeGateway(sdkClientFactory, config.category());
        StateStore stateStore = new FileStateStore(PathResolver.absolutePath(properties.runtimeSettings().stateFilePath()));
        StatusStore statusStore = new FileStatusStore(PathResolver.absolutePath(properties.runtimeSettings().statusFilePath()));
        WorkingOrderRegistry workingOrderRegistry = new WorkingOrderRegistry();
        RecoveryState recoveryState = new RecoveryBootstrap(stateStore).restore(engine, strategyLedger);
        workingOrderRegistry.replaceAll(recoveryState.trackedWorkingOrders());
        StartupReconciliationService reconciliationService = new StartupReconciliationService(
                new BybitSdkOrderQueryGateway(sdkClientFactory, config.category()),
                !properties.bybitSdkConfig().apiKey().isBlank() && !properties.bybitSdkConfig().apiSecret().isBlank()
        );
        workingOrderRegistry.replaceAll(reconciliationService.reconcile(config.symbol(), workingOrderRegistry.snapshot()));
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
                List.copyOf(workingOrderRegistry.snapshot())
        ));
        eventLogger.info("startup.ready", java.util.Map.of(
                "symbol", config.symbol(),
                "state", engine.runtimeState().state().name(),
                "trackedOrders", workingOrderRegistry.snapshot().size(),
                "stateFile", properties.runtimeSettings().stateFilePath(),
                "logFile", properties.runtimeSettings().logFilePath(),
                "statusFile", properties.runtimeSettings().statusFilePath(),
                "httpEnabled", properties.runtimeSettings().httpEnabled(),
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
                runtimeStatusProvider,
                eventLogger,
                alertService,
                stateStore,
                statusStore
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
                    stateStore,
                    workingOrderRegistry,
                    processedExecutionIds,
                    eventLogger,
                    alertService,
                    statusStore
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
}
