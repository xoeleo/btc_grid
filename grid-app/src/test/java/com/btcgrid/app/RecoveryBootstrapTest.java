package com.btcgrid.app;

import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.model.StrategyRuntimeState;
import com.btcgrid.strategy.model.StrategyStateSnapshot;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import com.btcgrid.common.model.InstrumentSpec;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecoveryBootstrapTest {

    @Test
    void restoresProcessedExecutionsAndTrackedOrdersFromStateFile() throws Exception {
        Path tempFile = Files.createTempFile("grid-state", ".json");
        FileStateStore stateStore = new FileStateStore(tempFile);
        stateStore.save(new StrategyStateSnapshot(
                ledgerSnapshot(),
                StrategyRuntimeState.initial(),
                new BigDecimal("100"),
                Set.of("exec-1", "exec-2"),
                List.of(new WorkingOrder(
                        "BTCUSDT",
                        "BUY-1",
                        "EX-1",
                        TradeSide.BUY,
                        com.btcgrid.common.model.OrderType.LIMIT,
                        new BigDecimal("70000"),
                        new BigDecimal("0.01"),
                        false,
                        com.btcgrid.common.model.OrderStatus.NEW,
                        Instant.now(),
                        Instant.now()
                ))
        ));

        StrategyLedger strategyLedger = new InMemoryStrategyLedger("BTCUSDT");
        GridStrategyEngine engine = engine(strategyLedger);
        RecoveryState recoveryState = new RecoveryBootstrap(stateStore).restore(engine, strategyLedger);

        assertEquals(2, recoveryState.processedExecutionIds().size());
        assertEquals(1, recoveryState.trackedWorkingOrders().size());
        assertEquals(new BigDecimal("0.50"), strategyLedger.snapshot().netQty());
        Files.deleteIfExists(tempFile);
    }

    private StrategyLedgerSnapshot ledgerSnapshot() {
        return new StrategyLedgerSnapshot(
                "BTCUSDT",
                new BigDecimal("0.50"),
                new BigDecimal("68000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("67900"),
                new BigDecimal("68000"),
                TradeSide.BUY,
                com.btcgrid.common.model.BandZone.INSIDE_BAND,
                1,
                0,
                InventoryMode.NORMAL,
                Instant.now()
        );
    }

    private GridStrategyEngine engine(StrategyLedger strategyLedger) {
        GridStrategyConfig config = new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("100"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("0.20"),
                new BigDecimal("2.00"),
                new BigDecimal("2.00"),
                4,
                55,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
        return new GridStrategyEngine(
                config,
                new InstrumentSpec("BTCUSDT", new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.1"), new BigDecimal("0.1")),
                strategyLedger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory())
        );
    }
}
