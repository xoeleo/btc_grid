package com.btcgrid.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Durable classification shared by runtime recovery and read-only history views. */
final class RebalanceManualAdjustment {
    static final String ORDER_PREFIX = "RBAL_MANUAL_";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private RebalanceManualAdjustment() { }

    static boolean isManualOrder(String clientOrderId) {
        return clientOrderId != null && clientOrderId.startsWith(ORDER_PREFIX);
    }

    static boolean isManual(FillRecord fill) {
        return fill != null && isManual(fill.clientOrderId(), fill.configJson());
    }

    static boolean isManual(String clientOrderId, String configJson) {
        if (isManualOrder(clientOrderId)) return true;
        if (configJson == null || configJson.isBlank()) return false;
        try {
            var value = JSON.readTree(configJson).path("manualPositionAdjustment");
            return value.isBoolean() && value.booleanValue();
        } catch (Exception ignored) {
            return false;
        }
    }

    static String markMetadata(String configJson) {
        try {
            ObjectNode metadata = configJson == null || configJson.isBlank()
                    ? JSON.createObjectNode() : (ObjectNode) JSON.readTree(configJson);
            metadata.put("manualPositionAdjustment", true);
            return JSON.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist manual adjustment classification", e);
        }
    }
}
