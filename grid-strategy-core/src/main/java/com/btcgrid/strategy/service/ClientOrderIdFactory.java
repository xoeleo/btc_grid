package com.btcgrid.strategy.service;

import java.time.Instant;
import java.util.UUID;

public class ClientOrderIdFactory {

    public String next(String symbol, String reason) {
        return "GRID_" + symbol + "_" + shortReason(reason) + "_"
                + Long.toString(Instant.now().toEpochMilli(), 36) + "_"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private String shortReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "O";
        }
        return switch (reason.trim().toUpperCase()) {
            case "INIT" -> "I";
            case "PAIR_BUY" -> "PB";
            case "PAIR_SELL" -> "PS";
            case "DEINV" -> "D";
            default -> {
                String cleaned = reason.replaceAll("[^A-Za-z0-9]", "");
                yield cleaned.isBlank() ? "O" : cleaned.substring(0, Math.min(4, cleaned.length()));
            }
        };
    }
}
