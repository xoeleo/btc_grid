package com.btcgrid.app;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileStatusStore implements StatusStore {

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileStatusStore(Path file) {
        this.file = file;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    public synchronized void save(RuntimeStatusSnapshot snapshot) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write status file: " + file, e);
        }
    }
}
