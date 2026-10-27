package com.btcgrid.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    void duplicateExecutionBackfillsRicherFeeAndRecomputesCycleTotals() {
        Path dbPath = tempDir.resolve("fee-reconcile.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        FillRecord initialWithoutFee = fill(
                Instant.ofEpochSecond(1), "BUY", new BigDecimal("0.015"), new BigDecimal("100"),
                BigDecimal.ZERO, "RBAL_BTCUSDT_I_fee", "initial-fee-exec"
        );
        store.save(initialWithoutFee);
        store.save(fill(
                Instant.ofEpochSecond(2), "SELL", new BigDecimal("0.003"), new BigDecimal("101"),
                new BigDecimal("0.03"), "RBAL_BTCUSDT_PS_fee", "sell-fee-exec"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1), "BUY", new BigDecimal("0.015"), new BigDecimal("100"),
                new BigDecimal("0.40"), "RBAL_BTCUSDT_I_fee", "initial-fee-exec"
        ));

        List<FillRecord> fills = store.currentCycleFills("BTCUSDT");
        assertEquals(2, fills.size());
        assertEquals(0, new BigDecimal("0.40").compareTo(fills.get(0).fee()));
        assertEquals(0, new BigDecimal("0.43").compareTo(fills.get(1).cumulativeFee()));
        assertEquals(0, new BigDecimal("0.43").compareTo(
                store.currentCycle("BTCUSDT", Instant.now(), new BigDecimal("101")).cumulativeTradingFee()));
    }

    @Test
    void restoresBuyAndSellGridRoundingCarryFromCurrentCycleMetadata() {
        Path dbPath = tempDir.resolve("rounding-carry.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fillWithMetadata(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("0.015"),
                new BigDecimal("76000"),
                "GRID_BTCUSDT_INIT_rounding",
                "rounding-initial",
                "{}"
        ));
        store.save(fillWithMetadata(
                Instant.ofEpochSecond(1100L),
                "SELL",
                new BigDecimal("0.004"),
                new BigDecimal("76323.9"),
                "GRID_BTCUSDT_PS_rounding",
                "rounding-sell",
                "{\"buyGridRoundingCarryQty\":\"0.0002\","
                        + "\"sellGridRoundingCarryQty\":\"0.0005\"}"
        ));

        assertEquals(
                new com.btcgrid.strategy.model.QuantityRoundingState(
                        new BigDecimal("0.0002"),
                        new BigDecimal("0.0005")
                ),
                new SqliteFillStore(dbPath).quantityRoundingState("BTCUSDT")
        );
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
    void pagesRecentFillsAndCyclesByOffset() {
        Path dbPath = tempDir.resolve("pages.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(Instant.ofEpochSecond(1), "SELL", new BigDecimal("0.001"), new BigDecimal("77000"), BigDecimal.ZERO, "RBAL_1", "fill-1"));
        store.save(fill(Instant.ofEpochSecond(2), "SELL", new BigDecimal("0.001"), new BigDecimal("77001"), BigDecimal.ZERO, "RBAL_2", "fill-2"));
        store.save(fill(Instant.ofEpochSecond(3), "CYCLE_END", BigDecimal.ZERO, new BigDecimal("77002"), BigDecimal.ZERO, "CYCLE_END_1", "cycle-1"));
        store.save(fill(Instant.ofEpochSecond(4), "CYCLE_END", BigDecimal.ZERO, new BigDecimal("77003"), BigDecimal.ZERO, "CYCLE_END_2", "cycle-2"));

        assertEquals("fill-2", store.recentTrading("BTCUSDT", 1, 0).get(0).executionId());
        assertEquals("fill-1", store.recentTrading("BTCUSDT", 1, 1).get(0).executionId());
        assertEquals("cycle-2", store.recentCycles("BTCUSDT", 1, 0).get(0).executionId());
        assertEquals("cycle-1", store.recentCycles("BTCUSDT", 1, 1).get(0).executionId());
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
    void mergedRecoveryInitialOrderDoesNotSplitTheCurrentCycle() {
        Path dbPath = tempDir.resolve("merged-recovery-cycle.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("0.025"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                "RBAL_BTCUSDT_I_original",
                "original-initial"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1100L),
                "SELL",
                new BigDecimal("0.005"),
                new BigDecimal("101"),
                BigDecimal.ZERO,
                "RBAL_BTCUSDT_PS_follow",
                "follow-fill"
        ));
        store.save(new FillRecord(
                Instant.ofEpochSecond(1200L),
                "BTCUSDT",
                "BUY",
                new BigDecimal("0.025"),
                new BigDecimal("102"),
                new BigDecimal("2.55"),
                BigDecimal.ZERO,
                "USDT",
                new BigDecimal("0.045"),
                new BigDecimal("4.545"),
                BigDecimal.ZERO,
                new BigDecimal("0.045"),
                new BigDecimal("101"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "RBAL_BTCUSDT_I_recovered",
                "recovered-initial",
                "{\"targetRatio\":1,\"mergedIntoRecoveredCycle\":true}"
        ));

        List<FillRecord> fills = store.currentCycleFills("BTCUSDT");
        CurrentCycleFillPage page = store.currentCycleFillsPage("BTCUSDT", 10, 0, null, null);

        assertEquals(3, fills.size());
        assertEquals("original-initial", fills.get(0).executionId());
        assertEquals("recovered-initial", fills.get(2).executionId());
        assertEquals(3, page.total());
        assertEquals(3, page.items().size());
    }

    @Test
    void pagesCurrentCycleFillsInSqliteWithTotalsFiltersAndCrossPageMetrics() {
        Path dbPath = tempDir.resolve("current-cycle-pages.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        for (int index = 0; index < 125; index++) {
            store.save(fill(
                    Instant.ofEpochSecond(1000L + index),
                    index % 2 == 0 ? "BUY" : "SELL",
                    new BigDecimal("0.001"),
                    new BigDecimal(100 + index),
                    BigDecimal.ZERO,
                    index == 0 ? "GRID_BTCUSDT_INIT_page" : "GRID_BTCUSDT_PB_page_" + index,
                    "current-page-" + index
            ));
        }

        CurrentCycleFillPage first = store.currentCycleFillsPage("BTCUSDT", 50, 0, null, null);
        CurrentCycleFillPage third = store.currentCycleFillsPage("BTCUSDT", 50, 100, null, null);
        CurrentCycleFillPage filtered = store.currentCycleFillsPage(
                "BTCUSDT",
                50,
                0,
                new BigDecimal("150"),
                new BigDecimal("159")
        );
        CurrentCycleFillPage exclusiveBucket = store.currentCycleFillsPage(
                "BTCUSDT",
                50,
                0,
                new BigDecimal("150"),
                new BigDecimal("160"),
                true
        );

        assertEquals(125, first.total());
        assertEquals(125, first.tradingCount());
        assertEquals(0, first.fundingCount());
        assertEquals(50, first.items().size());
        assertTrue(first.hasMore());
        assertEquals("current-page-124", first.items().get(0).fill().executionId());
        assertEquals(0, first.items().get(0).cumulativeTurnover().compareTo(new BigDecimal("20.250")));
        assertEquals(new BigDecimal("223"), first.items().get(0).previousTradingPrice());
        assertEquals(25, third.items().size());
        assertFalse(third.hasMore());
        assertEquals("current-page-24", third.items().get(0).fill().executionId());
        assertEquals("current-page-0", third.items().get(24).fill().executionId());
        assertEquals(null, third.items().get(24).previousTradingPrice());
        assertEquals(10, filtered.total());
        assertEquals("current-page-59", filtered.items().get(0).fill().executionId());
        assertEquals("current-page-50", filtered.items().get(9).fill().executionId());
        assertEquals(10, exclusiveBucket.total());
        assertEquals("current-page-59", exclusiveBucket.items().get(0).fill().executionId());
        assertEquals(
                new LiveControlService.RebalancePriceBucket(
                        new BigDecimal("150"),
                        new BigDecimal("160")
                ),
                LiveControlService.rebalancePriceBucket("159.99")
        );
    }

    @Test
    void loadsRebalanceRecoveryCycleBeyondLegacyThousandRowLimit() {
        Path dbPath = tempDir.resolve("long-rebalance-cycle.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        for (int index = 0; index < 1_005; index++) {
            store.save(fill(
                    Instant.ofEpochSecond(10_000L + index),
                    index % 2 == 0 ? "BUY" : "SELL",
                    new BigDecimal("0.001"),
                    new BigDecimal(60_000 + index),
                    BigDecimal.ZERO,
                    index == 0 ? "RBAL_BTCUSDT_I_long_cycle" : "RBAL_BTCUSDT_PB_long_cycle_" + index,
                    "long-cycle-" + index
            ));
        }

        List<FillRecord> fills = store.currentCycleFills("BTCUSDT");

        assertEquals(1_005, fills.size());
        assertEquals("long-cycle-0", fills.get(0).executionId());
        assertEquals("long-cycle-1004", fills.get(1_004).executionId());
    }

    @Test
    void currentCycleAddsPositiveFundingFeeToPnl() {
        Path dbPath = tempDir.resolve("funding-fee.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("1"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                "GRID_BTCUSDT_INIT_new",
                "initial-buy"
        ));
        store.save(new FillRecord(
                Instant.ofEpochSecond(2000L),
                "BTCUSDT",
                "FUNDING_FEE",
                new BigDecimal("1"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                new BigDecimal("0.5"),
                "USDT",
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("0.5"),
                BigDecimal.ONE,
                new BigDecimal("100"),
                BigDecimal.ZERO,
                new BigDecimal("-0.5"),
                new BigDecimal("-0.5"),
                "FUNDING_FEE_BTCUSDT_2000000",
                "funding-1"
        ));

        CurrentCycleSummary summary = store.currentCycle(
                "BTCUSDT",
                Instant.ofEpochSecond(2100L),
                new BigDecimal("100")
        );

        assertEquals(new BigDecimal("1"), summary.cumulativeTradingFee());
        assertEquals(new BigDecimal("0.5"), summary.cumulativeFundingFee());
        assertEquals(new BigDecimal("0.5"), summary.cumulativeFee());
        assertEquals(new BigDecimal("-0.5"), summary.markPricePnl());
        assertEquals(1, store.currentCycleFills("BTCUSDT").stream()
                .filter(fill -> "FUNDING_FEE".equals(fill.side()))
                .count());
    }

    @Test
    void currentCycleSubtractsNegativeFundingFeeFromPnl() {
        Path dbPath = tempDir.resolve("negative-funding-fee.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("1"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                "GRID_BTCUSDT_INIT_new",
                "initial-buy"
        ));
        store.save(new FillRecord(
                Instant.ofEpochSecond(2000L),
                "BTCUSDT",
                "FUNDING_FEE",
                new BigDecimal("1"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                new BigDecimal("-0.5"),
                "USDT",
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("1.5"),
                BigDecimal.ONE,
                new BigDecimal("100"),
                BigDecimal.ZERO,
                new BigDecimal("-1.5"),
                new BigDecimal("-1.5"),
                "FUNDING_FEE_BTCUSDT_2000000",
                "funding-1"
        ));

        CurrentCycleSummary summary = store.currentCycle(
                "BTCUSDT",
                Instant.ofEpochSecond(2100L),
                new BigDecimal("100")
        );

        assertEquals(new BigDecimal("1"), summary.cumulativeTradingFee());
        assertEquals(new BigDecimal("-0.5"), summary.cumulativeFundingFee());
        assertEquals(new BigDecimal("1.5"), summary.cumulativeFee());
        assertEquals(new BigDecimal("-1.5"), summary.markPricePnl());
    }

    @Test
    void currentCycleSummarizesProfitReduceBoostExtraQty() {
        Path dbPath = tempDir.resolve("profit-boost-extra.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("1"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                "GRID_BTCUSDT_INIT_new",
                "initial-buy"
        ));
        store.save(fillWithMetadata(
                Instant.ofEpochSecond(1100L),
                "SELL",
                new BigDecimal("0.5"),
                new BigDecimal("101"),
                "GRID_BTCUSDT_PS_boost_1",
                "boost-sell-1",
                "{\"profitReduceBoostExtraQty\":\"0.1\",\"cumulativeProfitReduceBoostQty\":\"0.1\"}"
        ));
        store.save(fillWithMetadata(
                Instant.ofEpochSecond(1200L),
                "SELL",
                new BigDecimal("0.5"),
                new BigDecimal("102"),
                "GRID_BTCUSDT_PS_boost_2",
                "boost-sell-2",
                "{\"profitReduceBoostExtraQty\":\"0.2\",\"cumulativeProfitReduceBoostQty\":\"0.3\","
                        + "\"permanentProfitReduceExtraQty\":\"0.2\"}"
        ));
        store.save(fillWithMetadata(
                Instant.ofEpochSecond(1300L),
                "SELL",
                new BigDecimal("0.5"),
                new BigDecimal("103"),
                "GRID_BTCUSDT_PS_boost_3",
                "boost-sell-3",
                "{\"profitReduceBoostExtraQty\":\"0.1\",\"cumulativeProfitReduceBoostQty\":\"0.1\"}"
        ));
        store.save(fillWithMetadata(
                Instant.ofEpochSecond(1350L),
                "BUY",
                new BigDecimal("0.5"),
                new BigDecimal("99"),
                "GRID_BTCUSDT_PB_release_1",
                "release-buy-1",
                "{\"lossAddBoostExtraQty\":\"0.1\",\"cumulativeLossAddBoostQty\":\"0.1\","
                        + "\"profitReduceCarryQty\":\"0.05\",\"lossAddCarryQty\":\"0.02\"}"
        ));

        CurrentCycleSummary summary = store.currentCycle(
                "BTCUSDT",
                Instant.ofEpochSecond(1400L),
                new BigDecimal("102")
        );

        assertEquals(new BigDecimal("0.4"), summary.profitReduceBoostExtraQty());
        assertEquals(new BigDecimal("102"), summary.profitReduceBoostAveragePrice());
        assertEquals(new BigDecimal("0.2"), summary.permanentProfitReduceQty());
        assertEquals(new BigDecimal("0.1"), summary.lossAddBoostExtraQty());
        assertEquals(new BigDecimal("99"), summary.lossAddBoostAveragePrice());
        assertEquals(new BigDecimal("0.1"), summary.profitReleaseReserveQty());
        assertEquals(new BigDecimal("0.05"), summary.profitReduceCarryQty());
        assertEquals(new BigDecimal("0.02"), summary.lossAddCarryQty());
        assertEquals(
                new com.btcgrid.strategy.model.ProfitSizingState(
                        new BigDecimal("0.05"),
                        new BigDecimal("0.4"),
                        new BigDecimal("0.02"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.2"),
                        BigDecimal.ZERO
                ),
                store.profitSizingState("BTCUSDT")
        );
    }

    @Test
    void currentCycleSummarizesProfitableReduceQty() {
        Path dbPath = tempDir.resolve("profitable-reduce-qty.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fillWithPnl(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("2"),
                new BigDecimal("70"),
                "GRID_BTCUSDT_INIT_new",
                "initial-buy",
                BigDecimal.ZERO
        ));
        store.save(fillWithPnl(
                Instant.ofEpochSecond(1100L),
                "SELL",
                new BigDecimal("1"),
                new BigDecimal("73.22"),
                "GRID_BTCUSDT_PS_profit",
                "profit-sell",
                new BigDecimal("0.770084")
        ));
        store.save(fillWithPnl(
                Instant.ofEpochSecond(1200L),
                "BUY",
                new BigDecimal("1"),
                new BigDecimal("72"),
                "GRID_BTCUSDT_PB_add",
                "add-buy",
                new BigDecimal("1")
        ));

        CurrentCycleSummary summary = store.currentCycle(
                "BTCUSDT",
                Instant.ofEpochSecond(1300L),
                new BigDecimal("72")
        );

        assertEquals(new BigDecimal("1"), summary.profitableReduceQty());
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

    @Test
    void pagesHistoricalCycleFillsWithoutLoadingTheWholeCycle() {
        Path dbPath = tempDir.resolve("cycle-detail-page.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        for (int index = 0; index < 75; index++) {
            store.save(fill(
                    Instant.ofEpochSecond(1000L + index),
                    index % 2 == 0 ? "BUY" : "SELL",
                    new BigDecimal("0.001"),
                    new BigDecimal("100"),
                    BigDecimal.ZERO,
                    index == 0 ? "GRID_BTCUSDT_INIT_page" : "GRID_BTCUSDT_PB_page_" + index,
                    "page-fill-" + index
            ));
        }
        store.save(new FillRecord(
                Instant.ofEpochSecond(1100L),
                "BTCUSDT",
                "CYCLE_END",
                BigDecimal.ZERO,
                new BigDecimal("100"),
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
                "CYCLE_END_GRID_BTCUSDT_PB_page",
                "cycle-end-page"
        ));

        CycleFillPage first = store.cycleFillsPage("cycle-end-page", 50, 0);
        CycleFillPage second = store.cycleFillsPage("cycle-end-page", 50, 50);

        assertEquals(75, first.total());
        assertEquals(50, first.items().size());
        assertTrue(first.hasMore());
        assertEquals("page-fill-0", first.items().get(0).executionId());
        assertEquals(25, second.items().size());
        assertFalse(second.hasMore());
        assertEquals("page-fill-50", second.items().get(0).executionId());
        assertEquals(0, second.turnoverBefore().compareTo(new BigDecimal("5.000")));
    }

    @Test
    void saveRecomputesCumulativeQtyWhenPartialFillsArriveOutOfTimeOrder() {
        Path dbPath = tempDir.resolve("late-partial-fill.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fillWithStoredCumulative(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("1.24054409"),
                new BigDecimal("80"),
                "GRID_BTCUSDT_INIT_late",
                "initial",
                new BigDecimal("1.24054409")
        ));
        store.save(fillWithStoredCumulative(
                Instant.ofEpochSecond(1002L),
                "SELL",
                new BigDecimal("0.6"),
                new BigDecimal("81"),
                "GRID_BTCUSDT_PS_late",
                "sell-later",
                new BigDecimal("0.64054409")
        ));
        store.save(fillWithStoredCumulative(
                Instant.ofEpochSecond(1001L),
                "SELL",
                new BigDecimal("0.5"),
                new BigDecimal("81"),
                "GRID_BTCUSDT_PS_late",
                "sell-earlier",
                new BigDecimal("0.14054409")
        ));

        var fills = store.currentCycleFills("BTCUSDT");

        assertEquals("initial", fills.get(0).executionId());
        assertEquals("sell-earlier", fills.get(1).executionId());
        assertEquals(new BigDecimal("0.74054409"), fills.get(1).cumulativeQty());
        assertEquals(new BigDecimal("0.74054409"), fills.get(1).netQty());
        assertEquals("sell-later", fills.get(2).executionId());
        assertEquals(new BigDecimal("0.14054409"), fills.get(2).cumulativeQty());
        assertEquals(new BigDecimal("0.14054409"), fills.get(2).netQty());
    }

    @Test
    void pagesCurrentCycleFundingFeesNewestFirstWithCycleCumulativeFunding() {
        Path dbPath = tempDir.resolve("funding-fees-page.sqlite");
        SqliteFillStore store = new SqliteFillStore(dbPath);
        store.save(fill(
                Instant.ofEpochSecond(1000L),
                "BUY",
                new BigDecimal("1"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                "GRID_BTCUSDT_INIT_funding_page",
                "initial"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1100L),
                "FUNDING_FEE",
                BigDecimal.ZERO,
                new BigDecimal("101"),
                new BigDecimal("-0.1"),
                "FUNDING_BTCUSDT_1",
                "funding-1"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1150L),
                "BUY",
                new BigDecimal("0.1"),
                new BigDecimal("99"),
                BigDecimal.ZERO,
                "GRID_BTCUSDT_PB_funding_page",
                "buy-1"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1200L),
                "FUNDING_FEE",
                BigDecimal.ZERO,
                new BigDecimal("102"),
                new BigDecimal("0.2"),
                "FUNDING_BTCUSDT_2",
                "funding-2"
        ));
        store.save(fill(
                Instant.ofEpochSecond(1300L),
                "FUNDING_FEE",
                BigDecimal.ZERO,
                new BigDecimal("103"),
                new BigDecimal("-0.05"),
                "FUNDING_BTCUSDT_3",
                "funding-3"
        ));

        FundingFeePage first = store.currentCycleFundingFeesPage("BTCUSDT", 2, 0);
        FundingFeePage second = store.currentCycleFundingFeesPage("BTCUSDT", 2, 2);

        assertEquals(3, first.total());
        assertTrue(first.hasMore());
        assertEquals("funding-3", first.items().get(0).fill().executionId());
        assertEquals(new BigDecimal("0.05"), first.items().get(0).cumulativeFundingFee());
        assertEquals("funding-2", first.items().get(1).fill().executionId());
        assertEquals(new BigDecimal("0.1"), first.items().get(1).cumulativeFundingFee());
        assertFalse(second.hasMore());
        assertEquals("funding-1", second.items().get(0).fill().executionId());
        assertEquals(new BigDecimal("-0.1"), second.items().get(0).cumulativeFundingFee());
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

    private FillRecord fillWithMetadata(
            Instant time,
            String side,
            BigDecimal qty,
            BigDecimal price,
            String clientOrderId,
            String executionId,
            String metadataJson
    ) {
        return new FillRecord(
                time,
                "BTCUSDT",
                side,
                qty,
                price,
                qty.multiply(price),
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
                clientOrderId,
                executionId,
                metadataJson
        );
    }

    private FillRecord fillWithPnl(
            Instant time,
            String side,
            BigDecimal qty,
            BigDecimal price,
            String clientOrderId,
            String executionId,
            BigDecimal totalPnlAtFill
    ) {
        return new FillRecord(
                time,
                "BTCUSDT",
                side,
                qty,
                price,
                qty.multiply(price),
                BigDecimal.ZERO,
                "USDT",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                totalPnlAtFill,
                totalPnlAtFill,
                clientOrderId,
                executionId
        );
    }

    private FillRecord fillWithStoredCumulative(
            Instant time,
            String side,
            BigDecimal qty,
            BigDecimal price,
            String clientOrderId,
            String executionId,
            BigDecimal cumulativeQty
    ) {
        return new FillRecord(
                time,
                "BTCUSDT",
                side,
                qty,
                price,
                qty.multiply(price),
                BigDecimal.ZERO,
                "USDT",
                cumulativeQty,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                cumulativeQty,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                clientOrderId,
                executionId
        );
    }
}
