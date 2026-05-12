package com.btcgrid.bybit.adapter;

public record BybitSdkConfig(
        String apiKey,
        String apiSecret,
        boolean demo,
        boolean testnet,
        boolean publicMarketMainnet,
        boolean debugMode,
        long recvWindow
) {
}
