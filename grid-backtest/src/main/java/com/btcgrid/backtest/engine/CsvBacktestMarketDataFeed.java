package com.btcgrid.backtest.engine;

import com.btcgrid.common.indicator.IndicatorCalculator;
import com.btcgrid.common.model.Candle;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.MarketSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class CsvBacktestMarketDataFeed implements BacktestMarketDataFeed {

    private final Path csvPath;
    private final Path indicatorCsvPath;
    private final String symbol;
    private final IndicatorCalculator indicatorCalculator;
    private final int emaLength;
    private final int bollLength;
    private final BigDecimal bollStdDevMultiplier;

    public CsvBacktestMarketDataFeed(
            Path csvPath,
            String symbol,
            IndicatorCalculator indicatorCalculator,
            int emaLength,
            int bollLength,
            BigDecimal bollStdDevMultiplier
    ) {
        this(csvPath, null, symbol, indicatorCalculator, emaLength, bollLength, bollStdDevMultiplier);
    }

    public CsvBacktestMarketDataFeed(
            Path csvPath,
            Path indicatorCsvPath,
            String symbol,
            IndicatorCalculator indicatorCalculator,
            int emaLength,
            int bollLength,
            BigDecimal bollStdDevMultiplier
    ) {
        this.csvPath = csvPath;
        this.indicatorCsvPath = indicatorCsvPath;
        this.symbol = symbol;
        this.indicatorCalculator = indicatorCalculator;
        this.emaLength = emaLength;
        this.bollLength = bollLength;
        this.bollStdDevMultiplier = bollStdDevMultiplier;
    }

    @Override
    public Stream<BacktestBar> stream() {
        List<Candle> executionCandles = readCandles(csvPath);
        List<Candle> indicatorCandles = indicatorCsvPath == null ? executionCandles : readCandles(indicatorCsvPath);
        List<BacktestBar> bars = new ArrayList<>();
        List<Candle> indicatorWindow = new ArrayList<>();
        int indicatorIndex = 0;
        for (Candle candle : executionCandles) {
            while (indicatorIndex < indicatorCandles.size()
                    && !indicatorCandles.get(indicatorIndex).closeTime().isAfter(candle.closeTime())) {
                indicatorWindow.add(indicatorCandles.get(indicatorIndex));
                indicatorIndex += 1;
            }
            Optional<IndicatorSnapshot> indicators = indicatorCalculator.calculate(
                    symbol,
                    indicatorWindow,
                    emaLength,
                    bollLength,
                    bollStdDevMultiplier
            );
            indicators.ifPresent(indicatorSnapshot -> bars.add(new BacktestBar(
                    new MarketSnapshot(
                            symbol,
                            candle.closeTime(),
                            candle.close(),
                            indicatorSnapshot
                    ),
                    candle
            )));
        }
        return bars.stream();
    }

    private List<Candle> readCandles(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            List<Candle> candles = new ArrayList<>();
            lines.map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .forEach(line -> {
                        if (isHeader(line)) {
                            return;
                        }
                        candles.add(parseCandle(line));
                    });
            return candles;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read csv file: " + path, e);
        }
    }

    private boolean isHeader(String line) {
        String[] parts = line.split(",");
        if (parts.length == 0) {
            return false;
        }
        String first = sanitize(parts[0]).toLowerCase();
        return "timestamp".equals(first)
                || "open_time".equals(first)
                || "time".equals(first);
    }

    private Candle parseCandle(String line) {
        String[] parts = line.split(",");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid csv row, expected at least 5 columns: " + line);
        }
        Instant closeTime = parseTimestamp(parts[0]);
        BigDecimal open = decimal(parts[1]);
        BigDecimal high = decimal(parts[2]);
        BigDecimal low = decimal(parts[3]);
        BigDecimal close = decimal(parts[4]);
        BigDecimal volume = parts.length >= 6 ? decimal(parts[5]) : BigDecimal.ZERO;
        return new Candle(symbol, closeTime, closeTime, open, high, low, close, volume);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(sanitize(value));
    }

    private Instant parseTimestamp(String raw) {
        raw = sanitize(raw);
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
        }
        long numeric = Long.parseLong(raw);
        if (raw.length() <= 10) {
            return Instant.ofEpochSecond(numeric);
        }
        return Instant.ofEpochMilli(numeric);
    }

    private String sanitize(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
