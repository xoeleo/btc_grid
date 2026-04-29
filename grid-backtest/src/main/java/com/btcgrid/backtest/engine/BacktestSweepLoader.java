package com.btcgrid.backtest.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class BacktestSweepLoader {

    public List<BacktestSweepCase> load(Path sweepFile, Map<String, String> baseOptions) {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(sweepFile)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read sweep file: " + sweepFile, e);
        }

        Map<String, List<String>> dimensions = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String raw = properties.getProperty(name, "").trim();
            if (raw.isEmpty()) {
                continue;
            }
            dimensions.put(name, splitValues(raw));
        }
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("Sweep file contains no parameter dimensions: " + sweepFile);
        }

        List<BacktestSweepCase> cases = new ArrayList<>();
        expand(new ArrayList<>(dimensions.entrySet()), 0, new LinkedHashMap<>(baseOptions), cases);
        return cases;
    }

    private void expand(
            List<Map.Entry<String, List<String>>> dimensions,
            int index,
            Map<String, String> current,
            List<BacktestSweepCase> out
    ) {
        if (index >= dimensions.size()) {
            out.add(new BacktestSweepCase("case-" + String.format("%03d", out.size() + 1), Map.copyOf(current)));
            return;
        }
        Map.Entry<String, List<String>> dimension = dimensions.get(index);
        for (String value : dimension.getValue()) {
            current.put(dimension.getKey(), value);
            expand(dimensions, index + 1, current, out);
        }
    }

    private List<String> splitValues(String raw) {
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Invalid sweep dimension: " + raw);
        }
        return values;
    }
}
