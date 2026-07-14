package com.btcgrid.bybit.adapter;

import com.bybit.api.client.config.BybitApiConfig;
import com.bybit.api.client.restApi.BybitApiAccountRestClient;
import com.bybit.api.client.restApi.BybitApiAssetRestClient;
import com.bybit.api.client.restApi.BybitApiMarketRestClient;
import com.bybit.api.client.restApi.BybitApiPositionRestClient;
import com.bybit.api.client.restApi.BybitApiTradeRestClient;
import com.bybit.api.client.service.BybitApiClientFactory;

public class BybitSdkClientFactory {

    private static final String DEMO_REST_DOMAIN = "https://api-demo.bybit.com";

    private final BybitSdkConfig config;
    private final BybitApiClientFactory marketDelegate;
    private final BybitApiClientFactory tradeDelegate;
    private final String tradeBaseUrl;

    public BybitSdkClientFactory(BybitSdkConfig config) {
        this.config = config;
        this.tradeBaseUrl = config.demo()
                ? DEMO_REST_DOMAIN
                : (config.testnet() ? BybitApiConfig.TESTNET_DOMAIN : BybitApiConfig.MAINNET_DOMAIN);
        String marketBaseUrl = !config.demo() && config.publicMarketMainnet() ? BybitApiConfig.MAINNET_DOMAIN : this.tradeBaseUrl;
        this.tradeDelegate = BybitApiClientFactory.newInstance(
                config.apiKey(),
                config.apiSecret(),
                this.tradeBaseUrl,
                config.recvWindow()
        );
        this.marketDelegate = BybitApiClientFactory.newInstance(
                marketBaseUrl,
                config.debugMode()
        );
    }

    public BybitApiMarketRestClient createMarketRestClient() {
        return marketDelegate.newMarketDataRestClient();
    }

    public BybitApiTradeRestClient createTradeRestClient() {
        return tradeDelegate.newTradeRestClient();
    }

    public BybitApiAccountRestClient createAccountRestClient() {
        return tradeDelegate.newAccountRestClient();
    }

    public BybitApiAssetRestClient createAssetRestClient() {
        return tradeDelegate.newAssetRestClient();
    }

    public BybitApiPositionRestClient createPositionRestClient() {
        return tradeDelegate.newPositionRestClient();
    }

    public BybitSdkConfig config() {
        return config;
    }

    public String tradeBaseUrl() {
        return tradeBaseUrl;
    }
}
