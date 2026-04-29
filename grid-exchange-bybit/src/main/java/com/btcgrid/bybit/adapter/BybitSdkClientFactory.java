package com.btcgrid.bybit.adapter;

import com.bybit.api.client.config.BybitApiConfig;
import com.bybit.api.client.restApi.BybitApiMarketRestClient;
import com.bybit.api.client.restApi.BybitApiTradeRestClient;
import com.bybit.api.client.service.BybitApiClientFactory;

public class BybitSdkClientFactory {

    private final BybitSdkConfig config;
    private final BybitApiClientFactory marketDelegate;
    private final BybitApiClientFactory tradeDelegate;

    public BybitSdkClientFactory(BybitSdkConfig config) {
        this.config = config;
        String tradeBaseUrl = config.testnet() ? BybitApiConfig.TESTNET_DOMAIN : BybitApiConfig.MAINNET_DOMAIN;
        String marketBaseUrl = config.publicMarketMainnet() ? BybitApiConfig.MAINNET_DOMAIN : tradeBaseUrl;
        this.tradeDelegate = BybitApiClientFactory.newInstance(
                config.apiKey(),
                config.apiSecret(),
                tradeBaseUrl,
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

    public BybitSdkConfig config() {
        return config;
    }
}
