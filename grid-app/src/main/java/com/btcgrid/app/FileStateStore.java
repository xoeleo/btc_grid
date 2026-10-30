package com.btcgrid.app;

import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public class FileStateStore implements StateStore {

    private final Path stateFile;
    private final ObjectMapper objectMapper;

    public FileStateStore(Path stateFile) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    public synchronized Optional<StrategyStateSnapshot> load() {
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
    public synchronized void save(StrategyStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path fileName = stateFile.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("stateFile must name a file: " + stateFile);
        }
        Path parent = stateFile.getParent();
        if (parent == null) {
            // A bare relative path has no Path#getParent(), but it is still a
            // valid state-file target in the current directory.
            parent = Path.of(".");
        }
        Path temporaryFile = null;
        try {
            // Serialize before creating or replacing anything. A serialization
            // failure therefore cannot damage the last known-good state file.
            byte[] serialized = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, fileName + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                OutputStream output = Channels.newOutputStream(channel);
                output.write(serialized);
                output.flush();
                channel.force(true);
            }
            // Do not fall back to a delete-then-move or a regular move. If the
            // filesystem cannot provide an atomic replacement, preserving the
            // old state is safer than risking a torn state file.
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            temporaryFile = null;
        } catch (AtomicMoveNotSupportedException e) {
            throw new IllegalStateException("Atomic state-file replacement is not supported: " + stateFile, e);
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("Atomic state-file replacement is not supported: " + stateFile, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save state file: " + stateFile, e);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // Preserve the original save failure. The temporary file
                    // is best-effort cleanup and is never the live state path.
                }
            }
        }
    }
}
