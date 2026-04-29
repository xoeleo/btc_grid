package com.btcgrid.app;

import com.btcgrid.common.model.StrategyCommand;

public record RiskDecision(
        boolean allowed,
        StrategyCommand command,
        String reason
) {
    public static RiskDecision allow(StrategyCommand command) {
        return new RiskDecision(true, command, null);
    }

    public static RiskDecision block(StrategyCommand command, String reason) {
        return new RiskDecision(false, command, reason);
    }
}
