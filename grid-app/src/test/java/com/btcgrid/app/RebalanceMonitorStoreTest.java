package com.btcgrid.app;

import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebalanceMonitorStoreTest {
    @Test
    void importsRunningLegacyLongMonitorAsEnabledRuleAndActiveSnapshot() throws Exception {
        var statePath = Files.createTempDirectory("legacy-monitor").resolve("rebalance-state.json");
        var store = new RebalanceMonitorStore(statePath);
        var settings = new RebalanceSettings(true, RebalanceMode.AUTO_LONG_BELOW,
                new BigDecimal("6"), new BigDecimal("0.006"), new BigDecimal("5"),
                ProductCategory.LINEAR, "BTCUSDT", "UNIFIED", "BTC", "USDT",
                List.of(new RebalanceBand(new BigDecimal("100000"), BigDecimal.ONE)),
                new BigDecimal("0.01"), new BigDecimal("0.0016"), new BigDecimal("0.85"),
                new BigDecimal("0.001"), new BigDecimal("5"), new BigDecimal("0.001"),
                new BigDecimal("1"), new BigDecimal("0.001"), new BigDecimal("0.001"),
                Duration.ofMinutes(15), 1, "config.properties", statePath.toString());
        var state = new RebalanceState(true, "BTCUSDT", "GRID_ACTIVE", BigDecimal.ONE,
                new BigDecimal("0.8"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal("80000"), TradeSide.BUY,
                null, null, null, List.of(new BigDecimal("0.8")), Instant.now(), "active");

        store.importLegacyLongBelow(settings, state);

        assertEquals(1, store.rules().size());
        var rule = store.rules().get(0);
        assertTrue(rule.enabled());
        assertEquals("legacy-auto-long-below", rule.id());
        assertEquals(0, rule.triggerPrice().compareTo(new BigDecimal("100000")));
        assertEquals(0, rule.gridQty().compareTo(new BigDecimal("0.006")));
        assertEquals(0, rule.initialSteps().compareTo(new BigDecimal("5")));
        assertEquals(rule, store.active().orElseThrow());
        store.importLegacyLongBelow(settings, state);
        assertEquals(1, store.rules().size());
    }
}
