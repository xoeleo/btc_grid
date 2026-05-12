package com.btcgrid.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteFillStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsFillsAcrossStoreInstances() {
        Path dbPath = tempDir.resolve("fills.sqlite");
        FillRecord record = new FillRecord(
                Instant.ofEpochMilli(1234567890L),
                "BTCUSDT",
                "SELL",
                new BigDecimal("0.001"),
                new BigDecimal("77000.1"),
                new BigDecimal("-0.001"),
                new BigDecimal("77000.1"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "GRID_TEST",
                "exec-1"
        );

        new SqliteFillStore(dbPath).save(record);
        new SqliteFillStore(dbPath).save(record);

        assertEquals(1, new SqliteFillStore(dbPath).recent(10).size());
        FillRecord saved = new SqliteFillStore(dbPath).recent(10).get(0);
        assertEquals("exec-1", saved.executionId());
        assertEquals(new BigDecimal("77.0001"), saved.value());
        assertEquals(new BigDecimal("-0.001"), saved.cumulativeQty());
    }

    @Test
    void returnsCycleEndRecordsSeparately() {
        Path dbPath = tempDir.resolve("cycles.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(record("SELL", "exec-1"));
        store.save(record("CYCLE_END", "CYCLE_END_exec-1"));

        assertEquals(2, store.recent(10).size());
        assertEquals(1, new SqliteFillStore(dbPath).recentCycles(10).size());
        assertEquals("CYCLE_END_exec-1", new SqliteFillStore(dbPath).recentCycles(10).get(0).executionId());
    }

    @Test
    void backfillsCumulativeProfitFromExistingRows() {
        Path dbPath = tempDir.resolve("profit.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(record("SELL", "sell-1"));
        store.save(new FillRecord(
                Instant.ofEpochMilli(1234567891L),
                "BTCUSDT",
                "BUY",
                new BigDecimal("0.001"),
                new BigDecimal("76000.1"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "GRID_TEST",
                "buy-1"
        ));

        FillRecord latest = new SqliteFillStore(dbPath).recent(10).get(0);
        assertEquals(BigDecimal.ZERO, latest.cumulativeQty());
        assertEquals(new BigDecimal("1"), latest.totalPnlAtFill());
    }

    @Test
    void currentCycleStartsFromLatestInitialOrder() {
        Path dbPath = tempDir.resolve("current-cycle.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(new FillRecord(
                Instant.ofEpochSecond(1777740706L),
                "BTCUSDT",
                "BUY",
                new BigDecimal("0.005"),
                new BigDecimal("78446.8"),
                new BigDecimal("392.234"),
                new BigDecimal("0.2157287"),
                "USDT",
                new BigDecimal("0.013"),
                new BigDecimal("1013.8488"),
                new BigDecimal("0.2157287"),
                new BigDecimal("0.005"),
                new BigDecimal("78446.8"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("5.7438713"),
                "GRID_BTCUSDT_INIT_1777740706987_1f2ae1a5",
                "old-cycle"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1777988676L),
                "SELL",
                new BigDecimal("0.025"),
                new BigDecimal("81363.4"),
                new BigDecimal("1.11874675"),
                "GRID_BTCUSDT_I_mosojshu_42ab5e",
                "new-initial"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1777988872L),
                "SELL",
                new BigDecimal("0.005"),
                new BigDecimal("81493.6"),
                new BigDecimal("0.0814936"),
                "GRID_BTCUSDT_PS_mosojsni_548550",
                "follow-sell"
        ));

        CurrentCycleSummary summary = store.currentCycle(
                "BTCUSDT",
                Instant.ofEpochSecond(1777988976L),
                new BigDecimal("81487.2")
        );

        assertEquals(0, summary.buyCount());
        assertEquals(2, summary.sellCount());
        assertEquals(new BigDecimal("-0.030"), summary.cumulativeQty());
        assertEquals(new BigDecimal("-2441.553"), summary.cumulativeValue());
        assertEquals(new BigDecimal("1.20024035"), summary.cumulativeFee());
        assertEquals(new BigDecimal("81487.2"), summary.marketPrice());
        assertEquals("SHORT", summary.entryDirection());
        assertEquals(new BigDecimal("-4.45524035"), summary.fillPricePnl());
        assertEquals(new BigDecimal("-4.26324035"), summary.markPricePnl());
        assertEquals(300L, summary.durationSeconds());
    }

    @Test
    void returnsFilledClientOrderIds() {
        Path dbPath = tempDir.resolve("filled-ids.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("0.005"),
                new BigDecimal("81258.2"),
                new BigDecimal("0.0812582"),
                "GRID_BTCUSDT_PB_mouqc8u1_189889",
                "exec-filled"
        ));

        assertEquals(
                Set.of("GRID_BTCUSDT_PB_mouqc8u1_189889"),
                store.filledClientOrderIds("BTCUSDT", Set.of(
                        "GRID_BTCUSDT_PB_mouqc8u1_189889",
                        "GRID_BTCUSDT_PS_open"
                ))
        );
    }

    @Test
    void backfillResetsCumulativeFieldsAtInitialOrder() {
        Path dbPath = tempDir.resolve("reset-initial.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(new FillRecord(
                Instant.ofEpochSecond(1000L),
                "BTCUSDT",
                "BUY",
                new BigDecimal("0.005"),
                new BigDecimal("78446.8"),
                new BigDecimal("392.234"),
                new BigDecimal("0.2157287"),
                "USDT",
                new BigDecimal("0.013"),
                new BigDecimal("1013.8488"),
                new BigDecimal("0.2157287"),
                new BigDecimal("0.005"),
                new BigDecimal("78446.8"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "GRID_BTCUSDT_INIT_old",
                "old-cycle"
        ));
        store.save(new FillRecord(
                Instant.ofEpochSecond(2000L),
                "BTCUSDT",
                "SELL",
                new BigDecimal("0.025"),
                new BigDecimal("81363.4"),
                new BigDecimal("2034.085"),
                new BigDecimal("1.11874675"),
                "USDT",
                new BigDecimal("-0.012"),
                new BigDecimal("-1020.2362"),
                new BigDecimal("1.33447545"),
                new BigDecimal("-0.025"),
                new BigDecimal("81363.4"),
                BigDecimal.ZERO,
                new BigDecimal("42.51332455"),
                new BigDecimal("42.51332455"),
                "GRID_BTCUSDT_I_new",
                "new-initial"
        ));

        FillRecord repaired = new SqliteFillStore(dbPath).latestTradingFill("BTCUSDT");

        assertEquals(new BigDecimal("-0.025"), repaired.cumulativeQty());
        assertEquals(new BigDecimal("-2034.085"), repaired.cumulativeValue());
        assertEquals(new BigDecimal("1.11874675"), repaired.cumulativeFee());
        assertEquals(new BigDecimal("-1.11874675"), repaired.floatingPnlAtFill());
        assertEquals(new BigDecimal("-1.11874675"), repaired.totalPnlAtFill());
    }

    @Test
    void returnsHistoricalCycleFillsFromCycleEndExecutionId() {
        Path dbPath = tempDir.resolve("cycle-detail.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("0.005"),
                new BigDecimal("78000"),
                BigDecimal.ZERO,
                "GRID_BTCUSDT_INIT_old",
                "old-initial"
        ));
        store.save(fill(
                Instant.ofEpochSecond(2000L),
                "SELL",
                new BigDecimal("0.025"),
                new BigDecimal("81363.4"),
                new BigDecimal("1.11874675"),
                "GRID_BTCUSDT_I_new",
                "new-initial"
        ));
        store.save(fill(
                Instant.ofEpochSecond(2100L),
                "BUY",
                new BigDecimal("0.005"),
                new BigDecimal("81200"),
                new BigDecimal("0.0812"),
                "GRID_BTCUSDT_PB_new",
                "new-buy"
        ));
        store.save(new FillRecord(
                Instant.ofEpochSecond(2101L),
                "BTCUSDT",
                "CYCLE_END",
                BigDecimal.ZERO,
                new BigDecimal("81200"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "USDT",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "CYCLE_END_GRID_BTCUSDT_PB_new",
                "cycle-end-new"
        ));

        var fills = store.cycleFills("cycle-end-new");

        assertEquals(2, fills.size());
        assertEquals("new-initial", fills.get(0).executionId());
        assertEquals("new-buy", fills.get(1).executionId());
    }

    private FillRecord record(String side, String executionId) {
        return new FillRecord(
                Instant.ofEpochMilli(1234567890L),
                "BTCUSDT",
                side,
                new BigDecimal("0.001"),
                new BigDecimal("77000.1"),
                new BigDecimal("-0.001"),
                new BigDecimal("77000.1"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "GRID_TEST",
                executionId
        );
    }

    private FillRecord fill(
            Instant time,
            String side,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal fee,
            String clientOrderId,
            String executionId
    ) {
        return new FillRecord(
                time,
                "BTCUSDT",
                side,
                qty,
                price,
                qty.multiply(price),
                fee,
                "USDT",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                clientOrderId,
                executionId
        );
    }
}
