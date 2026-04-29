package com.btcgrid.backtest.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BacktestCliConfigFileTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsOptionsFromConfigFileAndAllowsCliOverride() throws Exception {
        Path configFile = tempDir.resolve("case.properties");
        Files.writeString(configFile, """
                csv=runtime/sample-backtest.csv
                symbol=BTCUSDT
                base-grid-spacing=80
                sweep.case-id=case-001
                """);

        Map<String, String> options = BacktestCli.resolveOptions(new String[] {
                "--config-file", configFile.toString(),
                "--base-grid-spacing", "100",
                "--top-n", "3"
        });

        assertEquals("runtime/sample-backtest.csv", options.get("csv"));
        assertEquals("BTCUSDT", options.get("symbol"));
        assertEquals("100", options.get("base-grid-spacing"));
        assertEquals("3", options.get("top-n"));
        assertFalse(options.containsKey("sweep.case-id"));
    }
}
