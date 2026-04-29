package com.btcgrid.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class FileEventLogger implements EventLogger {

    private final Path logFile;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FileEventLogger(Path logFile) {
        this.logFile = logFile;
    }

    @Override
    public void info(String eventType, Map<String, Object> payload) {
        write("INFO", eventType, payload);
    }

    @Override
    public void warn(String eventType, Map<String, Object> payload) {
        write("WARN", eventType, payload);
    }

    @Override
    public void error(String eventType, Map<String, Object> payload) {
        write("ERROR", eventType, payload);
    }

    private synchronized void write(String level, String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ts", Instant.now());
        envelope.put("level", level);
        envelope.put("event", eventType);
        envelope.put("payload", payload);
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                    logFile,
                    objectMapper.writeValueAsString(envelope) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write log file: " + logFile, e);
        }
    }
}
