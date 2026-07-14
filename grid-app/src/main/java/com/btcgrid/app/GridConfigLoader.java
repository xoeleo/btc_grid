package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitConfigLoader;
import com.btcgrid.bybit.adapter.BybitSdkConfig;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.ProductCategory;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public final class GridConfigLoader {

    private GridConfigLoader() {
    }

    public static GridProperties load(String resourceName) {
        Properties properties = loadProperties(resourceName);
        applySystemPropertyOverrides(properties);
        BybitSdkConfig bybitSdkConfig = BybitConfigLoader.load(properties);
        String runtimeEnvironment = runtimeEnvironment(bybitSdkConfig);
        loadRuntimeOverride(properties, runtimeEnvironment);
        applySystemPropertyOverrides(properties);
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
        validateStrategyConfig(strategyConfig);
        RebalanceSettings rebalanceSettings = rebalanceSettings(properties, runtimeEnvironment);
        RuntimeSettings runtimeSettings = new RuntimeSettings(
                Duration.ofSeconds(Long.parseLong(properties.getProperty("runtime.market-poll-seconds", "15"))),
                Boolean.parseBoolean(properties.getProperty("runtime.auto-start-live", "false")),
                runtimePath(properties, "runtime.state-file", runtimeEnvironment, "grid-state.json"),
                runtimePath(properties, "runtime.log-file", runtimeEnvironment, "grid-events.log"),
                runtimePath(properties, "runtime.status-file", runtimeEnvironment, "grid-status.json"),
                runtimePath(properties, "runtime.fills-db", runtimeEnvironment, "grid-fills.sqlite"),
                Boolean.parseBoolean(properties.getProperty("runtime.http-enabled", "true")),
                properties.getProperty("runtime.http-host", "0.0.0.0").trim(),
                Integer.parseInt(properties.getProperty("runtime.http-port", "8787")),
                Integer.parseInt(properties.getProperty("runtime.reconnect-base-delay-seconds", "5")),
                Integer.parseInt(properties.getProperty("runtime.reconnect-max-attempts", "20"))
        );
        return new GridProperties(strategyConfig, rebalanceSettings, bybitSdkConfig, runtimeSettings, properties);
    }

    private static void validateStrategyConfig(GridStrategyConfig strategyConfig) {
        if (strategyConfig.entryDirection().isSpotLongOnly() && strategyConfig.category() != ProductCategory.SPOT) {
            throw new IllegalStateException("grid.entry-direction=spot_long_only requires grid.category=spot");
        }
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

    private static void applySystemPropertyOverrides(Properties properties) {
        System.getProperties().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (name.startsWith("grid.")
                    || name.startsWith("runtime.")
                    || name.startsWith("rebalance.")
                    || name.startsWith("bybit.")) {
                properties.setProperty(name, String.valueOf(value));
            }
        });
    }

    private static RebalanceSettings rebalanceSettings(Properties properties, String runtimeEnvironment) {
        boolean enabled = Boolean.parseBoolean(properties.getProperty("rebalance.enabled", "false"));
        RebalanceMode mode = RebalanceMode.fromConfig(properties.getProperty("rebalance.mode", "price_target"));
        BigDecimal autoMinTotalBtc = new BigDecimal(properties.getProperty("rebalance.auto-min-total-btc", "7").trim());
        BigDecimal autoBaseGridQty = new BigDecimal(properties.getProperty("rebalance.auto-base-grid-qty", "0.005").trim());
        BigDecimal autoInitialSteps = new BigDecimal(properties.getProperty("rebalance.auto-initial-steps", "5").trim());
        ProductCategory category = ProductCategory.fromConfig(properties.getProperty("rebalance.category", "spot"));
        String symbol = properties.getProperty("rebalance.symbol", "BTCUSDT").trim().toUpperCase();
        String accountType = properties.getProperty("rebalance.account-type", "UNIFIED").trim().toUpperCase();
        String baseCoin = properties.getProperty("rebalance.base-coin", "BTC").trim().toUpperCase();
        String quoteCoin = properties.getProperty("rebalance.quote-coin", "USDT").trim().toUpperCase();
        List<RebalanceBand> bands = parseBands(properties.getProperty("rebalance.price-targets", "0:0"));
        BigDecimal ratioThreshold = new BigDecimal(properties.getProperty("rebalance.ratio-threshold", "0.01").trim());
        BigDecimal baseGridSpacing = new BigDecimal(properties.getProperty("rebalance.base-grid-spacing", properties.getProperty("grid.base-grid-spacing", "0.0016")).trim());
        BigDecimal gridRequoteShrinkFactor = new BigDecimal(properties.getProperty("rebalance.grid-requote-shrink-factor", properties.getProperty("grid.grid-requote-shrink-factor", "0.85")).trim());
        BigDecimal baseGridQty = new BigDecimal(properties.getProperty("rebalance.base-grid-qty", properties.getProperty("grid.base-grid-qty", "0.001")).trim());
        BigDecimal maxInitialSteps = new BigDecimal(properties.getProperty("rebalance.max-initial-steps", properties.getProperty("grid.max-initial-steps", "5")).trim());
        BigDecimal maxOrderQty = new BigDecimal(properties.getProperty("rebalance.max-order-qty", "0.001").trim());
        BigDecimal maxInventoryQty = new BigDecimal(properties.getProperty("rebalance.max-inventory-qty", properties.getProperty("grid.max-inventory-qty", "0.15")).trim());
        BigDecimal priceOffsetRatio = new BigDecimal(properties.getProperty("rebalance.price-offset-ratio", "0.001").trim());
        BigDecimal bandHysteresisRatio = new BigDecimal(properties.getProperty("rebalance.band-hysteresis-ratio", "0.001").trim());
        Duration orderTtl = Duration.ofMinutes(Long.parseLong(properties.getProperty("rebalance.order-ttl-minutes", "15").trim()));
        int confirmationSamples = Integer.parseInt(properties.getProperty("rebalance.confirmation-samples", "3").trim());
        String configFile = runtimePath(properties, "rebalance.config-file", runtimeEnvironment, "rebalance-config.properties");
        String stateFile = runtimePath(properties, "rebalance.state-file", runtimeEnvironment, "rebalance-state.json");
        if (enabled && bands.isEmpty()) {
            throw new IllegalStateException("rebalance.price-targets must contain at least one price:ratio band when rebalance is enabled");
        }
        return new RebalanceSettings(
                enabled,
                mode,
                autoMinTotalBtc,
                autoBaseGridQty,
                autoInitialSteps,
                category,
                symbol,
                accountType,
                baseCoin,
                quoteCoin,
                bands,
                ratioThreshold,
                baseGridSpacing,
                gridRequoteShrinkFactor,
                baseGridQty,
                maxInitialSteps,
                maxOrderQty,
                maxInventoryQty,
                priceOffsetRatio,
                bandHysteresisRatio,
                orderTtl,
                confirmationSamples,
                configFile,
                stateFile
        );
    }

    private static void loadRuntimeOverride(Properties properties, String runtimeEnvironment) {
        String overridePath = runtimePath(properties, "rebalance.config-file", runtimeEnvironment, "rebalance-config.properties");
        java.nio.file.Path path = PathResolver.absolutePath(overridePath);
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        try (java.io.InputStream inputStream = java.nio.file.Files.newInputStream(path)) {
            Properties override = new Properties();
            override.load(inputStream);
            override.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (name.startsWith("rebalance.")) {
                    properties.setProperty(name, String.valueOf(value));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load rebalance config override: " + path, e);
        }
    }

    private static List<RebalanceBand> parseBands(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .map(GridConfigLoader::parseBand)
                .sorted(Comparator.comparing(RebalanceBand::priceFloor))
                .toList();
    }

    private static RebalanceBand parseBand(String token) {
        String[] parts = token.split(":");
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid rebalance.price-targets entry: " + token);
        }
        BigDecimal priceFloor = new BigDecimal(parts[0].trim());
        BigDecimal targetRatio = new BigDecimal(parts[1].trim());
        if (targetRatio.compareTo(BigDecimal.ZERO) < 0 || targetRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("Invalid rebalance target ratio: " + token);
        }
        return new RebalanceBand(priceFloor, targetRatio);
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

    private static String runtimeEnvironment(BybitSdkConfig bybitSdkConfig) {
        if (bybitSdkConfig.demo()) {
            return "demo";
        }
        if (bybitSdkConfig.testnet()) {
            return "testnet";
        }
        return "mainnet";
    }

    private static String runtimePath(Properties properties, String key, String runtimeEnvironment, String fileName) {
        String legacyPath = "runtime/" + fileName;
        String rawPath = properties.getProperty(key, legacyPath);
        if (rawPath == null || rawPath.isBlank() || legacyPath.equals(rawPath.trim())) {
            return PathResolver.absolute("runtime/" + runtimeEnvironment + "/" + fileName);
        }
        return PathResolver.absolute(rawPath.trim().replace("{env}", runtimeEnvironment));
    }
}
