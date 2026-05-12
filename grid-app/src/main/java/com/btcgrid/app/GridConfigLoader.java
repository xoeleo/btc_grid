package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitConfigLoader;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.ProductCategory;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Properties;

public final class GridConfigLoader {

    private GridConfigLoader() {
    }

    public static GridProperties load(String resourceName) {
        Properties properties = loadProperties(resourceName);
        GridStrategyConfig strategyConfig = new GridStrategyConfig(
                ProductCategory.fromConfig(required(properties, "grid.category")),
                properties.getProperty("grid.symbol"),
                EntryDirection.fromConfig(properties.getProperty("grid.entry-direction")),
                decimal(properties, "grid.base-grid-spacing"),
                decimal(properties, "grid.grid-requote-shrink-factor"),
                decimal(properties, "grid.base-grid-qty"),
                decimal(properties, "grid.max-initial-steps"),
                decimal(properties, "grid.max-order-qty"),
                decimal(properties, "grid.max-inventory-qty"),
                decimal(properties, "grid.max-directional-inventory-qty"),
                integer(properties, "grid.max-consecutive-same-side-fills"),
                integer(properties, "grid.ema-length-4h"),
                integer(properties, "grid.boll-length-4h"),
                decimal(properties, "grid.boll-std-dev-multiplier"),
                decimal(properties, "grid.deinventory-pnl-threshold-rate"),
                decimal(properties, "grid.deinventory-boost-slope"),
                decimal(properties, "grid.deinventory-boost-cap"),
                Boolean.parseBoolean(properties.getProperty("grid.classic-reduce-sizing", "false")),
                Duration.ofMinutes(integer(properties, "grid.pair-order-timeout-minutes")),
                Duration.ofMinutes(integer(properties, "grid.trigger-cooldown-minutes"))
        );
        RuntimeSettings runtimeSettings = new RuntimeSettings(
                Duration.ofSeconds(Long.parseLong(properties.getProperty("runtime.market-poll-seconds", "15"))),
                Boolean.parseBoolean(properties.getProperty("runtime.auto-start-live", "false")),
                PathResolver.absolute(properties.getProperty("runtime.state-file", "runtime/grid-state.json")),
                PathResolver.absolute(properties.getProperty("runtime.log-file", "runtime/grid-events.log")),
                PathResolver.absolute(properties.getProperty("runtime.status-file", "runtime/grid-status.json")),
                PathResolver.absolute(properties.getProperty("runtime.fills-db", "runtime/grid-fills.sqlite")),
                Boolean.parseBoolean(properties.getProperty("runtime.http-enabled", "true")),
                Integer.parseInt(properties.getProperty("runtime.http-port", "8787")),
                Integer.parseInt(properties.getProperty("runtime.reconnect-base-delay-seconds", "5")),
                Integer.parseInt(properties.getProperty("runtime.reconnect-max-attempts", "20"))
        );
        return new GridProperties(strategyConfig, BybitConfigLoader.load(properties), runtimeSettings, properties);
    }

    private static Properties loadProperties(String resourceName) {
        Properties properties = new Properties();
        try (InputStream inputStream = GridConfigLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing config resource: " + resourceName);
            }
            properties.load(inputStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config resource: " + resourceName, e);
        }
        return properties;
    }
    
    private static BigDecimal decimal(Properties properties, String key) {
        return new BigDecimal(required(properties, key));
    }

    private static int integer(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return value.trim();
    }
}
