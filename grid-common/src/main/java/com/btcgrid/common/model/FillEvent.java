package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record FillEvent(
        String executionId,
        String symbol,
        String clientOrderId,
        TradeSide side,
        BigDecimal fillQty,
        BigDecimal fillPrice,
        Instant fillTime,
        BigDecimal fillValue,
        BigDecimal fee,
        String feeCurrency
) {
    private static final String[] COMMON_QUOTES = {"USDT", "USDC", "USD", "BTC", "ETH"};

    public FillEvent(
            String executionId,
            String symbol,
            String clientOrderId,
            TradeSide side,
            BigDecimal fillQty,
            BigDecimal fillPrice,
            Instant fillTime
    ) {
        this(
                executionId,
                symbol,
                clientOrderId,
                side,
                fillQty,
                fillPrice,
                fillTime,
                value(fillQty, fillPrice),
                BigDecimal.ZERO,
                "USDT"
        );
    }

    private static BigDecimal value(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return BigDecimal.ZERO;
        }
        return qty.multiply(price);
    }

    public BigDecimal netQtyDelta() {
        BigDecimal qty = fillQty == null ? BigDecimal.ZERO : fillQty;
        BigDecimal adjustedQty = qty;
        if (isBaseCurrencyFee()) {
            BigDecimal feeQty = fee == null ? BigDecimal.ZERO : fee;
            adjustedQty = side == TradeSide.BUY
                    ? qty.subtract(feeQty).max(BigDecimal.ZERO)
                    : qty.add(feeQty);
        }
        return side == TradeSide.BUY ? adjustedQty : adjustedQty.negate();
    }

    public boolean isBaseCurrencyFee() {
        String base = baseCoin(symbol);
        return base != null && feeCurrency != null && base.equalsIgnoreCase(feeCurrency.trim());
    }

    public static String baseCoin(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String upper = symbol.trim().toUpperCase();
        for (String quote : COMMON_QUOTES) {
            if (upper.endsWith(quote) && upper.length() > quote.length()) {
                return upper.substring(0, upper.length() - quote.length());
            }
        }
        return upper;
    }
}
