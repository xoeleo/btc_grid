package com.btcgrid.app;

import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.StrategyCommandType;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiveCommandExecutor {

    private final BybitTradeGateway tradeGateway;
    private final WorkingOrderRegistry workingOrderRegistry;
    private final RiskGuardService riskGuardService;
    private final EventLogger eventLogger;
    private final AlertService alertService;

    public LiveCommandExecutor(
            BybitTradeGateway tradeGateway,
            WorkingOrderRegistry workingOrderRegistry,
            RiskGuardService riskGuardService,
            EventLogger eventLogger,
            AlertService alertService
    ) {
        this.tradeGateway = tradeGateway;
        this.workingOrderRegistry = workingOrderRegistry;
        this.riskGuardService = riskGuardService;
        this.eventLogger = eventLogger;
        this.alertService = alertService;
    }

    public void execute(List<StrategyCommand> commands, com.btcgrid.ledger.model.StrategyLedgerSnapshot ledgerSnapshot) {
        for (RiskDecision decision : riskGuardService.evaluate(commands, ledgerSnapshot)) {
            StrategyCommand command = decision.command();
            if (!decision.allowed()) {
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
                workingOrderRegistry.trackSubmitted(command.orderIntent(), Instant.now());
                try {
                    tradeGateway.submit(command.orderIntent());
                    logCommand("command.place", command);
                } catch (RuntimeException e) {
                    workingOrderRegistry.markExpired(command.clientOrderId(), Instant.now());
                    logCommandFailure("command.place.failed", command, e);
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
                    logCommandFailure("command.cancel.failed", command, e);
                    alertService.warning("ORDER_CANCEL_FAILED", "Bybit order cancellation failed", Map.of(
                            "clientOrderId", command.clientOrderId(),
                            "symbol", command.symbol(),
                            "message", e.getMessage()
                    ));
                }
            }
        }
    }

    private void logCommand(String eventName, StrategyCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", command.type().name());
        payload.put("symbol", command.symbol());
        payload.put("clientOrderId", command.clientOrderId());
        payload.put("reason", command.reason());
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
