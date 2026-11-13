package com.btcgrid.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.List;

/** Pending timeout intent only; no ledger or exchange state is overwritten. */
final class RebalanceRequoteStore {
    record Plan(long cycleStartMillis, String latestExecutionId, String config,
                BigDecimal spacing, List<String> canceledOrderIds) { }
    private final Path path;
    private final ObjectMapper json = new ObjectMapper();
    RebalanceRequoteStore(String statePath) { path = PathResolver.absolutePath(statePath + ".requote.json"); }
    Plan load() {
        if (!Files.exists(path)) return null;
        try {
            Plan plan = json.readValue(path.toFile(), Plan.class);
            if (plan.spacing() == null || plan.spacing().signum() <= 0 || plan.canceledOrderIds() == null)
                throw new IllegalStateException("Invalid pending requote plan: " + path);
            return plan;
        } catch (IOException e) { throw new IllegalStateException("Cannot read pending requote plan: " + path, e); }
    }
    void save(Plan plan) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), "requote-", ".tmp");
            try {
                json.writeValue(tmp.toFile(), plan);
                Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) { throw new IllegalStateException("Cannot save pending requote plan: " + path, e); }
    }
    void clear() {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { throw new IllegalStateException("Cannot clear pending requote plan: " + path, e); }
    }
}
