package com.btcgrid.backtest.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class BacktestSweepReportWriter {

    public void writeSummaryAndLeaderboard(
            Path outputDir,
            List<BacktestSweepOutcome> rankedOutcomes,
            BacktestSweepSelection selection,
            BacktestSweepEvaluator evaluator,
            int totalCases,
            int topN
    ) {
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("summary.txt"), buildSummary(rankedOutcomes, selection, evaluator, totalCases, topN));
            List<String> lines = new ArrayList<>();
            lines.add("rank,case_id,score_mode,score,net_pnl,max_drawdown,profit_factor,win_rate,peak_inventory_qty,total_fees,options");
            for (int i = 0; i < rankedOutcomes.size(); i++) {
                BacktestSweepOutcome outcome = rankedOutcomes.get(i);
                lines.add(csv(
                        i + 1,
                        outcome.caseId(),
                        selection.scoreMode(),
                        evaluator.score(outcome.result(), selection.scoreMode()),
                        outcome.result().netPnl(),
                        outcome.result().maxDrawdown(),
                        outcome.result().profitFactor(),
                        outcome.result().winRate(),
                        outcome.result().peakInventoryQty(),
                        outcome.result().totalFees(),
                        formatOptions(outcome.options())
                ));
            }
            Files.write(outputDir.resolve("leaderboard.csv"), lines);
            writeParameterSnapshots(outputDir, rankedOutcomes, topN);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write sweep leaderboard to " + outputDir, e);
        }
    }

    private String buildSummary(
            List<BacktestSweepOutcome> rankedOutcomes,
            BacktestSweepSelection selection,
            BacktestSweepEvaluator evaluator,
            int totalCases,
            int topN
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("Sweep Summary").append(System.lineSeparator());
        builder.append("totalCases=").append(totalCases).append(System.lineSeparator());
        builder.append("qualifiedCases=").append(rankedOutcomes.size()).append(System.lineSeparator());
        builder.append("scoreMode=").append(selection.scoreMode()).append(System.lineSeparator());
        if (selection.minNetPnl() != null) {
            builder.append("minNetPnl=").append(selection.minNetPnl()).append(System.lineSeparator());
        }
        if (selection.maxDrawdown() != null) {
            builder.append("maxDrawdown=").append(selection.maxDrawdown()).append(System.lineSeparator());
        }
        if (selection.minWinRate() != null) {
            builder.append("minWinRate=").append(selection.minWinRate()).append(System.lineSeparator());
        }
        if (selection.maxPeakInventoryQty() != null) {
            builder.append("maxPeakInventoryQty=").append(selection.maxPeakInventoryQty()).append(System.lineSeparator());
        }
        builder.append("topN=").append(topN).append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("Top Results").append(System.lineSeparator());
        rankedOutcomes.stream()
                .limit(topN)
                .forEachOrdered(outcome -> builder.append(outcome.caseId())
                        .append(" score=").append(evaluator.score(outcome.result(), selection.scoreMode()))
                        .append(" netPnl=").append(outcome.result().netPnl())
                        .append(" maxDrawdown=").append(outcome.result().maxDrawdown())
                        .append(" profitFactor=").append(outcome.result().profitFactor())
                        .append(System.lineSeparator()));
        return builder.toString();
    }

    private void writeParameterSnapshots(
            Path outputDir,
            List<BacktestSweepOutcome> rankedOutcomes,
            int topN
    ) throws IOException {
        if (rankedOutcomes.isEmpty()) {
            return;
        }

        writePropertiesFile(outputDir.resolve("best-case.properties"), rankedOutcomes.get(0));

        Path topCasesDir = outputDir.resolve("top-cases");
        Files.createDirectories(topCasesDir);
        for (int i = 0; i < rankedOutcomes.size() && i < topN; i++) {
            BacktestSweepOutcome outcome = rankedOutcomes.get(i);
            String filename = String.format("%02d-%s.properties", i + 1, outcome.caseId());
            writePropertiesFile(topCasesDir.resolve(filename), outcome);
        }
    }

    private void writePropertiesFile(Path file, BacktestSweepOutcome outcome) throws IOException {
        Properties properties = new Properties();
        outcome.options().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> properties.setProperty(entry.getKey(), entry.getValue()));
        properties.setProperty("sweep.case-id", outcome.caseId());
        try (java.io.Writer writer = Files.newBufferedWriter(file)) {
            properties.store(writer, "Generated by BacktestSweepReportWriter");
        }
    }

    private String formatOptions(java.util.Map<String, String> options) {
        return options.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    private String csv(Object... values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escape(values[i]));
        }
        return builder.toString();
    }

    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains(";")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
