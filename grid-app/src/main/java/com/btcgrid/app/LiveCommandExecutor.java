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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiveCommandExecutor {

    private static final Duration MAX_SPOT_ACCOUNT_SNAPSHOT_AGE = Duration.ofMinutes(2);

    private final BybitTradeGateway tradeGateway;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final RiskGuardService riskGuardService;
    private final EventLogger eventLogger;
    private final AlertService alertService;
    private final ProductCategory productCategory;
    private final BybitAccountGateway accountGateway;
    private final SpotInventoryDepletedHandler spotInventoryDepletedHandler;
    private volatile Runnable beforeExchangeMutation = () -> {
    };

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
        // A cancel request is only an exchange request.  Until a private-stream
        // update or an explicit REST reconciliation reports a terminal status,
        // the old order may still be live.  Never submit a replacement in the
        // same batch, even when the cancel call itself returned successfully.
        boolean skipPlaceCommands = workingOrderRegistry.hasPendingCancellation() || commands.stream()
                .anyMatch(command -> command != null && command.type() == StrategyCommandType.CANCEL);
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
                    failures.add("spot sell rejected " + command.clientOrderId() + ": inventory is not reconciled");
                    continue;
                }
                command = StrategyCommand.place(orderIntent);
                workingOrderRegistry.trackSubmitted(command.orderIntent(), Instant.now());
                beforeExchangeMutation.run();
                try {
                    BybitOrderSubmission submission = tradeGateway.submit(command.orderIntent());
                    workingOrderRegistry.markAccepted(command.clientOrderId(), submission.orderId(), Instant.now());
                    logCommand("command.place", command, submission);
                } catch (RuntimeException e) {
                    failures.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    // A timeout or a lost response is not proof that Bybit did
                    // not accept the order.  Keep it NEW for reconciliation.
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
                        skipPlaceCommands = true;
                    }
                    alertService.warning("ORDER_PLACE_FAILED", "Bybit order placement failed", Map.of(
                            "clientOrderId", command.clientOrderId(),
                            "symbol", command.symbol(),
                            "message", e.getMessage()
                    ));
                }
            } else if (command.type() == StrategyCommandType.CANCEL) {
                workingOrderRegistry.markCancelPending(command.clientOrderId(), Instant.now());
                beforeExchangeMutation.run();
                try {
                    tradeGateway.cancel(command.symbol(), command.clientOrderId());
                    logCommand("command.cancel", command);
                } catch (RuntimeException e) {
                    failures.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    logCommandFailure("command.cancel.failed", command, e);
                    eventLogger.warn("manual-requote.deferred", Map.of(
                            "clientOrderId", command.clientOrderId(),
                            "symbol", command.symbol(),
                            "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                    ));
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

    /**
     * Submits the operator-requested flatten order outside the ordinary grid
     * order-size guard.  This path is intentionally narrow: it only accepts
     * the dedicated market flatten reason and still caps a spot sell to the
     * real trading-wallet inventory.
     */
    public List<String> executeManualFlatten(OrderIntent orderIntent, StrategyLedgerSnapshot ledgerSnapshot) {
        List<String> failures = new ArrayList<>();
        if (orderIntent == null
                || orderIntent.type() != com.btcgrid.common.model.OrderType.MARKET
                || !"manual-flatten-and-stop".equals(orderIntent.reason())) {
            return List.of("invalid manual flatten order");
        }
        if (productCategory == ProductCategory.SPOT && orderIntent.side() != TradeSide.SELL) {
            return List.of("manual flatten rejected: spot flatten only supports selling positive strategy inventory");
        }
        OrderIntent adjusted = adjustedForSpotInventory(orderIntent, ledgerSnapshot);
        if (adjusted == null) {
            return List.of("manual flatten rejected: strategy inventory is not reconciled");
        }
        StrategyCommand command = StrategyCommand.place(adjusted);
        workingOrderRegistry.trackSubmitted(adjusted, Instant.now());
        beforeExchangeMutation.run();
        try {
            BybitOrderSubmission submission = tradeGateway.submit(adjusted);
            workingOrderRegistry.markAccepted(adjusted.clientOrderId(), submission.orderId(), Instant.now());
            logCommand("command.place.manual-flatten", command, submission);
        } catch (RuntimeException e) {
            failures.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            // Submission failure can be an acknowledgement timeout.  Leave the
            // tracked order NEW so startup/REST reconciliation can resolve it.
            logCommandFailure("command.place.manual-flatten.failed", command, e);
            alertService.warning("MANUAL_FLATTEN_FAILED", "Manual flatten order placement failed", Map.of(
                    "clientOrderId", adjusted.clientOrderId(),
                    "symbol", adjusted.symbol(),
                    "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
        }
        return failures;
    }

    public BigDecimal spotBaseWalletBalance(String symbol) {
        if (productCategory != ProductCategory.SPOT || accountGateway == null) {
            return BigDecimal.ZERO;
        }
        String baseCoin = FillEvent.baseCoin(symbol);
        String quoteCoin = quoteCoin(symbol);
        if (baseCoin == null || quoteCoin == null) {
            return BigDecimal.ZERO;
        }
        AccountAssetSnapshot snapshot = accountGateway.tradingWalletSnapshot(
                "UNIFIED", baseCoin, quoteCoin, ProductCategory.SPOT, symbol
        );
        return snapshot.baseWalletBalance() == null
                ? BigDecimal.ZERO
                : snapshot.baseWalletBalance().max(BigDecimal.ZERO);
    }

    /** Installs the durable-state hook executed after local tracking and before any exchange call. */
    public void setBeforeExchangeMutation(Runnable beforeExchangeMutation) {
        this.beforeExchangeMutation = beforeExchangeMutation == null ? () -> {
        } : beforeExchangeMutation;
    }

    private OrderIntent adjustedForSpotInventory(OrderIntent orderIntent, StrategyLedgerSnapshot ledgerSnapshot) {
        if (orderIntent == null || productCategory != ProductCategory.SPOT || orderIntent.side() != TradeSide.SELL) {
            return orderIntent;
        }
        // A spot sell must be attributable to this strategy.  A missing or
        // non-positive ledger is an uncertainty, never permission to sell the
        // wallet's unrelated inventory.
        if (ledgerSnapshot == null
                || ledgerSnapshot.netQty() == null
                || ledgerSnapshot.netQty().signum() <= 0
                || ledgerSnapshot.symbol() == null
                || !ledgerSnapshot.symbol().equalsIgnoreCase(orderIntent.symbol())
                || accountGateway == null
                || orderIntent.qty() == null
                || orderIntent.qty().signum() <= 0) {
            return null;
        }
        String baseCoin = FillEvent.baseCoin(orderIntent.symbol());
        String quoteCoin = quoteCoin(orderIntent.symbol());
        if (baseCoin == null || quoteCoin == null) {
            return null;
        }
        AccountAssetSnapshot snapshot = accountGateway.tradingWalletSnapshot(
                "UNIFIED",
                baseCoin,
                quoteCoin,
                ProductCategory.SPOT,
                orderIntent.symbol()
        );
        if (snapshot == null || snapshot.baseWalletBalance() == null || snapshot.baseWalletBalance().signum() < 0
                || !isFreshSpotSnapshot(snapshot.timestamp())) {
            return null;
        }
        BigDecimal available = snapshot.baseWalletBalance();
        BigDecimal attributable = ledgerSnapshot.netQty().min(available);
        if (attributable.signum() <= 0 || orderIntent.qty().compareTo(attributable) > 0) {
            eventLogger.warn("spot.sell.inventory.empty", Map.of(
                    "symbol", orderIntent.symbol(),
                    "clientOrderId", orderIntent.clientOrderId(),
                    "requestedQty", orderIntent.qty(),
                    "availableQty", available,
                    "strategyQty", ledgerSnapshot.netQty()
            ));
            alertService.warning("SPOT_SELL_INVENTORY_EMPTY", "Spot sell skipped because base inventory is zero", Map.of(
                    "symbol", orderIntent.symbol(),
                    "clientOrderId", orderIntent.clientOrderId(),
                    "requestedQty", orderIntent.qty(),
                    "availableQty", available,
                    "strategyQty", ledgerSnapshot.netQty()
            ));
            return null;
        }
        // Quantity sizing/step rounding belongs to the runner, where the
        // instrument specification is available.  Do not silently rewrite a
        // requested quantity using BigDecimal scale here.
        return orderIntent;
    }

    private boolean isFreshSpotSnapshot(Instant timestamp) {
        if (timestamp == null) {
            return false;
        }
        Instant now = Instant.now();
        return !timestamp.isAfter(now.plusSeconds(5))
                && !timestamp.isBefore(now.minus(MAX_SPOT_ACCOUNT_SNAPSHOT_AGE));
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
