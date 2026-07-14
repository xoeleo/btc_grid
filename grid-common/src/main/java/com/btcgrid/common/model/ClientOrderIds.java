package com.btcgrid.common.model;

public final class ClientOrderIds {

    private ClientOrderIds() {
    }

    public static boolean isInitialPositionOrder(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return false;
        }
        if (clientOrderId.contains("_INIT_")) {
            return true;
        }
        String[] parts = clientOrderId.split("_", 4);
        return parts.length >= 3 && ("GRID".equals(parts[0]) || "RBAL".equals(parts[0])) && "I".equals(parts[2]);
    }
}
