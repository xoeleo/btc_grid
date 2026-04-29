package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InventoryMode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestSweepReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesLeaderboardSortedByNetPnl() throws Exception {
        BacktestSweepOutcome weaker = new BacktestSweepOutcome("case-002", Map.of("base-grid-spacing", "120"), result("1.0"));
        BacktestSweepOutcome stronger = new BacktestSweepOutcome("case-001", Map.of("base-grid-spacing", "80"), result("2.0"));

        BacktestSweepSelection selection = new BacktestSweepSelection(
                BacktestSweepScoreMode.NET_PNL,
                null,
                null,
                null,
                null
        );
        BacktestSweepEvaluator evaluator = new BacktestSweepEvaluator();
        List<BacktestSweepOutcome> ranked = evaluator.rank(List.of(weaker, stronger), selection);
        new BacktestSweepReportWriter().writeSummaryAndLeaderboard(tempDir, ranked, selection, evaluator, 2, 2);

        String leaderboard = Files.readString(tempDir.resolve("leaderboard.csv"));
        String summary = Files.readString(tempDir.resolve("summary.txt"));
        String bestCase = Files.readString(tempDir.resolve("best-case.properties"));
        String topCase = Files.readString(tempDir.resolve("top-cases").resolve("01-case-001.properties"));
        assertTrue(leaderboard.contains("rank,case_id,score_mode,score,net_pnl"));
        assertTrue(leaderboard.indexOf("case-001") < leaderboard.indexOf("case-002"));
        assertTrue(summary.contains("totalCases=2"));
        assertTrue(summary.contains("qualifiedCases=2"));
        assertTrue(summary.contains("Top Results"));
        assertTrue(summary.contains("case-001"));
        assertTrue(bestCase.contains("sweep.case-id=case-001"));
        assertTrue(bestCase.contains("base-grid-spacing=80"));
        assertTrue(topCase.contains("sweep.case-id=case-001"));
    }

    private BacktestResult result(String netPnl) {
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        return new BacktestResult(
                now,
                now.plusSeconds(14400),
                1,
                1,
                0,
                1,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(netPnl),
                new BigDecimal(netPnl),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                GridState.GRID_ACTIVE,
                InventoryMode.NORMAL,
                0,
                List.of(),
                null,
                List.of(),
                List.of()
        );
    }
}
