package com.btcgrid.app;

import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileStateStore implements StateStore {

    private final Path stateFile;
    private final ObjectMapper objectMapper;

    public FileStateStore(Path stateFile) {
        this.stateFile = stateFile;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    public Optional<StrategyStateSnapshot> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(stateFile.toFile(), StrategyStateSnapshot.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load state file: " + stateFile, e);
        }
    }

    @Override
    public void save(StrategyStateSnapshot snapshot) {
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save state file: " + stateFile, e);
        }
    }
}
