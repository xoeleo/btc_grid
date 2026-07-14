package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitAccountGateway;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.StrategyCommandType;
import com.btcgrid.bybit.adapter.BybitOrderSubmission;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiveCommandExecutor {

    private final BybitTradeGateway tradeGateway;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final RiskGuardService riskGuardService;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final ProductCategory productCategory;
    private final BybitAccountGateway accountGateway;
    private final SpotInventoryDepletedHandler spotInventoryDepletedHandler;

    @FunctionalInterface
    public interface SpotInventoryDepletedHandler {
        void onSpotInventoryDepleted(String symbol, String clientOrderId, StrategyLedgerSnapshot ledgerSnapshot);
    }

    public LiveCommandExecutor(
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            RiskGuardService riskGuardService,
            EventLogger eventLogger,
            AlertService alertService
    ) {
        this(tradeGateway, workingOrderRegistry, riskGuardService, eventLogger, alertService, null, null, null);
    }

    public LiveCommandExecutor(
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            RiskGuardService riskGuardService,
            EventLogger eventLogger,
            AlertService alertService,
            ProductCategory productCategory,
            BybitAccountGateway accountGateway
    ) {
        this(tradeGateway, workingOrderRegistry, riskGuardService, eventLogger, alertService, productCategory, accountGateway, null);
    }

    public LiveCommandExecutor(
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            RiskGuardService riskGuardService,
            EventLogger eventLogger,
            AlertService alertService,
            ProductCategory productCategory,
            BybitAccountGateway accountGateway,
            SpotInventoryDepletedHandler spotInventoryDepletedHandler
    ) {
        this.tradeGateway = tradeGateway;
        this.workingOrderRegistry = workingOrderRegistry;
        this.riskGuardService = riskGuardService;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
        this.productCategory = productCategory;
        this.accountGateway = accountGateway;
        this.spotInventoryDepletedHandler = spotInventoryDepletedHandler;
    }

    public List<String> execute(List<StrategyCommand> commands, StrategyLedgerSnapshot ledgerSnapshot) {
        List<String> failures = new ArrayList<>();
        boolean skipPlaceCommands = false;
        for (RiskDecision decision : riskGuardService.evaluate(commands, ledgerSnapshot)) {
            StrategyCommand command = decision.command();
            if (skipPlaceCommands && command.type() == StrategyCommandType.PLACE) {
                failures.add("skipped " + command.clientOrderId() + ": waiting for cancel reconciliation");
                eventLogger.warn("command.place.skipped", Map.of(
                        "clientOrderId", command.clientOrderId(),
                        "symbol", command.symbol(),
                        "reason", command.reason(),
                        "message", "waiting for cancel reconciliation"
                ));
                continue;
            }
            if (!decision.allowed()) {
                failures.add("risk blocked " + command.clientOrderId() + ": " + decision.reason());
                Map<String, Object> details = Map.of(
                        "reason", decision.reason(),
                        "commandType", command.type().name(),
                        "clientOrderId", command.clientOrderId(),
                        "symbol", command.symbol()
                );
                eventLogger.warn("risk.blocked", details);
                alertService.warning("RISK_BLOCKED", "Strategy command blocked by risk guard", details);
                continue;
            }

            if (command.type() == StrategyCommandType.PLACE) {
                OrderIntent orderIntent = adjustedForSpotInventory(command.orderIntent(), ledgerSnapshot);
                if (orderIntent == null) {
                    workingOrderRegistry.markExpired(command.clientOrderId(), Instant.now());
                    continue;
                }
                command = StrategyCommand.place(orderIntent);
                workingOrderRegistry.trackSubmitted(command.orderIntent(), Instant.now());
                try {
                    BybitOrderSubmission submission = tradeGateway.submit(command.orderIntent());
                    workingOrderRegistry.markAccepted(command.clientOrderId(), submission.orderId(), Instant.now());
                    logCommand("command.place", command, submission);
                } catch (RuntimeException e) {
                    failures.add(e.getMessage());
                    workingOrderRegistry.markExpired(command.clientOrderId(), Instant.now());
                    logCommandFailure("command.place.failed", command, e);
                    if (isSpotSellInsufficientBalance(command, e)) {
                        eventLogger.warn("spot.sell.inventory.insufficient", Map.of(
                                "clientOrderId", command.clientOrderId(),
                                "symbol", command.symbol(),
                                "message", e.getMessage()
                        ));
                        alertService.warning("SPOT_SELL_INVENTORY_INSUFFICIENT", "Spot sell rejected because base inventory is insufficient", Map.of(
                                "clientOrderId", command.clientOrderId(),
                                "symbol", command.symbol(),
                                "message", e.getMessage()
                        ));
                        if (spotInventoryDepletedHandler != null) {
                            spotInventoryDepletedHandler.onSpotInventoryDepleted(command.symbol(), command.clientOrderId(), ledgerSnapshot);
                        }
                        skipPlaceCommands = true;
                    }
                    alertService.warning("ORDER_PLACE_FAILED", "Bybit order placement failed", Map.of(
                            "clientOrderId", command.clientOrderId(),
                            "symbol", command.symbol(),
                            "message", e.getMessage()
                    ));
                }
            } else if (command.type() == StrategyCommandType.CANCEL) {
                workingOrderRegistry.markCanceled(command.clientOrderId(), Instant.now());
                try {
                    tradeGateway.cancel(command.symbol(), command.clientOrderId());
                    logCommand("command.cancel", command);
                } catch (RuntimeException e) {
                    failures.add(e.getMessage());
                    logCommandFailure("command.cancel.failed", command, e);
                    if (isManualRequoteUncertainCancel(command, e)) {
                        skipPlaceCommands = true;
                        eventLogger.warn("manual-requote.deferred", Map.of(
                                "clientOrderId", command.clientOrderId(),
                                "symbol", command.symbol(),
                                "message", e.getMessage()
                        ));
                    }
                    alertService.warning("ORDER_CANCEL_FAILED", "Bybit order cancellation failed", Map.of(
                            "clientOrderId", command.clientOrderId(),
                            "symbol", command.symbol(),
                            "message", e.getMessage()
                    ));
                }
            }
        }
        return failures;
    }

    private OrderIntent adjustedForSpotInventory(OrderIntent orderIntent, StrategyLedgerSnapshot ledgerSnapshot) {
        if (orderIntent == null
                || productCategory != ProductCategory.SPOT
                || accountGateway == null
                || orderIntent.side() != TradeSide.SELL) {
            return orderIntent;
        }
        String baseCoin = FillEvent.baseCoin(orderIntent.symbol());
        String quoteCoin = quoteCoin(orderIntent.symbol());
        if (baseCoin == null || quoteCoin == null) {
            return orderIntent;
        }
        AccountAssetSnapshot snapshot = accountGateway.tradingWalletSnapshot(
                "UNIFIED",
                baseCoin,
                quoteCoin,
                ProductCategory.SPOT,
                orderIntent.symbol()
        );
        BigDecimal available = snapshot.baseWalletBalance() == null ? BigDecimal.ZERO : snapshot.baseWalletBalance().max(BigDecimal.ZERO);
        if (available.signum() <= 0) {
            eventLogger.warn("spot.sell.inventory.empty", Map.of(
                    "symbol", orderIntent.symbol(),
                    "clientOrderId", orderIntent.clientOrderId(),
                    "requestedQty", orderIntent.qty(),
                    "availableQty", available
            ));
            alertService.warning("SPOT_SELL_INVENTORY_EMPTY", "Spot sell skipped because base inventory is zero", Map.of(
                    "symbol", orderIntent.symbol(),
                    "clientOrderId", orderIntent.clientOrderId(),
                    "requestedQty", orderIntent.qty()
            ));
            if (spotInventoryDepletedHandler != null) {
                spotInventoryDepletedHandler.onSpotInventoryDepleted(orderIntent.symbol(), orderIntent.clientOrderId(), ledgerSnapshot);
            }
            return null;
        }
        if (orderIntent.qty() == null || orderIntent.qty().compareTo(available) <= 0) {
            return orderIntent;
        }
        BigDecimal cappedQty = capToOrderScale(available, orderIntent.qty());
        if (cappedQty.signum() <= 0) {
            eventLogger.warn("spot.sell.inventory.empty", Map.of(
                    "symbol", orderIntent.symbol(),
                    "clientOrderId", orderIntent.clientOrderId(),
                    "requestedQty", orderIntent.qty(),
                    "availableQty", available
            ));
            alertService.warning("SPOT_SELL_INVENTORY_EMPTY", "Spot sell skipped because base inventory is below order precision", Map.of(
                    "symbol", orderIntent.symbol(),
                    "clientOrderId", orderIntent.clientOrderId(),
                    "requestedQty", orderIntent.qty(),
                    "availableQty", available
            ));
            if (spotInventoryDepletedHandler != null) {
                spotInventoryDepletedHandler.onSpotInventoryDepleted(orderIntent.symbol(), orderIntent.clientOrderId(), ledgerSnapshot);
            }
            return null;
        }
        eventLogger.warn("spot.sell.qty.capped", Map.of(
                "symbol", orderIntent.symbol(),
                "clientOrderId", orderIntent.clientOrderId(),
                "requestedQty", orderIntent.qty(),
                "availableQty", available,
                "cappedQty", cappedQty
        ));
        return new OrderIntent(
                orderIntent.symbol(),
                orderIntent.clientOrderId(),
                orderIntent.side(),
                orderIntent.type(),
                cappedQty,
                orderIntent.price(),
                orderIntent.reduceOnly(),
                orderIntent.reason()
        );
    }

    private BigDecimal capToOrderScale(BigDecimal available, BigDecimal requestedQty) {
        if (available == null || requestedQty == null) {
            return BigDecimal.ZERO;
        }
        return available.max(BigDecimal.ZERO).setScale(Math.max(0, requestedQty.scale()), RoundingMode.DOWN);
    }

    private static String quoteCoin(String symbol) {
        if (symbol == null) {
            return null;
        }
        String upper = symbol.trim().toUpperCase();
        if (upper.endsWith("USDT")) {
            return "USDT";
        }
        if (upper.endsWith("USDC")) {
            return "USDC";
        }
        return null;
    }

    private boolean isManualRequoteUncertainCancel(StrategyCommand command, RuntimeException exception) {
        String reason = command.reason() == null ? "" : command.reason();
        if (!reason.contains("manual-requote")) {
            return false;
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        return message.contains("110001")
                || message.contains("order not exists")
                || message.contains("too late to cancel");
    }

    private boolean isSpotSellInsufficientBalance(StrategyCommand command, RuntimeException exception) {
        if (productCategory != ProductCategory.SPOT || command.orderIntent() == null || command.orderIntent().side() != TradeSide.SELL) {
            return false;
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        return message.contains("170131") || message.contains("insufficient balance");
    }

    private void logCommand(String eventName, StrategyCommand command) {
        logCommand(eventName, command, null);
    }

    private void logCommand(String eventName, StrategyCommand command, BybitOrderSubmission submission) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", command.type().name());
        payload.put("symbol", command.symbol());
        payload.put("clientOrderId", command.clientOrderId());
        payload.put("reason", command.reason());
        if (submission != null) {
            payload.put("exchangeOrderId", submission.orderId());
            payload.put("orderLinkId", submission.orderLinkId());
        }
        if (command.orderIntent() != null) {
            payload.put("side", command.orderIntent().side().name());
            payload.put("qty", command.orderIntent().qty());
            payload.put("price", command.orderIntent().price());
            payload.put("reduceOnly", command.orderIntent().reduceOnly());
        }
        eventLogger.info(eventName, payload);
    }

    private void logCommandFailure(String eventName, StrategyCommand command, RuntimeException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", command.type().name());
        payload.put("symbol", command.symbol());
        payload.put("clientOrderId", command.clientOrderId());
        payload.put("reason", command.reason());
        payload.put("message", exception.getMessage());
        eventLogger.error(eventName, payload);
    }
}
