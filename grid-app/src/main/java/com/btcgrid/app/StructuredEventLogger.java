package com.btcgrid.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class StructuredEventLogger implements EventLogger {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void info(String eventType, Map<String, Object> payload) {
        log("INFO", eventType, payload);
    }

    @Override
    public void warn(String eventType, Map<String, Object> payload) {
        log("WARN", eventType, payload);
    }

    @Override
    public void error(String eventType, Map<String, Object> payload) {
        log("ERROR", eventType, payload);
    }

    private void log(String level, String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ts", Instant.now());
        envelope.put("level", level);
        envelope.put("event", eventType);
        envelope.put("payload", payload);
        try {
            System.out.println(objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            System.out.println("{\"level\":\"" + level + "\",\"event\":\"" + eventType + "\",\"payload\":\"serialization-error\"}");
        }
    }
}
