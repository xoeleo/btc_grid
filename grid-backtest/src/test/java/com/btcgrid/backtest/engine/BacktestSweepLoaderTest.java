package com.btcgrid.backtest.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestSweepLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsCartesianProductFromPropertiesFile() throws Exception {
        Path sweepFile = tempDir.resolve("sweep.properties");
        Files.writeString(sweepFile, """
                base-grid-spacing=80,100
                base-grid-qty=0.01,0.02
                """);

        List<BacktestSweepCase> cases = new BacktestSweepLoader().load(sweepFile, Map.of("csv", "runtime/sample.csv"));

        assertEquals(4, cases.size());
        assertEquals("case-001", cases.get(0).caseId());
        assertTrue(cases.stream().anyMatch(sweepCase -> "80".equals(sweepCase.options().get("base-grid-spacing"))
                && "0.02".equals(sweepCase.options().get("base-grid-qty"))));
    }
}
