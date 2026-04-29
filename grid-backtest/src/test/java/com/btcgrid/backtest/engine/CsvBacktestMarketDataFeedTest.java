package com.btcgrid.backtest.engine;

import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvBacktestMarketDataFeedTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsSnapshotsFromCsvWithHeaderAndIsoTimestamps() throws Exception {
        Path csv = tempDir.resolve("btc-4h.csv");
        Files.writeString(csv, """
                timestamp,open,high,low,close,volume
                2026-04-01T00:00:00Z,70000,70100,69900,70050,100
                2026-04-01T04:00:00Z,70050,70200,70000,70100,120
                2026-04-01T08:00:00Z,70100,70300,70050,70250,140
                """);

        CsvBacktestMarketDataFeed feed = new CsvBacktestMarketDataFeed(
                csv,
                "BTCUSDT",
                new FourHourIndicatorCalculator(),
                2,
                2,
                new BigDecimal("2.0")
        );

        List<BacktestBar> bars = feed.stream().toList();

        assertEquals(2, bars.size());
        assertEquals(new BigDecimal("70100"), bars.get(0).snapshot().lastPrice());
        assertEquals(new BigDecimal("70300"), bars.get(1).candle().high());
        assertEquals("2026-04-01T08:00:00Z", bars.get(1).snapshot().timestamp().toString());
    }

    @Test
    void supportsEpochSecondTimestampsWithoutHeader() throws Exception {
        Path csv = tempDir.resolve("btc-epoch.csv");
        Files.writeString(csv, """
                1775001600,70000,70100,69900,70050,100
                1775016000,70050,70200,70000,70100,120
                """);

        CsvBacktestMarketDataFeed feed = new CsvBacktestMarketDataFeed(
                csv,
                "BTCUSDT",
                new FourHourIndicatorCalculator(),
                2,
                2,
                new BigDecimal("2.0")
        );

        List<BacktestBar> bars = feed.stream().toList();

        assertEquals(1, bars.size());
        assertEquals("2026-04-01T04:00:00Z", bars.get(0).snapshot().timestamp().toString());
    }

    @Test
    void canUseSeparateIndicatorCsv() throws Exception {
        Path execCsv = tempDir.resolve("btc-5m.csv");
        Files.writeString(execCsv, """
                timestamp,open,high,low,close,volume
                2026-04-01T04:05:00Z,70000,70050,69950,70010,10
                2026-04-01T04:10:00Z,70010,70060,70000,70020,10
                2026-04-01T08:05:00Z,70020,70080,70010,70030,10
                """);
        Path indicatorCsv = tempDir.resolve("btc-4h.csv");
        Files.writeString(indicatorCsv, """
                timestamp,open,high,low,close,volume
                2026-04-01T00:00:00Z,70000,70100,69900,70050,100
                2026-04-01T04:00:00Z,70050,70200,70000,70100,120
                2026-04-01T08:00:00Z,70100,70300,70050,70250,140
                """);

        CsvBacktestMarketDataFeed feed = new CsvBacktestMarketDataFeed(
                execCsv,
                indicatorCsv,
                "BTCUSDT",
                new FourHourIndicatorCalculator(),
                2,
                2,
                new BigDecimal("2.0")
        );

        List<BacktestBar> bars = feed.stream().toList();

        assertEquals(3, bars.size());
        assertEquals("2026-04-01T04:05:00Z", bars.get(0).snapshot().timestamp().toString());
        assertEquals(new BigDecimal("70010"), bars.get(0).snapshot().lastPrice());
        assertEquals(new BigDecimal("70075.00000000"), bars.get(0).snapshot().indicators().bollMiddle4h());
    }
}
