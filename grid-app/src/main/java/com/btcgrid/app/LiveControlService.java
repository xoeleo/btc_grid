package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitSdkClientFactory;
import com.btcgrid.bybit.adapter.BybitSdkMarketDataGateway;
import com.btcgrid.bybit.adapter.BybitSdkPrivateStreamGateway;
import com.btcgrid.bybit.adapter.BybitSdkTradeGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LiveControlService {

    private final GridProperties baseProperties;
    private final BybitSdkClientFactory clientFactory;
    private final RuntimeStatusProvider fallbackStatusProvider;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final StateStore stateStore;
    private final StatusStore statusStore;
    private final List<FillRecord> recentFills = Collections.synchronizedList(new ArrayList<>());
    private volatile GridLiveRunner activeRunner;
    private volatile GridStrategyConfig lastConfig;

    public LiveControlService(
            GridProperties baseProperties,
            BybitSdkClientFactory clientFactory,
            RuntimeStatusProvider fallbackStatusProvider,
            EventLogger eventLogger,
            AlertService alertService,
            StateStore stateStore,
            StatusStore statusStore
    ) {
        this.baseProperties = baseProperties;
        this.clientFactory = clientFactory;
        this.fallbackStatusProvider = fallbackStatusProvider;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.stateStore = stateStore;
        this.statusStore = statusStore;
        this.lastConfig = baseProperties.strategyConfig();
    }

    public synchronized Map<String, Object> start(StartRequest request) {
        if (activeRunner != null && activeRunner.isRunning()) {
            return Map.of("ok", false, "message", "strategy already running");
        }
        if (baseProperties.bybitSdkConfig().apiKey().isBlank() || baseProperties.bybitSdkConfig().apiSecret().isBlank()) {
            return Map.of("ok", false, "message", "missing bybit testnet api key or secret");
        }
        GridStrategyConfig config = request.toConfig(baseProperties.strategyConfig());
        lastConfig = config;
        recentFills.clear();
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
        runner.start();
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

    public RuntimeStatusSnapshot snapshot() {
        GridLiveRunner runner = activeRunner;
        return runner != null ? runner.snapshot() : fallbackStatusProvider.snapshot();
    }

    public Map<String, Object> controlState() {
        GridLiveRunner runner = activeRunner;
        return Map.of(
                "running", runner != null && runner.isRunning(),
                "config", lastConfig
        );
    }

    public List<FillRecord> recentFills() {
        synchronized (recentFills) {
            return List.copyOf(recentFills);
        }
    }

    private void recordFill(FillEvent fillEvent) {
        FillRecord record = new FillRecord(
                fillEvent.fillTime(),
                fillEvent.symbol(),
                fillEvent.side().name(),
                fillEvent.fillQty(),
                fillEvent.fillPrice(),
                fillEvent.clientOrderId(),
                fillEvent.executionId()
        );
        synchronized (recentFills) {
            recentFills.add(0, record);
            while (recentFills.size() > 200) {
                recentFills.remove(recentFills.size() - 1);
            }
        }
    }

    public record FillRecord(
            Instant time,
            String symbol,
            String side,
            BigDecimal qty,
            BigDecimal price,
            String clientOrderId,
            String executionId
    ) {
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
