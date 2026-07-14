package com.btcgrid.strategy.service;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.ActiveOrderPair;
import com.btcgrid.strategy.model.OrderPairPlan;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class GridStrategyEngine {

    private final GridStrategyConfig config;
    private final InstrumentSpec instrumentSpec;
    private final StrategyLedger strategyLedger;
    private final GridTriggerEvaluator triggerEvaluator;
    private final GridOrderPlanner orderPlanner;
    private final boolean manualStartMode;

    private StrategyRuntimeState runtimeState = StrategyRuntimeState.initial();
    private BigDecimal activeSpacing;

    public GridStrategyEngine(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            StrategyLedger strategyLedger,
            GridTriggerEvaluator triggerEvaluator,
            GridOrderPlanner orderPlanner
    ) {
        this(config, instrumentSpec, strategyLedger, triggerEvaluator, orderPlanner, false);
    }

    public GridStrategyEngine(
            GridStrategyConfig config,
            InstrumentSpec instrumentSpec,
            StrategyLedger strategyLedger,
            GridTriggerEvaluator triggerEvaluator,
            GridOrderPlanner orderPlanner,
            boolean manualStartMode
    ) {
        this.config = config;
        this.instrumentSpec = instrumentSpec;
        this.strategyLedger = strategyLedger;
        this.triggerEvaluator = triggerEvaluator;
        this.orderPlanner = orderPlanner;
        this.manualStartMode = manualStartMode;
    }

    public List<StrategyCommand> onMarket(MarketSnapshot marketSnapshot) {
        strategyLedger.markToMarket(marketSnapshot);
        StrategyLedgerSnapshot ledgerSnapshot = strategyLedger.snapshot();
        if (ledgerSnapshot.inventoryMode() == InventoryMode.DEINVENTORY) {
            strategyLedger.switchInventoryMode(InventoryMode.NORMAL);
            ledgerSnapshot = strategyLedger.snapshot();
        }
        List<StrategyCommand> commands = new ArrayList<>();
        boolean boostReduceSide = shouldBoostReduceSide(marketSnapshot.indicators(), marketSnapshot, ledgerSnapshot);

        switch (runtimeState.state()) {
            case INIT -> {
                OrderIntent initialOrder = orderPlanner.planInitialOrder(config, instrumentSpec, marketSnapshot, manualStartMode);
                if (initialOrder != null) {
                    commands.add(StrategyCommand.place(initialOrder));
                    runtimeState = runtimeState.withState(GridState.OPENING_FIRST_POSITION);
                }
            }
            case WAITING_TRIGGER -> {
                if (ledgerSnapshot.netQty().signum() == 0) {
                    runtimeState = runtimeState.withState(GridState.INIT);
                    return commands;
                }
                if ((manualStartMode || triggerEvaluator.canActivate(marketSnapshot, ledgerSnapshot))
                        && (manualStartMode || marketSnapshot.timestamp().isAfter(runtimeState.lastTriggerTime().plus(config.triggerCooldown())))) {
                    String pairReason = manualStartMode ? "manual-grid" : activationReason(marketSnapshot, ledgerSnapshot);
                    OrderPairPlan pairPlan = orderPlanner.planPairedOrders(
                            config,
                            instrumentSpec,
                            marketSnapshot,
                            ledgerSnapshot,
                            ledgerSnapshot.lastFillPrice().signum() == 0 ? marketSnapshot.lastPrice() : ledgerSnapshot.lastFillPrice(),
                            null,
                            boostReduceSide,
                            pairReason
                    );
                    if (pairPlan != null) {
                        addPlaceCommands(commands, pairPlan);
                        activeSpacing = plannedSpacing(pairPlan, ledgerSnapshot.lastFillPrice().signum() == 0 ? marketSnapshot.lastPrice() : ledgerSnapshot.lastFillPrice());
                        runtimeState = new StrategyRuntimeState(
                                GridState.GRID_ACTIVE,
                                new ActiveOrderPair(UUID.randomUUID().toString(), pairPlan.buyOrder(), pairPlan.sellOrder(), marketSnapshot.timestamp()),
                                marketSnapshot.timestamp()
                        );
                    }
                }
            }
            case GRID_ACTIVE -> {
                ActiveOrderPair pair = runtimeState.activeOrderPair();
                if (pair != null
                        && !config.pairOrderTimeout().isZero()
                        && marketSnapshot.timestamp().isAfter(pair.createdAt().plus(config.pairOrderTimeout()))) {
                    if (pair.buyOrder() != null) {
                        commands.add(StrategyCommand.cancel(pair.buyOrder().symbol(), pair.buyOrder().clientOrderId(), "pair-timeout"));
                    }
                    if (pair.sellOrder() != null) {
                        commands.add(StrategyCommand.cancel(pair.sellOrder().symbol(), pair.sellOrder().clientOrderId(), "pair-timeout"));
                    }
                    BigDecimal requoteReferencePrice = ledgerSnapshot.lastFillPrice().signum() == 0
                            ? marketSnapshot.lastPrice()
                            : ledgerSnapshot.lastFillPrice();
                    activeSpacing = orderPlanner.nextRequoteSpacing(
                            config,
                            activeSpacing == null ? orderPlanner.baseSpacing(config, requoteReferencePrice) : activeSpacing,
                            requoteReferencePrice
                    );
                    OrderPairPlan requotePlan = orderPlanner.planPairedOrders(
                            config,
                            instrumentSpec,
                            marketSnapshot,
                            ledgerSnapshot,
                            requoteReferencePrice,
                            activeSpacing,
                            boostReduceSide,
                            "requote"
                    );
                    if (requotePlan != null) {
                        addPlaceCommands(commands, requotePlan);
                        runtimeState = new StrategyRuntimeState(
                                GridState.REQUOTE,
                                new ActiveOrderPair(UUID.randomUUID().toString(), requotePlan.buyOrder(), requotePlan.sellOrder(), marketSnapshot.timestamp()),
                                runtimeState.lastTriggerTime()
                        );
                    } else {
                        runtimeState = new StrategyRuntimeState(
                                GridState.WAITING_TRIGGER,
                                null,
                                runtimeState.lastTriggerTime()
                        );
                    }
                }
            }
            case REQUOTE -> runtimeState = runtimeState.withState(GridState.GRID_ACTIVE);
            case OPENING_FIRST_POSITION, DEINVENTORY, PAUSED -> {
            }
        }

        return commands;
    }

    public List<StrategyCommand> onFill(FillEvent fillEvent, MarketSnapshot marketSnapshot) {
        strategyLedger.applyFill(fillEvent);
        StrategyLedgerSnapshot ledgerSnapshot = strategyLedger.snapshot();
        List<StrategyCommand> commands = new ArrayList<>();
        boolean boostReduceSide = shouldBoostReduceSide(marketSnapshot.indicators(), marketSnapshot, ledgerSnapshot);

        if (runtimeState.state() == GridState.OPENING_FIRST_POSITION) {
            if (manualStartMode && ledgerSnapshot.netQty().signum() != 0) {
                BigDecimal referencePrice = ledgerSnapshot.lastFillPrice().signum() == 0
                        ? fillEvent.fillPrice()
                        : ledgerSnapshot.lastFillPrice();
                OrderPairPlan firstPair = orderPlanner.planPairedOrders(
                        config,
                        instrumentSpec,
                        marketSnapshot,
                        ledgerSnapshot,
                        referencePrice,
                        null,
                        boostReduceSide,
                        "manual-grid"
                );
                if (firstPair != null) {
                    addPlaceCommands(commands, firstPair);
                    activeSpacing = plannedSpacing(firstPair, referencePrice);
                    runtimeState = new StrategyRuntimeState(
                            GridState.GRID_ACTIVE,
                            new ActiveOrderPair(UUID.randomUUID().toString(), firstPair.buyOrder(), firstPair.sellOrder(), marketSnapshot.timestamp()),
                            runtimeState.lastTriggerTime()
                    );
                    return commands;
                }
            }
            runtimeState = runtimeState.withState(GridState.WAITING_TRIGGER);
            return commands;
        }

        ActiveOrderPair pair = runtimeState.activeOrderPair();
        if (ledgerSnapshot.netQty().signum() == 0) {
            cancelActivePair(commands, pair, fillEvent.clientOrderId(), "cycle-end");
            runtimeState = new StrategyRuntimeState(
                    GridState.INIT,
                    null,
                    runtimeState.lastTriggerTime()
            );
            return commands;
        }
        if (pair != null && pair.contains(fillEvent.clientOrderId())) {
            OrderIntent opposite = pair.oppositeOf(fillEvent.clientOrderId());
            if (opposite != null) {
                commands.add(StrategyCommand.cancel(opposite.symbol(), opposite.clientOrderId(), "pair-opposite-filled"));
            }
            OrderPairPlan nextPair = orderPlanner.planPairedOrders(
                    config,
                    instrumentSpec,
                    marketSnapshot,
                    ledgerSnapshot,
                    fillEvent.fillPrice(),
                    null,
                    boostReduceSide,
                    "follow-fill"
            );
            if (nextPair != null) {
                addPlaceCommands(commands, nextPair);
                activeSpacing = plannedSpacing(nextPair, fillEvent.fillPrice());
                runtimeState = new StrategyRuntimeState(
                        GridState.GRID_ACTIVE,
                        new ActiveOrderPair(UUID.randomUUID().toString(), nextPair.buyOrder(), nextPair.sellOrder(), marketSnapshot.timestamp()),
                        runtimeState.lastTriggerTime()
                );
            } else {
                runtimeState = new StrategyRuntimeState(
                        GridState.WAITING_TRIGGER,
                        null,
                        runtimeState.lastTriggerTime()
                );
            }
        }

        return commands;
    }

    private void cancelActivePair(List<StrategyCommand> commands, ActiveOrderPair pair, String filledClientOrderId, String reason) {
        if (pair == null) {
            return;
        }
        addCancelIfDifferent(commands, pair.buyOrder(), filledClientOrderId, reason);
        addCancelIfDifferent(commands, pair.sellOrder(), filledClientOrderId, reason);
    }

    private void addCancelIfDifferent(List<StrategyCommand> commands, OrderIntent order, String filledClientOrderId, String reason) {
        if (order != null && !order.clientOrderId().equals(filledClientOrderId)) {
            commands.add(StrategyCommand.cancel(order.symbol(), order.clientOrderId(), reason));
        }
    }

    public List<StrategyCommand> replanActivePair(
            MarketSnapshot marketSnapshot,
            Set<String> activeClientOrderIds,
            String reason
    ) {
        strategyLedger.markToMarket(marketSnapshot);
        StrategyLedgerSnapshot ledgerSnapshot = strategyLedger.snapshot();
        List<StrategyCommand> commands = new ArrayList<>();
        ActiveOrderPair pair = runtimeState.activeOrderPair();
        if (runtimeState.state() != GridState.GRID_ACTIVE || pair == null || ledgerSnapshot.netQty().signum() == 0) {
            return commands;
        }

        if (pair.buyOrder() != null && activeClientOrderIds.contains(pair.buyOrder().clientOrderId())) {
            commands.add(StrategyCommand.cancel(pair.buyOrder().symbol(), pair.buyOrder().clientOrderId(), reason));
        }
        if (pair.sellOrder() != null && activeClientOrderIds.contains(pair.sellOrder().clientOrderId())) {
            commands.add(StrategyCommand.cancel(pair.sellOrder().symbol(), pair.sellOrder().clientOrderId(), reason));
        }

        boolean boostReduceSide = shouldBoostReduceSide(marketSnapshot.indicators(), marketSnapshot, ledgerSnapshot);
        BigDecimal referencePrice = ledgerSnapshot.lastFillPrice().signum() == 0
                ? marketSnapshot.lastPrice()
                : ledgerSnapshot.lastFillPrice();
        OrderPairPlan replan = orderPlanner.planPairedOrders(
                config,
                instrumentSpec,
                marketSnapshot,
                ledgerSnapshot,
                referencePrice,
                activeSpacing,
                boostReduceSide,
                reason
        );
        if (replan != null) {
            addPlaceCommands(commands, replan);
            activeSpacing = plannedSpacing(replan, referencePrice);
            runtimeState = new StrategyRuntimeState(
                    GridState.GRID_ACTIVE,
                    new ActiveOrderPair(UUID.randomUUID().toString(), replan.buyOrder(), replan.sellOrder(), marketSnapshot.timestamp()),
                    runtimeState.lastTriggerTime()
            );
        } else {
            runtimeState = new StrategyRuntimeState(
                    GridState.WAITING_TRIGGER,
                    null,
                    runtimeState.lastTriggerTime()
            );
        }

        return commands;
    }

    public List<StrategyCommand> recoverPairFromLastFill(MarketSnapshot marketSnapshot, String reason) {
        strategyLedger.markToMarket(marketSnapshot);
        StrategyLedgerSnapshot ledgerSnapshot = strategyLedger.snapshot();
        if (ledgerSnapshot.netQty().signum() == 0
                || runtimeState.activeOrderPair() != null
                || runtimeState.state() == GridState.GRID_ACTIVE
                || ledgerSnapshot.lastFillPrice().signum() == 0) {
            return List.of();
        }
        boolean boostReduceSide = shouldBoostReduceSide(marketSnapshot.indicators(), marketSnapshot, ledgerSnapshot);
        OrderPairPlan recoveryPlan = orderPlanner.planPairedOrders(
                config,
                instrumentSpec,
                marketSnapshot,
                ledgerSnapshot,
                ledgerSnapshot.lastFillPrice(),
                null,
                boostReduceSide,
                reason
        );
        if (recoveryPlan == null) {
            runtimeState = new StrategyRuntimeState(
                    GridState.WAITING_TRIGGER,
                    null,
                    runtimeState.lastTriggerTime()
            );
            return List.of();
        }
        List<StrategyCommand> commands = new ArrayList<>();
        addPlaceCommands(commands, recoveryPlan);
        activeSpacing = plannedSpacing(recoveryPlan, ledgerSnapshot.lastFillPrice());
        runtimeState = new StrategyRuntimeState(
                GridState.GRID_ACTIVE,
                new ActiveOrderPair(UUID.randomUUID().toString(), recoveryPlan.buyOrder(), recoveryPlan.sellOrder(), marketSnapshot.timestamp()),
                runtimeState.lastTriggerTime()
        );
        return commands;
    }

    public StrategyRuntimeState runtimeState() {
        return runtimeState;
    }

    public GridStrategyConfig config() {
        return config;
    }

    public BigDecimal activeSpacing() {
        return activeSpacing;
    }

    public void restore(StrategyRuntimeState runtimeState, BigDecimal activeSpacing) {
        this.runtimeState = runtimeState == null ? StrategyRuntimeState.initial() : runtimeState;
        this.activeSpacing = activeSpacing;
    }

    public void restoreRuntimeState(StrategyRuntimeState runtimeState) {
        this.runtimeState = runtimeState == null ? StrategyRuntimeState.initial() : runtimeState;
    }

    private void addPlaceCommands(List<StrategyCommand> commands, OrderPairPlan pairPlan) {
        if (pairPlan.buyOrder() != null) {
            commands.add(StrategyCommand.place(pairPlan.buyOrder()));
        }
        if (pairPlan.sellOrder() != null) {
            commands.add(StrategyCommand.place(pairPlan.sellOrder()));
        }
    }

    private BigDecimal plannedSpacing(OrderPairPlan pairPlan, BigDecimal referencePrice) {
        if (pairPlan.buyOrder() != null && pairPlan.sellOrder() != null) {
            return pairPlan.sellOrder().price().subtract(pairPlan.buyOrder().price()).divide(new BigDecimal("2"));
        }
        if (pairPlan.buyOrder() != null) {
            return referencePrice.subtract(pairPlan.buyOrder().price()).abs();
        }
        return pairPlan.sellOrder().price().subtract(referencePrice).abs();
    }

    public StrategyStateSnapshot snapshot(Set<String> processedExecutionIds) {
        return new StrategyStateSnapshot(
                strategyLedger.snapshot(),
                runtimeState,
                activeSpacing,
                processedExecutionIds,
                List.of(),
                config
        );
    }

    private boolean shouldBoostReduceSide(
            IndicatorSnapshot indicatorSnapshot,
            MarketSnapshot marketSnapshot,
            StrategyLedgerSnapshot ledgerSnapshot
    ) {
        boolean outsideBand = marketSnapshot.lastPrice().compareTo(indicatorSnapshot.bollUpper4h()) > 0
                || marketSnapshot.lastPrice().compareTo(indicatorSnapshot.bollLower4h()) < 0;
        if (!outsideBand || ledgerSnapshot.netQty().signum() == 0) {
            return false;
        }
        if (ledgerSnapshot.avgEntryPrice().signum() == 0) {
            return false;
        }
        BigDecimal exposure = ledgerSnapshot.avgEntryPrice().multiply(ledgerSnapshot.netQty().abs());
        if (exposure.signum() == 0) {
            return false;
        }
        BigDecimal pnlRate = ledgerSnapshot.floatingPnl().divide(exposure, 8, java.math.RoundingMode.HALF_UP);
        return pnlRate.compareTo(config.deinventoryPnlThresholdRate()) >= 0;
    }

    private String activationReason(MarketSnapshot marketSnapshot, StrategyLedgerSnapshot ledgerSnapshot) {
        if (marketSnapshot.lastPrice().compareTo(marketSnapshot.indicators().bollUpper4h()) > 0) {
            return "trigger-above-upper";
        }
        if (marketSnapshot.lastPrice().compareTo(marketSnapshot.indicators().bollLower4h()) < 0) {
            return "trigger-below-lower";
        }
        return "trigger-unknown";
    }
}
