package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.ProductCategory;
import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.bybit.api.client.restApi.BybitApiTradeRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class BybitSdkTradeGateway implements BybitTradeGateway {

    private final BybitSdkClientFactory clientFactory;
    private final ProductCategory category;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BybitSdkTradeGateway(BybitSdkClientFactory clientFactory, ProductCategory category) {
        this.clientFactory = clientFactory;
        this.category = category;
    }

    @Override
    public void submit(OrderIntent orderIntent) {
        BybitApiTradeRestClient tradeClient = clientFactory.createTradeRestClient();
        validateResponse("createOrder", tradeClient.createOrder(BybitSdkMapper.toTradeOrderRequest(category, orderIntent)));
    }

    @Override
    public void cancel(String symbol, String clientOrderId) {
        BybitApiTradeRestClient tradeClient = clientFactory.createTradeRestClient();
        TradeOrderRequest request = TradeOrderRequest.builder()
                .category(BybitSdkMapper.toCategory(category))
                .symbol(symbol)
                .orderLinkId(clientOrderId)
                .build();
        validateResponse("cancelOrder", tradeClient.cancelOrder(request));
    }

    public BybitSdkClientFactory clientFactory() {
        return clientFactory;
    }

    private void validateResponse(String operation, Object response) {
        JsonNode root = objectMapper.valueToTree(response);
        if (root.isTextual()) {
            try {
                root = objectMapper.readTree(root.asText());
            } catch (Exception e) {
                throw new IllegalStateException(operation + " returned an unparseable Bybit response: " + root.asText(), e);
            }
        }
        int retCode = root.path("retCode").asInt(0);
        if (retCode != 0) {
            String retMsg = root.path("retMsg").asText("unknown Bybit error");
            throw new IllegalStateException(operation + " rejected by Bybit: retCode=" + retCode + ", retMsg=" + retMsg);
        }
        if (!root.has("retCode")) {
            throw new IllegalStateException(operation + " returned an unexpected Bybit response: " + root);
        }
    }
}
