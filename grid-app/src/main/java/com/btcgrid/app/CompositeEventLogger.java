package com.btcgrid.app;

import java.util.List;
import java.util.Map;

public class CompositeEventLogger implements EventLogger {

    private final List<EventLogger> delegates;

    public CompositeEventLogger(List<EventLogger> delegates) {
        this.delegates = delegates;
    }

    @Override
    public void info(String eventType, Map<String, Object> payload) {
        delegates.forEach(logger -> logger.info(eventType, payload));
    }

    @Override
    public void warn(String eventType, Map<String, Object> payload) {
        delegates.forEach(logger -> logger.warn(eventType, payload));
    }

    @Override
    public void error(String eventType, Map<String, Object> payload) {
        delegates.forEach(logger -> logger.error(eventType, payload));
    }
}
