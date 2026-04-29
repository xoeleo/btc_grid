package com.btcgrid.app;

import java.util.Map;

public interface EventLogger {

    void info(String eventType, Map<String, Object> payload);

    void warn(String eventType, Map<String, Object> payload);

    void error(String eventType, Map<String, Object> payload);
}
