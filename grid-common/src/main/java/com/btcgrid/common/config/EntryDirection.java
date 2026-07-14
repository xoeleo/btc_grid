package com.btcgrid.common.config;

public enum EntryDirection {
    BOTH,
    LONG_ONLY,
    SHORT_ONLY,
    SPOT_LONG_ONLY;

    public static EntryDirection fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return BOTH;
        }
        return switch (value.trim().toLowerCase()) {
            case "both", "all", "long_short", "long-short" -> BOTH;
            case "long", "long_only", "long-only", "buy" -> LONG_ONLY;
            case "short", "short_only", "short-only", "sell" -> SHORT_ONLY;
            case "spot_long", "spot-long", "spot_long_only", "spot-long-only" -> SPOT_LONG_ONLY;
            default -> throw new IllegalArgumentException("Unsupported entry direction: " + value);
        };
    }

    public boolean allowsLongEntry() {
        return this == BOTH || this == LONG_ONLY || this == SPOT_LONG_ONLY;
    }

    public boolean allowsShortEntry() {
        return this == BOTH || this == SHORT_ONLY;
    }

    public boolean isSpotLongOnly() {
        return this == SPOT_LONG_ONLY;
    }
}
