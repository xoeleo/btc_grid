package com.btcgrid.backtest.engine;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.config.SpacingLadder;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.StrategyCommandType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.GridStrategyEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BacktestRunner {

    private final BacktestMarketDataFeed marketDataFeed;
    private final InMemoryExecutionSimulator executionSimulator;
    private final GridStrategyEngine strategyEngine;
    private final StrategyLedger strategyLedger;
    private BacktestRunAccumulator accumulator;

    public BacktestRunner(
            BacktestMarketDataFeed marketDataFeed,
            InMemoryExecutionSimulator executionSimulator,
            GridStrategyEngine strategyEngine,
            StrategyLedger strategyLedger
    ) {
        this.marketDataFeed = marketDataFeed;
        this.executionSimulator = executionSimulator;
        this.strategyEngine = strategyEngine;
        this.strategyLedger = strategyLedger;
    }

    public BacktestResult run() {
        accumulator = new BacktestRunAccumulator();
        marketDataFeed.stream().forEach(bar -> {
            MarketSnapshot snapshot = bar.snapshot();
            accumulator.onSnapshot(snapshot);
            applyCommands(strategyEngine.onMarket(snapshot), snapshot);
            List<FillEvent> fills = executionSimulator.simulate(bar, executionSimulator.openOrders());
            accumulator.onFills(fills);
            for (FillEvent fill : fills) {
                StrategyLedgerSnapshot beforeFillSnapshot = strategyLedger.snapshot();
                applyCommands(strategyEngine.onFill(fill, snapshot), snapshot);
                accumulator.onFee(executionSimulator.feeFor(fill));
                accumulator.recordFill(fill, snapshot, beforeFillSnapshot, strategyLedger.snapshot(), strategyEngine, executionSimulator.openOrders().size());
            }
            strategyLedger.markToMarket(snapshot);
            accumulator.recordEquity(snapshot, strategyLedger.snapshot(), strategyEngine, executionSimulator.openOrders().size());
        });
        StrategyLedgerSnapshot ledgerSnapshot = strategyLedger.snapshot();
        return new BacktestResult(
                accumulator.startTime,
                accumulator.endTime,
                accumulator.snapshotsProcessed,
                accumulator.ordersPlaced,
                accumulator.ordersCanceled,
                accumulator.fillsProcessed,
                ledgerSnapshot.netQty(),
                ledgerSnapshot.avgEntryPrice(),
                ledgerSnapshot.realizedPnl(),
                ledgerSnapshot.floatingPnl(),
                accumulator.totalFees,
                grossPnl(ledgerSnapshot),
                accumulator.netPnl(ledgerSnapshot),
                accumulator.maxDrawdown,
                accumulator.grossRealizedProfit,
                accumulator.grossRealizedLoss,
                accumulator.winningCloseCount,
                accumulator.losingCloseCount,
                accumulator.breakevenCloseCount,
                accumulator.winRate(),
                accumulator.profitFactor(),
                accumulator.peakInventoryQty,
                accumulator.feeToGrossPnlRatio(ledgerSnapshot),
                strategyEngine.runtimeState().state(),
                ledgerSnapshot.inventoryMode(),
                executionSimulator.openOrders().size(),
                List.copyOf(accumulator.completedCycles),
                accumulator.openCycleSnapshot(ledgerSnapshot, accumulator.endTime),
                List.copyOf(accumulator.events),
                List.copyOf(accumulator.equityCurve)
        );
    }

    private void applyCommands(List<StrategyCommand> commands, MarketSnapshot snapshot) {
        for (StrategyCommand command : commands) {
            StrategyLedgerSnapshot beforeSnapshot = strategyLedger.snapshot();
            if (command.type() == StrategyCommandType.PLACE) {
                executionSimulator.apply(command.orderIntent(), snapshot.timestamp());
                currentAccumulator().ordersPlaced += 1;
                currentAccumulator().recordCommand(
                        command,
                        snapshot,
                        beforeSnapshot,
                        strategyEngine,
                        executionSimulator.openOrders().size()
                );
            } else if (command.type() == StrategyCommandType.CANCEL) {
                executionSimulator.cancel(command.clientOrderId());
                currentAccumulator().ordersCanceled += 1;
                currentAccumulator().recordCommand(
                        command,
                        snapshot,
                        beforeSnapshot,
                        strategyEngine,
                        executionSimulator.openOrders().size()
                );
            }
        }
    }

    private BacktestRunAccumulator currentAccumulator() {
        return accumulator;
    }

    private BigDecimal grossPnl(StrategyLedgerSnapshot ledgerSnapshot) {
        return ledgerSnapshot.realizedPnl().add(ledgerSnapshot.floatingPnl());
    }

    private final class BacktestRunAccumulator {
        private Instant startTime;
        private Instant endTime;
        private long snapshotsProcessed;
        private long ordersPlaced;
        private long ordersCanceled;
        private long fillsProcessed;
        private BigDecimal totalFees = BigDecimal.ZERO;
        private BigDecimal peakNetPnl = BigDecimal.ZERO;
        private BigDecimal maxDrawdown = BigDecimal.ZERO;
        private BigDecimal lastRealizedPnl = BigDecimal.ZERO;
        private BigDecimal peakInventoryQty = BigDecimal.ZERO;
        private BigDecimal grossRealizedProfit = BigDecimal.ZERO;
        private BigDecimal grossRealizedLoss = BigDecimal.ZERO;
        private int winningCloseCount;
        private int losingCloseCount;
        private int breakevenCloseCount;
        private final List<BacktestEvent> events = new ArrayList<>();
        private final List<BacktestEquityPoint> equityCurve = new ArrayList<>();
        private final List<CompletedCycle> completedCycles = new ArrayList<>();
        private final Map<String, OrderTrace> orderTraces = new HashMap<>();
        private int nextCycleId = 1;
        private ActiveCycle activeCycle;

        private void onSnapshot(MarketSnapshot snapshot) {
            if (startTime == null) {
                startTime = snapshot.timestamp();
            }
            endTime = snapshot.timestamp();
            snapshotsProcessed += 1;
            if (activeCycle != null) {
                activeCycle.observe(strategyLedger.snapshot(), snapshot.lastPrice(), netPnl(strategyLedger.snapshot()));
            }
        }

        private void onFills(List<FillEvent> fills) {
            fillsProcessed += fills.size();
        }

        private void onFee(BigDecimal fee) {
            totalFees = totalFees.add(fee);
        }

        private BigDecimal netPnl(StrategyLedgerSnapshot ledgerSnapshot) {
            return grossPnl(ledgerSnapshot).subtract(totalFees);
        }

        private BigDecimal winRate() {
            int closeCount = winningCloseCount + losingCloseCount + breakevenCloseCount;
            if (closeCount == 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(winningCloseCount)
                    .divide(BigDecimal.valueOf(closeCount), 8, RoundingMode.HALF_UP);
        }

        private BigDecimal profitFactor() {
            if (grossRealizedLoss.signum() == 0) {
                return grossRealizedProfit.signum() == 0 ? BigDecimal.ZERO : new BigDecimal("999999");
            }
            return grossRealizedProfit.divide(grossRealizedLoss, 8, RoundingMode.HALF_UP);
        }

        private BigDecimal feeToGrossPnlRatio(StrategyLedgerSnapshot ledgerSnapshot) {
            BigDecimal gross = grossPnl(ledgerSnapshot).abs();
            if (gross.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return totalFees.divide(gross, 8, RoundingMode.HALF_UP);
        }

        private CompletedCycle openCycleSnapshot(StrategyLedgerSnapshot ledgerSnapshot, Instant snapshotTime) {
            if (activeCycle == null) {
                return null;
            }
            Instant endInstant = snapshotTime == null ? activeCycle.startTime : snapshotTime;
            return new CompletedCycle(
                    activeCycle.cycleId,
                    activeCycle.startTime,
                    endInstant,
                    Duration.between(activeCycle.startTime, endInstant),
                    activeCycle.entrySide,
                    activeCycle.entryPrice,
                    ledgerSnapshot.netQty().signum() >= 0 ? TradeSide.SELL : TradeSide.BUY,
                    ledgerSnapshot.lastFillPrice(),
                    activeCycle.maxInventoryQty,
                    activeCycle.maxInventoryUsdt,
                    activeCycle.maxLossPnl,
                    activeCycle.realizedPnl,
                    activeCycle.fillCount
            );
        }

        private void recordCommand(
                StrategyCommand command,
                MarketSnapshot snapshot,
                StrategyLedgerSnapshot ledgerSnapshot,
                GridStrategyEngine strategyEngine,
                int openOrders
        ) {
            OrderTrace orderTrace = createOrderTrace(command, snapshot, ledgerSnapshot, strategyEngine);
            if (command.type() == StrategyCommandType.PLACE && command.orderIntent() != null) {
                orderTraces.put(command.orderIntent().clientOrderId(), orderTrace);
            }
            events.add(new BacktestEvent(
                    snapshot.timestamp(),
                    activeCycle == null ? null : activeCycle.cycleId,
                    command.type().name(),
                    command.clientOrderId(),
                    command.orderIntent() == null ? null : command.orderIntent().side(),
                    command.orderIntent() == null ? null : command.orderIntent().qty(),
                    command.orderIntent() == null ? null : command.orderIntent().price(),
                    command.orderIntent() == null || command.orderIntent().qty() == null || command.orderIntent().price() == null
                            ? null
                            : command.orderIntent().qty().multiply(command.orderIntent().price()),
                    command.orderIntent() != null && command.orderIntent().reduceOnly(),
                    command.reason(),
                    null,
                    orderTrace.orderContext(),
                    orderTrace.qtyCalcDetail(),
                    snapshot.indicators().bollUpper4h(),
                    snapshot.indicators().bollMiddle4h(),
                    snapshot.indicators().bollLower4h(),
                    strategyEngine.runtimeState().state(),
                    ledgerSnapshot.inventoryMode(),
                    ledgerSnapshot.netQty(),
                    ledgerSnapshot.netQty(),
                    ledgerSnapshot.avgEntryPrice(),
                    ledgerSnapshot.realizedPnl(),
                    BigDecimal.ZERO,
                    ledgerSnapshot.floatingPnl(),
                    totalFees,
                    grossPnl(ledgerSnapshot),
                    netPnl(ledgerSnapshot),
                    openOrders,
                    orderTrace.referencePrice(),
                    orderTrace.previousLegDistance(),
                    orderTrace.distanceMultiplier(),
                    orderTrace.baseSpacing(),
                    orderTrace.placementDistance(),
                    orderTrace.derivedSteps(),
                    orderTrace.effectiveSteps(),
                    orderTrace.minStepApplied()
            ));
        }

        private void recordFill(
                FillEvent fill,
                MarketSnapshot snapshot,
                StrategyLedgerSnapshot beforeFillSnapshot,
                StrategyLedgerSnapshot ledgerSnapshot,
                GridStrategyEngine strategyEngine,
                int openOrders
        ) {
            BigDecimal realizedPnlDelta = updateCloseMetrics(ledgerSnapshot);
            OrderTrace orderTrace = orderTraces.getOrDefault(fill.clientOrderId(), OrderTrace.empty());
            Integer cycleId = trackCycle(fill, beforeFillSnapshot, ledgerSnapshot, realizedPnlDelta);
            events.add(new BacktestEvent(
                    fill.fillTime(),
                    cycleId,
                    "FILL",
                    fill.clientOrderId(),
                    fill.side(),
                    fill.fillQty(),
                    fill.fillPrice(),
                    fill.fillQty().multiply(fill.fillPrice()),
                    false,
                    "execution",
                    classifyTradeCategory(fill, snapshot),
                    orderTrace.orderContext(),
                    orderTrace.withFillQty(fill.fillQty()),
                    snapshot.indicators().bollUpper4h(),
                    snapshot.indicators().bollMiddle4h(),
                    snapshot.indicators().bollLower4h(),
                    strategyEngine.runtimeState().state(),
                    ledgerSnapshot.inventoryMode(),
                    ledgerSnapshot.netQty(),
                    ledgerSnapshot.netQty(),
                    ledgerSnapshot.avgEntryPrice(),
                    ledgerSnapshot.realizedPnl(),
                    realizedPnlDelta,
                    ledgerSnapshot.floatingPnl(),
                    totalFees,
                    grossPnl(ledgerSnapshot),
                    netPnl(ledgerSnapshot),
                    openOrders,
                    orderTrace.referencePrice(),
                    orderTrace.previousLegDistance(),
                    orderTrace.distanceMultiplier(),
                    orderTrace.baseSpacing(),
                    orderTrace.placementDistance(),
                    orderTrace.derivedSteps(),
                    orderTrace.effectiveSteps(),
                    orderTrace.minStepApplied()
            ));
        }

        private String classifyTradeCategory(FillEvent fill, MarketSnapshot snapshot) {
            if (fill.clientOrderId().contains("_INIT_")) {
                return "\u5F00\u5355";
            }
            if (fill.fillPrice().compareTo(snapshot.indicators().bollUpper4h()) > 0) {
                return "\u5E03\u6797\u5E26\u4E0A\u6CBF";
            }
            if (fill.fillPrice().compareTo(snapshot.indicators().bollLower4h()) < 0) {
                return "\u5E03\u6797\u5E26\u4E0B\u6CBF";
            }
            return fill.fillPrice().compareTo(snapshot.indicators().bollMiddle4h()) >= 0
                    ? "\u4E2D\u7EBF\u4EE5\u4E0A"
                    : "\u4E2D\u7EBF\u4EE5\u4E0B";
        }

        private OrderTrace createOrderTrace(
                StrategyCommand command,
                MarketSnapshot snapshot,
                StrategyLedgerSnapshot ledgerSnapshot,
                GridStrategyEngine strategyEngine
        ) {
            if (command.orderIntent() == null) {
                return OrderTrace.empty();
            }
            OrderIntent orderIntent = command.orderIntent();
            GridStrategyConfig config = strategyEngine.config();
            if ("initial-position".equals(orderIntent.reason())) {
                BigDecimal baseline = snapshot.indicators().baseline4h();
                BigDecimal currentPrice = snapshot.lastPrice();
                BigDecimal baseSpacing = baseline.multiply(config.baseGridSpacing());
                BigDecimal baselineDistance = currentPrice.subtract(baseline).abs();
                BigDecimal placementDistance = baseSpacing;
                BigDecimal derivedSteps = rawSteps(placementDistance, baseSpacing);
                BigDecimal effectiveSteps = stepsFromQty(orderIntent.qty(), config.baseGridQty());
                return new OrderTrace(
                        "\u5F00\u5355",
                        "baseline_distance=|current_price-boll_middle|"
                                + " = |" + currentPrice + "-" + baseline + "|"
                                + " = " + baselineDistance
                                + " / base_spacing=" + baseSpacing
                                + " / placement_distance=base_spacing"
                                + " = " + placementDistance
                                + " / derived_steps=" + derivedSteps.stripTrailingZeros().toPlainString()
                                + " / effective_steps=" + effectiveSteps.stripTrailingZeros().toPlainString()
                                + " / min_step_applied=" + (effectiveSteps.compareTo(derivedSteps) > 0)
                                + " * baseQty=" + config.baseGridQty()
                                + " * boost=1"
                                + " => finalQty=" + orderIntent.qty(),
                        baseline,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        baseSpacing,
                        baseSpacing,
                        derivedSteps,
                        effectiveSteps,
                        effectiveSteps.compareTo(derivedSteps) > 0
                );
            }

            BigDecimal referencePrice = ledgerSnapshot.lastFillPrice().signum() == 0
                    ? snapshot.lastPrice()
                    : ledgerSnapshot.lastFillPrice();
            BigDecimal previousLegDistance = previousLegDistance(ledgerSnapshot, orderIntent.side());
            BigDecimal distanceMultiplier = distanceMultiplier(ledgerSnapshot, orderIntent.side());
            BigDecimal placementDistance = orderIntent.price() == null
                    ? BigDecimal.ZERO
                    : orderIntent.price().subtract(referencePrice).abs();
            BigDecimal baseSpacing = referencePrice.multiply(config.baseGridSpacing());
            BigDecimal derivedSteps = rawSteps(placementDistance, baseSpacing);
            BigDecimal effectiveSteps = stepsFromQty(orderIntent.qty(), config.baseGridQty());
            BigDecimal theoreticalQty = normalizeForFormula(effectiveSteps.multiply(config.baseGridQty()));
            BigDecimal boost = theoreticalQty.signum() == 0
                    ? BigDecimal.ONE
                    : orderIntent.qty().divide(theoreticalQty, 8, RoundingMode.HALF_UP);
            ReduceQtyTrace reduceQtyTrace = reduceQtyTrace(orderIntent, ledgerSnapshot, snapshot, referencePrice);
            String qtyCalcDetail = reduceQtyTrace.qtyCalcDetail() != null
                    ? reduceQtyTrace.qtyCalcDetail()
                    : "previous_leg_distance="
                            + (previousLegDistance.signum() > 0
                            ? "|last_fill_price-previous_same_side_fill_price| = |"
                            + ledgerSnapshot.lastFillPrice() + "-" + ledgerSnapshot.previousFillPrice() + "| = "
                            + previousLegDistance
                            : "0")
                            + " / distance_multiplier=" + distanceMultiplier.stripTrailingZeros().toPlainString()
                            + " / base_spacing=" + baseSpacing
                            + " / placement_distance="
                            + (previousLegDistance.signum() > 0
                            ? "previous_leg_distance * distance_multiplier = "
                            + previousLegDistance + " * " + distanceMultiplier.stripTrailingZeros().toPlainString()
                            + " = " + placementDistance
                            : "base_spacing = " + placementDistance)
                            + " / derived_steps=" + derivedSteps.stripTrailingZeros().toPlainString()
                            + " / effective_steps=" + effectiveSteps.stripTrailingZeros().toPlainString()
                            + " / min_step_applied=" + (effectiveSteps.compareTo(derivedSteps) > 0)
                            + " => steps=" + effectiveSteps
                            + " * baseQty=" + config.baseGridQty()
                            + " * boost=" + boost.stripTrailingZeros().toPlainString()
                            + " => finalQty=" + orderIntent.qty();

            return new OrderTrace(
                    orderContextLabel(orderIntent.reason()),
                    qtyCalcDetail,
                    referencePrice,
                    previousLegDistance,
                    distanceMultiplier,
                    baseSpacing,
                    placementDistance,
                    derivedSteps,
                    effectiveSteps,
                    effectiveSteps.compareTo(derivedSteps) > 0
            );
        }

        private BigDecimal rawSteps(BigDecimal distance, BigDecimal spacing) {
            if (spacing == null || spacing.signum() == 0 || distance == null || distance.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return distance.divide(spacing, 8, RoundingMode.HALF_UP);
        }

        private BigDecimal stepsFromQty(BigDecimal qty, BigDecimal baseQty) {
            if (qty == null || baseQty == null || baseQty.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return qty.divide(baseQty, 8, RoundingMode.HALF_UP);
        }

        private BigDecimal normalizeForFormula(BigDecimal qty) {
            return qty.signum() == 0 ? BigDecimal.ONE : qty;
        }

        private BigDecimal previousLegDistance(StrategyLedgerSnapshot ledgerSnapshot, TradeSide orderSide) {
            if (ledgerSnapshot.previousFillPrice() == null || ledgerSnapshot.lastFillPrice() == null) {
                return BigDecimal.ZERO;
            }
            if (ledgerSnapshot.previousFillPrice().signum() == 0 || ledgerSnapshot.lastFillPrice().signum() == 0) {
                return BigDecimal.ZERO;
            }
            if (ledgerSnapshot.lastFillSide() != orderSide) {
                return BigDecimal.ZERO;
            }
            int consecutiveCount = orderSide == TradeSide.BUY
                    ? ledgerSnapshot.consecutiveBuyFills()
                    : ledgerSnapshot.consecutiveSellFills();
            if (consecutiveCount <= 1) {
                return BigDecimal.ZERO;
            }
            return ledgerSnapshot.lastFillPrice().subtract(ledgerSnapshot.previousFillPrice()).abs();
        }

        private BigDecimal distanceMultiplier(StrategyLedgerSnapshot ledgerSnapshot, TradeSide orderSide) {
            int consecutiveCount = orderSide == TradeSide.BUY
                    ? ledgerSnapshot.consecutiveBuyFills()
                    : ledgerSnapshot.consecutiveSellFills();
            if (consecutiveCount <= 1) {
                return BigDecimal.ONE;
            }
            return SpacingLadder.multiplier(consecutiveCount);
        }

        private ReduceQtyTrace reduceQtyTrace(
                OrderIntent orderIntent,
                StrategyLedgerSnapshot ledgerSnapshot,
                MarketSnapshot snapshot,
                BigDecimal referencePrice
        ) {
            if (orderIntent.reason() == null || !orderIntent.reason().contains("reduce-qty")) {
                return ReduceQtyTrace.empty();
            }
            if (ledgerSnapshot.netQty().signum() == 0 || orderIntent.price() == null) {
                return ReduceQtyTrace.empty();
            }
            boolean isReduceSell = ledgerSnapshot.netQty().signum() > 0 && orderIntent.side() == TradeSide.SELL;
            boolean isReduceBuy = ledgerSnapshot.netQty().signum() < 0 && orderIntent.side() == TradeSide.BUY;
            if (!isReduceSell && !isReduceBuy) {
                return ReduceQtyTrace.empty();
            }
            BigDecimal inventory = ledgerSnapshot.netQty().abs();
            BigDecimal bandEdgeDistance = isReduceSell
                    ? snapshot.indicators().bollUpper4h().subtract(referencePrice)
                    : referencePrice.subtract(snapshot.indicators().bollLower4h());
            BigDecimal orderDistance = isReduceSell
                    ? orderIntent.price().subtract(referencePrice)
                    : referencePrice.subtract(orderIntent.price());
            if (orderDistance.signum() <= 0) {
                return ReduceQtyTrace.empty();
            }
            if (bandEdgeDistance.signum() <= 0) {
                return new ReduceQtyTrace(
                        "reduce_qty=inventory="
                                + inventory
                                + " / band_edge_distance<=0 => finalQty=" + orderIntent.qty()
                );
            }
            return new ReduceQtyTrace(
                    "reduce_qty=inventory="
                            + inventory
                            + " / band_edge_distance=|band_edge-reference_price| = |"
                            + (isReduceSell ? snapshot.indicators().bollUpper4h() : snapshot.indicators().bollLower4h())
                            + "-" + referencePrice + "| = " + bandEdgeDistance
                            + " * order_distance=|order_price-reference_price| = |"
                            + orderIntent.price() + "-" + referencePrice + "| = " + orderDistance
                            + " => finalQty=" + orderIntent.qty()
            );
        }

        private String orderContextLabel(String reason) {
            if (reason == null || reason.isBlank()) {
                return "";
            }
            if ("initial-position".equals(reason)) {
                return "\u5F00\u5355";
            }
            String code = reason;
            int separator = code.indexOf('|');
            if (separator >= 0) {
                code = code.substring(0, separator);
            }
            return switch (code) {
                case "trigger-above-upper" -> "\u89E6\u53D1\u6302\u7F51-\u5E03\u6797\u4E0A\u6CBF\u5916";
                case "trigger-below-lower" -> "\u89E6\u53D1\u6302\u7F51-\u5E03\u6797\u4E0B\u6CBF\u5916";
                case "trigger-last-above-now-below-baseline" -> "\u89E6\u53D1\u6302\u7F51-\u4E0A\u6B21\u4E0A\u6CBF\u6210\u4EA4\u4E14\u5F53\u524D\u5728\u57FA\u7EBF\u4E0B";
                case "trigger-last-below-now-above-baseline" -> "\u89E6\u53D1\u6302\u7F51-\u4E0A\u6B21\u4E0B\u6CBF\u6210\u4EA4\u4E14\u5F53\u524D\u5728\u57FA\u7EBF\u4E0A";
                case "follow-fill" -> "\u7F51\u683C\u7EED\u6302";
                case "requote" -> "\u8D85\u65F6\u91CD\u6302";
                default -> code;
            };
        }

        private Integer trackCycle(
                FillEvent fill,
                StrategyLedgerSnapshot beforeFillSnapshot,
                StrategyLedgerSnapshot afterFillSnapshot,
                BigDecimal realizedPnlDelta
        ) {
            if (beforeFillSnapshot.netQty().signum() == 0 && afterFillSnapshot.netQty().signum() != 0) {
                activeCycle = new ActiveCycle(
                        nextCycleId++,
                        fill.fillTime(),
                        fill.side(),
                        fill.fillPrice(),
                        netPnl(afterFillSnapshot)
                );
            }
            if (activeCycle == null) {
                return null;
            }
            Integer cycleId = activeCycle.cycleId;
            activeCycle.fillCount += 1;
            activeCycle.realizedPnl = activeCycle.realizedPnl.add(realizedPnlDelta);
            activeCycle.observe(afterFillSnapshot, fill.fillPrice(), netPnl(afterFillSnapshot));
            if (afterFillSnapshot.netQty().signum() == 0) {
                completedCycles.add(new CompletedCycle(
                        activeCycle.cycleId,
                        activeCycle.startTime,
                        fill.fillTime(),
                        Duration.between(activeCycle.startTime, fill.fillTime()),
                        activeCycle.entrySide,
                        activeCycle.entryPrice,
                        fill.side(),
                        fill.fillPrice(),
                        activeCycle.maxInventoryQty,
                        activeCycle.maxInventoryUsdt,
                        activeCycle.maxLossPnl,
                        activeCycle.realizedPnl,
                        activeCycle.fillCount
                ));
                activeCycle = null;
            }
            return cycleId;
        }

        private void recordEquity(
                MarketSnapshot snapshot,
                StrategyLedgerSnapshot ledgerSnapshot,
                GridStrategyEngine strategyEngine,
                int openOrders
        ) {
            BigDecimal netPnl = netPnl(ledgerSnapshot);
            if (netPnl.compareTo(peakNetPnl) > 0) {
                peakNetPnl = netPnl;
            }
            BigDecimal drawdown = peakNetPnl.subtract(netPnl);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
            BigDecimal absInventory = ledgerSnapshot.netQty().abs();
            if (absInventory.compareTo(peakInventoryQty) > 0) {
                peakInventoryQty = absInventory;
            }
            equityCurve.add(new BacktestEquityPoint(
                    snapshot.timestamp(),
                    snapshot.lastPrice(),
                    ledgerSnapshot.netQty(),
                    ledgerSnapshot.avgEntryPrice(),
                    ledgerSnapshot.realizedPnl(),
                    ledgerSnapshot.floatingPnl(),
                    totalFees,
                    grossPnl(ledgerSnapshot),
                    netPnl,
                    strategyEngine.runtimeState().state(),
                    ledgerSnapshot.inventoryMode(),
                    openOrders
            ));
        }

        private BigDecimal updateCloseMetrics(StrategyLedgerSnapshot ledgerSnapshot) {
            BigDecimal realizedDelta = ledgerSnapshot.realizedPnl().subtract(lastRealizedPnl);
            lastRealizedPnl = ledgerSnapshot.realizedPnl();
            if (realizedDelta.signum() > 0) {
                winningCloseCount += 1;
                grossRealizedProfit = grossRealizedProfit.add(realizedDelta);
            } else if (realizedDelta.signum() < 0) {
                losingCloseCount += 1;
                grossRealizedLoss = grossRealizedLoss.add(realizedDelta.abs());
            }
            return realizedDelta;
        }
    }

    private static final class ActiveCycle {
        private final int cycleId;
        private final Instant startTime;
        private final TradeSide entrySide;
        private final BigDecimal entryPrice;
        private final BigDecimal startNetPnl;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private BigDecimal maxInventoryQty = BigDecimal.ZERO;
        private BigDecimal maxInventoryUsdt = BigDecimal.ZERO;
        private BigDecimal maxLossPnl = BigDecimal.ZERO;
        private int fillCount;

        private ActiveCycle(int cycleId, Instant startTime, TradeSide entrySide, BigDecimal entryPrice, BigDecimal startNetPnl) {
            this.cycleId = cycleId;
            this.startTime = startTime;
            this.entrySide = entrySide;
            this.entryPrice = entryPrice;
            this.startNetPnl = startNetPnl;
        }

        private void observe(StrategyLedgerSnapshot ledgerSnapshot, BigDecimal markPrice, BigDecimal netPnl) {
            BigDecimal absQty = ledgerSnapshot.netQty().abs();
            if (absQty.compareTo(maxInventoryQty) > 0) {
                maxInventoryQty = absQty;
            }
            BigDecimal inventoryUsdt = absQty.multiply(markPrice);
            if (inventoryUsdt.compareTo(maxInventoryUsdt) > 0) {
                maxInventoryUsdt = inventoryUsdt;
            }
            BigDecimal cyclePnl = netPnl.subtract(startNetPnl);
            if (cyclePnl.compareTo(maxLossPnl) < 0) {
                maxLossPnl = cyclePnl;
            }
        }
    }

    private record OrderTrace(
            String orderContext,
            String qtyCalcDetail,
            BigDecimal referencePrice,
            BigDecimal previousLegDistance,
            BigDecimal distanceMultiplier,
            BigDecimal baseSpacing,
            BigDecimal placementDistance,
            BigDecimal derivedSteps,
            BigDecimal effectiveSteps,
            Boolean minStepApplied
    ) {
        private static OrderTrace empty() {
            return new OrderTrace("", "", null, null, null, null, null, null, null, null);
        }

        private String withFillQty(BigDecimal fillQty) {
            if (qtyCalcDetail == null || qtyCalcDetail.isBlank()) {
                return "";
            }
            return qtyCalcDetail + " ; fillQty=" + fillQty;
        }
    }

    private record ReduceQtyTrace(
            String qtyCalcDetail
    ) {
        private static ReduceQtyTrace empty() {
            return new ReduceQtyTrace(null);
        }
    }
}
