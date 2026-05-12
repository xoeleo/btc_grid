package com.btcgrid.bybit.adapter;

import java.util.Properties;

public final class BybitConfigLoader {

    private BybitConfigLoader() {
    }

    public static BybitSdkConfig load(Properties properties) {
        String apiKey = propertyOrEnv(properties, "bybit.api-key", "BYBIT_API_KEY");
        String apiSecret = propertyOrEnv(properties, "bybit.api-secret", "BYBIT_API_SECRET");
        boolean demo = Boolean.parseBoolean(properties.getProperty("bybit.demo", "false"));
        boolean testnet = Boolean.parseBoolean(properties.getProperty("bybit.testnet", "true"));
        boolean publicMarketMainnet = Boolean.parseBoolean(properties.getProperty("bybit.public-market-mainnet", "true"));
        boolean debugMode = Boolean.parseBoolean(properties.getProperty("bybit.debug-mode", "false"));
        long recvWindow = Long.parseLong(properties.getProperty("bybit.recv-window", "5000"));
        return new BybitSdkConfig(apiKey, apiSecret, demo, testnet, publicMarketMainnet, debugMode, recvWindow);
    }

    private static String propertyOrEnv(Properties properties, String propertyKey, String envKey) {
        String propertyValue = properties.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        String envValue = System.getenv(envKey);
        return envValue == null ? "" : envValue.trim();
    }
}
