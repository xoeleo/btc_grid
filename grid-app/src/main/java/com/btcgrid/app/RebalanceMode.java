package com.btcgrid.app;

public enum RebalanceMode {
    PRICE_TARGET,
    AUTO_CYCLE;

    public static RebalanceMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return PRICE_TARGET;
        }
        return switch (value.trim().toLowerCase()) {
            case "price_target", "price-target", "price" -> PRICE_TARGET;
            case "auto_cycle", "auto-cycle", "auto" -> AUTO_CYCLE;
            default -> throw new IllegalArgumentException("Unsupported rebalance mode: " + value);
        };
    }

    public String configValue() {
        return name().toLowerCase();
    }
}
