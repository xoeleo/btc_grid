package com.btcgrid.app;

import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.strategy.model.GridSafetyState;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripAndReplacementPreserveACompleteSnapshot() {
        Path stateFile = tempDir.resolve("runtime").resolve("grid-state.json");
        FileStateStore store = new FileStateStore(stateFile);
        StrategyStateSnapshot first = snapshot("exec-1");
        StrategyStateSnapshot second = snapshot("exec-2");

        store.save(first);
        assertEquals(first, store.load().orElseThrow());
        store.save(second);

        assertEquals(second, store.load().orElseThrow());
    }

    @Test
    void missingStateFileLoadsAsEmpty() {
        FileStateStore store = new FileStateStore(tempDir.resolve("missing.json"));

        assertEquals(java.util.Optional.empty(), store.load());
    }

    @Test
    void corruptedStateFileFailsClosed() throws Exception {
        Path stateFile = tempDir.resolve("corrupted.json");
        Files.writeString(stateFile, "{not valid json", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FileStateStore(stateFile).load());

        assertEquals("Failed to load state file: " + stateFile, failure.getMessage());
    }

    @Test
    void failedSaveLeavesExistingStateFileUntouched() throws Exception {
        Path stateFile = tempDir.resolve("state.json");
        FileStateStore store = new FileStateStore(stateFile);
        store.save(snapshot("before"));
        byte[] before = Files.readAllBytes(stateFile);

        assertThrows(NullPointerException.class, () -> store.save(null));

        assertArrayEquals(before, Files.readAllBytes(stateFile));
        assertEquals(snapshot("before"), store.load().orElseThrow());
    }

    @Test
    void safetyStateAndPendingCancellationEvidenceRoundTrip() {
        Path stateFile = tempDir.resolve("safety.json");
        FileStateStore store = new FileStateStore(stateFile);
        WorkingOrder pending = new WorkingOrder(
                "SOLUSDT", "GRID_SOLUSDT_PENDING", "exchange-1", TradeSide.BUY,
                OrderType.LIMIT, new BigDecimal("85"), BigDecimal.ONE, false,
                OrderStatus.NEW, Instant.now().minusSeconds(5), Instant.now(),
                "manual-stop", BigDecimal.ZERO, true
        );
        GridSafetyState safety = new GridSafetyState(
                true,
                "MANUAL_FLATTEN_SOLUSDT_1",
                Instant.parse("2026-01-01T00:00:00Z"),
                true,
                java.util.Map.of("exec-1", BigDecimal.ONE)
        );
        StrategyStateSnapshot snapshot = new StrategyStateSnapshot(
                null, null, null, Set.of("exec-1"), List.of(pending), null, safety
        );

        store.save(snapshot);

        StrategyStateSnapshot restored = store.load().orElseThrow();
        assertEquals(safety, restored.safetyState());
        assertEquals(true, restored.trackedWorkingOrders().get(0).cancellationRequested());
    }

    @Test
    void legacyStateWithoutSafetyStateRemainsReadable() throws Exception {
        Path stateFile = tempDir.resolve("legacy.json");
        Files.writeString(stateFile, "{\"ledgerSnapshot\":null,\"runtimeState\":null,\"activeSpacing\":null,"
                + "\"processedExecutionIds\":[],\"trackedWorkingOrders\":[],\"cycleConfig\":null}",
                StandardCharsets.UTF_8);

        StrategyStateSnapshot restored = new FileStateStore(stateFile).load().orElseThrow();

        assertEquals(null, restored.safetyState());
        assertEquals(List.of(), restored.trackedWorkingOrders());
    }

    private StrategyStateSnapshot snapshot(String executionId) {
        return new StrategyStateSnapshot(
                null,
                null,
                null,
                Set.of(executionId),
                List.of(),
                null
        );
    }
}
