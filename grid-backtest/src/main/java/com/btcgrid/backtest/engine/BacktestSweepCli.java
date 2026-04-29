package com.btcgrid.backtest.engine;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.indicator.FourHourIndicatorCalculator;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.ledger.service.StrategyLedger;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BacktestSweepCli {

    private BacktestSweepCli() {
    }

    public static void main(String[] args) {
        Map<String, String> options = BacktestCli.parseArgs(args);
        if (options.containsKey("help") || !options.containsKey("csv") || !options.containsKey("sweep-file")
                || !options.containsKey("output-dir")) {
            printUsage();
            return;
        }

        Path sweepFile = Path.of(options.get("sweep-file"));
        Path outputDir = Path.of(options.get("output-dir"));
        Map<String, String> baseOptions = new LinkedHashMap<>(options);
        baseOptions.remove("sweep-file");
        baseOptions.remove("output-dir");
        baseOptions.remove("top-n");

        List<BacktestSweepCase> cases = new BacktestSweepLoader().load(sweepFile, baseOptions);
        List<BacktestSweepOutcome> outcomes = new ArrayList<>();
        int topN = Integer.parseInt(options.getOrDefault("top-n", "10"));
        BacktestSweepSelection selection = selection(options);
        BacktestSweepEvaluator evaluator = new BacktestSweepEvaluator();

        for (BacktestSweepCase sweepCase : cases) {
            GridStrategyConfig config = BacktestCli.strategyConfig(sweepCase.options());
            InstrumentSpec instrumentSpec = BacktestCli.instrumentSpec(sweepCase.options(), config.symbol());
            BacktestMarketDataFeed marketDataFeed = new CsvBacktestMarketDataFeed(
                    Path.of(sweepCase.options().get("csv")),
                    optionalPath(sweepCase.options().get("indicator-csv")),
                    config.symbol(),
                    new FourHourIndicatorCalculator(),
                    config.emaLength4h(),
                    config.bollLength4h(),
                    config.bollStdDevMultiplier()
            );
            InMemoryExecutionSimulator executionSimulator =
                    new InMemoryExecutionSimulator(BacktestCli.executionConfig(sweepCase.options()));
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
            outcomes.add(new BacktestSweepOutcome(sweepCase.caseId(), sweepCase.options(), result));

            Path caseOutputDir = outputDir.resolve(sweepCase.caseId());
            new BacktestReportWriter().write(caseOutputDir, config, instrumentSpec, result);
            System.out.println("completed " + sweepCase.caseId()
                    + " netPnl=" + result.netPnl()
                    + " maxDrawdown=" + result.maxDrawdown());
        }

        List<BacktestSweepOutcome> rankedOutcomes = evaluator.rank(outcomes, selection);
        new BacktestSweepReportWriter().writeSummaryAndLeaderboard(
                outputDir,
                rankedOutcomes,
                selection,
                evaluator,
                outcomes.size(),
                topN
        );
        printTop(rankedOutcomes, topN, selection, evaluator);
        System.out.println("sweepCases=" + outcomes.size());
        System.out.println("qualifiedCases=" + rankedOutcomes.size());
        System.out.println("scoreMode=" + selection.scoreMode());
        System.out.println("sweepOutputDir=" + outputDir.toAbsolutePath());
    }

    private static void printTop(
            List<BacktestSweepOutcome> rankedOutcomes,
            int topN,
            BacktestSweepSelection selection,
            BacktestSweepEvaluator evaluator
    ) {
        System.out.println("Top Results");
        rankedOutcomes.stream()
                .limit(topN)
                .forEachOrdered(outcome -> System.out.println(
                        outcome.caseId()
                                + " score=" + evaluator.score(outcome.result(), selection.scoreMode())
                                + " netPnl=" + outcome.result().netPnl()
                                + " maxDrawdown=" + outcome.result().maxDrawdown()
                                + " profitFactor=" + outcome.result().profitFactor()
                ));
    }

    private static BacktestSweepSelection selection(Map<String, String> options) {
        return new BacktestSweepSelection(
                BacktestSweepScoreMode.valueOf(options.getOrDefault("score-mode", "NET_PNL").toUpperCase()),
                decimalOrNull(options.get("min-net-pnl")),
                decimalOrNull(options.get("max-drawdown")),
                decimalOrNull(options.get("min-win-rate")),
                decimalOrNull(options.get("max-peak-inventory-qty"))
        );
    }

    private static BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

    private static Path optionalPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java ... com.btcgrid.backtest.engine.BacktestSweepCli --csv path/to/4h.csv --sweep-file path/to/sweep.properties --output-dir path/to/reports [options]");
        System.out.println("Required:");
        System.out.println("  --csv                     historical candle csv");
        System.out.println("  --indicator-csv           optional dedicated 4h csv for indicator calculation");
        System.out.println("  --sweep-file              properties file, each key maps to comma-separated values");
        System.out.println("  --output-dir              parent directory for case reports and leaderboard");
        System.out.println("Optional:");
        System.out.println("  --top-n                   default 10");
        System.out.println("  --score-mode              NET_PNL | NET_PNL_MINUS_DRAWDOWN | NET_PNL_MINUS_HALF_DRAWDOWN | PROFIT_FACTOR | WIN_RATE");
        System.out.println("  --min-net-pnl             optional filter");
        System.out.println("  --max-drawdown            optional filter");
        System.out.println("  --min-win-rate            optional filter");
        System.out.println("  --max-peak-inventory-qty  optional filter");
        System.out.println("Other options:");
        System.out.println("  all single-run BacktestCli options are accepted as base defaults");
    }
}
