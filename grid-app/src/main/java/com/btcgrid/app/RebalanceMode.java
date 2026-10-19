package com.btcgrid.app;

public enum RebalanceMode {
    PRICE_TARGET,
    AUTO_CYCLE,
    AUTO_SHORT_CYCLE,
    AUTO_LONG_BELOW;

    public static RebalanceMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return PRICE_TARGET;
        }
        return switch (value.trim().toLowerCase()) {
            case "price_target", "price-target", "price" -> PRICE_TARGET;
            case "auto_cycle", "auto-cycle", "auto" -> AUTO_CYCLE;
            case "auto_short_cycle", "auto-short-cycle", "auto_short", "auto-short" -> AUTO_SHORT_CYCLE;
            case "auto_long_below", "auto-long-below" -> AUTO_LONG_BELOW;
            default -> throw new IllegalArgumentException("Unsupported rebalance mode: " + value);
        };
    }

    public String configValue() {
        return name().toLowerCase();
    }
}
