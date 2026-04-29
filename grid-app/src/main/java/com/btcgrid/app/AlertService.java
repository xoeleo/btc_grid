package com.btcgrid.app;

import java.util.LinkedHashMap;
import java.util.Map;

public class AlertService {

    private final EventLogger eventLogger;

    public AlertService(EventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    public void critical(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("severity", "CRITICAL");
        payload.put("details", details);
        eventLogger.error("alert", payload);
    }

    public void warning(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("severity", "WARNING");
        payload.put("details", details);
        eventLogger.warn("alert", payload);
    }
}
