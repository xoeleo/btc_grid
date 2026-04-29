package com.btcgrid.backtest.engine;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class BacktestCli {

    private BacktestCli() {
    }

    public static void main(String[] args) {
        try {
            Map<String, String> options = resolveOptions(args);
            if (options.containsKey("help") || !options.containsKey("csv")) {
                printUsage();
                return;
            }

            GridStrategyConfig config = strategyConfig(options);
            InstrumentSpec instrumentSpec = instrumentSpec(options, config.symbol());
            BacktestMarketDataFeed marketDataFeed = new CsvBacktestMarketDataFeed(
                    Path.of(options.get("csv")),
                    optionalPath(options.get("indicator-csv")),
                    config.symbol(),
                    new FourHourIndicatorCalculator(),
                    config.emaLength4h(),
                    config.bollLength4h(),
                    config.bollStdDevMultiplier()
            );
            InMemoryExecutionSimulator executionSimulator = new InMemoryExecutionSimulator(executionConfig(options));
            StrategyLedger strategyLedger = new InMemoryStrategyLedger(config.symbol());
            GridStrategyEngine strategyEngine = new GridStrategyEngine(
                    config,
                    instrumentSpec,
                    strategyLedger,
                    new GridTriggerEvaluator(),
                    new GridOrderPlanner(new ClientOrderIdFactory())
            );

            BacktestResult result = new BacktestRunner(
                    marketDataFeed,
                    executionSimulator,
                    strategyEngine,
                    strategyLedger
            ).run();

            printSummary(config, instrumentSpec, result);
            if (options.containsKey("output-dir")) {
                Path outputDir = Path.of(options.get("output-dir"));
                new BacktestReportWriter().write(outputDir, config, instrumentSpec, result);
                System.out.println("reportDir=" + outputDir.toAbsolutePath());
            }
        } catch (RuntimeException e) {
            System.err.println("Backtest failed: " + e.getMessage());
            System.err.println("Use --help to see required arguments and defaults.");
            throw e;
        }
    }

    static Map<String, String> resolveOptions(String[] args) {
        Map<String, String> cliOptions = parseArgs(args);
        String configFile = cliOptions.get("config-file");
        if (configFile == null || configFile.isBlank()) {
            return cliOptions;
        }

        Map<String, String> merged = loadPropertiesFile(Path.of(configFile));
        merged.putAll(cliOptions);
        merged.remove("sweep.case-id");
        return merged;
    }

    static void printSummary(GridStrategyConfig config, InstrumentSpec instrumentSpec, BacktestResult result) {
        System.out.println("Backtest Summary");
        System.out.println("symbol=" + config.symbol());
        System.out.println("category=" + config.category().name());
        System.out.println("csv-run-from=" + result.startTime());
        System.out.println("csv-run-to=" + result.endTime());
        System.out.println("snapshots=" + result.snapshotsProcessed());
        System.out.println("ordersPlaced=" + result.ordersPlaced());
        System.out.println("ordersCanceled=" + result.ordersCanceled());
        System.out.println("fills=" + result.fillsProcessed());
        System.out.println("finalState=" + result.finalState());
        System.out.println("inventoryMode=" + result.inventoryMode());
        System.out.println("openOrders=" + result.openOrders());
        System.out.println("finalNetQty=" + result.finalNetQty());
        System.out.println("avgEntryPrice=" + result.avgEntryPrice());
        System.out.println("realizedPnl=" + result.realizedPnl());
        System.out.println("floatingPnl=" + result.floatingPnl());
        System.out.println("totalFees=" + result.totalFees());
        System.out.println("grossPnl=" + result.totalPnl());
        System.out.println("netPnl=" + result.netPnl());
        System.out.println("maxDrawdown=" + result.maxDrawdown());
        System.out.println("grossRealizedProfit=" + result.grossRealizedProfit());
        System.out.println("grossRealizedLoss=" + result.grossRealizedLoss());
        System.out.println("winningCloseCount=" + result.winningCloseCount());
        System.out.println("losingCloseCount=" + result.losingCloseCount());
        System.out.println("breakevenCloseCount=" + result.breakevenCloseCount());
        System.out.println("winRate=" + result.winRate());
        System.out.println("profitFactor=" + result.profitFactor());
        System.out.println("peakInventoryQty=" + result.peakInventoryQty());
        System.out.println("feeToGrossPnlRatio=" + result.feeToGrossPnlRatio());
        System.out.println("qtyStep=" + instrumentSpec.qtyStep());
        System.out.println("priceTick=" + instrumentSpec.priceTick());
    }

    static GridStrategyConfig strategyConfig(Map<String, String> options) {
        return new GridStrategyConfig(
                ProductCategory.fromConfig(options.getOrDefault("category", "linear")),
                options.getOrDefault("symbol", "BTCUSDT"),
                EntryDirection.fromConfig(options.getOrDefault("entry-direction", "both")),
                decimal(options, "base-grid-spacing", "0.001"),
                decimal(options, "grid-requote-shrink-factor", "0.85"),
                decimal(options, "base-grid-qty", "0.01"),
                decimal(options, "max-initial-steps", "5"),
                decimal(options, "max-order-qty", "0.20"),
                decimal(options, "max-inventory-qty", "0.15"),
                decimal(options, "max-directional-inventory-qty", "0.15"),
                integer(options, "max-consecutive-same-side-fills", "4"),
                integer(options, "ema-length-4h", "20"),
                integer(options, "boll-length-4h", "20"),
                decimal(options, "boll-std-dev-multiplier", "2.0"),
                decimal(options, "deinventory-pnl-threshold-rate", "0.01"),
                decimal(options, "deinventory-boost-slope", "10"),
                decimal(options, "deinventory-boost-cap", "0.30"),
                Boolean.parseBoolean(options.getOrDefault("classic-reduce-sizing", "false")),
                Duration.ofMinutes(integer(options, "pair-order-timeout-minutes", "0")),
                Duration.ofMinutes(integer(options, "trigger-cooldown-minutes", "5"))
        );
    }

    static InstrumentSpec instrumentSpec(Map<String, String> options, String symbol) {
        return new InstrumentSpec(
                symbol,
                decimal(options, "qty-step", "0.001"),
                decimal(options, "min-order-qty", "0.001"),
                decimal(options, "price-tick", "0.1"),
                decimal(options, "min-price", "0.1")
        );
    }

    static BacktestExecutionConfig executionConfig(Map<String, String> options) {
        return new BacktestExecutionConfig(
                decimal(options, "maker-fee-rate", "0"),
                decimal(options, "taker-fee-rate", "0"),
                decimal(options, "market-slippage-bps", "0"),
                decimal(options, "limit-fill-ratio-per-tick", "1"),
                decimal(options, "market-fill-ratio-per-tick", "1"),
                decimal(options, "min-fill-qty", "0.00000001"),
                decimal(options, "max-volume-participation-rate", "1"),
                decimal(options, "min-touch-fill-ratio", "1")
        );
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Invalid argument: " + arg);
            }
            String keyValue = arg.substring(2);
            int separator = keyValue.indexOf('=');
            if (separator >= 0) {
                options.put(keyValue.substring(0, separator), keyValue.substring(separator + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for argument: " + arg);
            }
            options.put(keyValue, args[++i]);
        }
        return options;
    }

    static BigDecimal decimal(Map<String, String> options, String key, String defaultValue) {
        return new BigDecimal(options.getOrDefault(key, defaultValue));
    }

    static int integer(Map<String, String> options, String key, String defaultValue) {
        return Integer.parseInt(options.getOrDefault(key, defaultValue));
    }

    static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java ... com.btcgrid.backtest.engine.BacktestCli --csv path/to/4h.csv [options]");
        System.out.println("  java ... com.btcgrid.backtest.engine.BacktestCli --config-file path/to/best-case.properties [options]");
        System.out.println("Required:");
        System.out.println("  --csv                     CSV file with columns: timestamp,open,high,low,close[,volume]");
        System.out.println("  --indicator-csv           optional dedicated 4h csv for indicator calculation");
        System.out.println("  --config-file             optional properties file; command-line args override file values");
        System.out.println("Optional strategy params:");
        System.out.println("  --symbol                  default BTCUSDT");
        System.out.println("  --category                default linear");
        System.out.println("  --base-grid-spacing       default 0.001 (ratio, e.g. one per mille)");
        System.out.println("  --grid-requote-shrink-factor default 0.85");
        System.out.println("  --base-grid-qty           default 0.01");
        System.out.println("  --max-initial-steps       default 5");
        System.out.println("  --max-order-qty           default 0.20");
        System.out.println("  --max-inventory-qty       default 0.15");
        System.out.println("  --max-directional-inventory-qty default 0.15");
        System.out.println("  --max-consecutive-same-side-fills default 4");
        System.out.println("  --ema-length-4h           default 20");
        System.out.println("  --boll-length-4h          default 20");
        System.out.println("  --boll-std-dev-multiplier default 2.0");
        System.out.println("  --deinventory-pnl-threshold-rate default 0.01");
        System.out.println("  --deinventory-boost-slope default 10");
        System.out.println("  --deinventory-boost-cap   default 0.30");
        System.out.println("  --classic-reduce-sizing   default false");
        System.out.println("  --pair-order-timeout-minutes default 0 (disabled for 4h backtests)");
        System.out.println("  --trigger-cooldown-minutes default 5");
        System.out.println("Optional instrument params:");
        System.out.println("  --qty-step                default 0.001");
        System.out.println("  --min-order-qty           default 0.001");
        System.out.println("  --price-tick              default 0.1");
        System.out.println("  --min-price               default 0.1");
        System.out.println("Execution model:");
        System.out.println("  --maker-fee-rate          default 0");
        System.out.println("  --taker-fee-rate          default 0");
        System.out.println("  --market-slippage-bps     default 0");
        System.out.println("  --limit-fill-ratio-per-tick default 1");
        System.out.println("  --market-fill-ratio-per-tick default 1");
        System.out.println("  --min-fill-qty            default 0.00000001");
        System.out.println("  --max-volume-participation-rate default 1");
        System.out.println("  --min-touch-fill-ratio    default 1");
        System.out.println("Output:");
        System.out.println("  --output-dir              optional folder for summary.txt, events.csv, equity.csv");
    }

    private static Map<String, String> loadPropertiesFile(Path file) {
        Properties properties = new Properties();
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config file: " + file, e);
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name);
            if (value != null && !value.isBlank()) {
                options.put(name, value.trim());
            }
        }
        return options;
    }

    private static Path optionalPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value);
    }
}
