package com.btcgrid.app;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileRebalanceStateStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileRebalanceStateStore(Path file) {
        this.file = file;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    public synchronized Optional<RebalanceState> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) == 0) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(file.toFile(), RebalanceState.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load rebalance state file: " + file, e);
        }
    }

    public synchronized void save(RebalanceState state) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), state);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save rebalance state file: " + file, e);
        }
    }
}
