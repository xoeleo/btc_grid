package com.btcgrid.common.model;

public record StrategyCommand(
        StrategyCommandType type,
        OrderIntent orderIntent,
        String symbol,
        String clientOrderId,
        String reason
) {
    public static StrategyCommand place(OrderIntent orderIntent) {
        return new StrategyCommand(
                StrategyCommandType.PLACE,
                orderIntent,
                orderIntent.symbol(),
                orderIntent.clientOrderId(),
                orderIntent.reason()
        );
    }

    public static StrategyCommand cancel(String symbol, String clientOrderId, String reason) {
        return new StrategyCommand(
                StrategyCommandType.CANCEL,
                null,
                symbol,
                clientOrderId,
                reason
        );
    }
}
