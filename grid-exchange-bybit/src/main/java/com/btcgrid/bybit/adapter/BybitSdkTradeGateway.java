package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.bybit.api.client.restApi.BybitApiTradeRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BybitSdkTradeGateway implements BybitTradeGateway {

    private static final String DEMO_REST_DOMAIN = "https://api-demo.bybit.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final BybitSdkClientFactory clientFactory;
    private final ProductCategory category;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public BybitSdkTradeGateway(BybitSdkClientFactory clientFactory, ProductCategory category) {
        this.clientFactory = clientFactory;
        this.category = category;
    }

    @Override
    public BybitOrderSubmission submit(OrderIntent orderIntent) {
        if (clientFactory.config().demo()) {
            return submitDemoRest(orderIntent);
        }
        BybitApiTradeRestClient tradeClient = clientFactory.createTradeRestClient();
        JsonNode root = validateResponse("createOrder", tradeClient.createOrder(BybitSdkMapper.toTradeOrderRequest(category, orderIntent)));
        JsonNode result = root.path("result");
        String orderId = result.path("orderId").asText("");
        if (orderId.isBlank()) {
            throw new IllegalStateException("createOrder returned no Bybit orderId: " + root);
        }
        String orderLinkId = result.path("orderLinkId").asText(orderIntent.clientOrderId());
        return new BybitOrderSubmission(orderId, orderLinkId);
    }

    @Override
    public void cancel(String symbol, String clientOrderId) {
        if (clientFactory.config().demo()) {
            cancelDemoRest(symbol, clientOrderId);
            return;
        }
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

    private BybitOrderSubmission submitDemoRest(OrderIntent orderIntent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", categoryValue());
        body.put("symbol", orderIntent.symbol());
        body.put("side", orderIntent.side() == TradeSide.BUY ? "Buy" : "Sell");
        body.put("orderType", orderIntent.type() == OrderType.MARKET ? "Market" : "Limit");
        body.put("qty", orderIntent.qty().stripTrailingZeros().toPlainString());
        body.put("orderLinkId", orderIntent.clientOrderId());
        body.put("reduceOnly", orderIntent.reduceOnly());
        if (category == ProductCategory.LINEAR || category == ProductCategory.INVERSE) {
            body.put("positionIdx", 0);
        }
        if (orderIntent.type() == OrderType.LIMIT) {
            body.put("price", orderIntent.price().stripTrailingZeros().toPlainString());
            body.put("timeInForce", "GTC");
        } else {
            body.put("timeInForce", "IOC");
        }

        JsonNode root = signedDemoPost("createOrder", "/v5/order/create", body);
        JsonNode result = root.path("result");
        String orderId = result.path("orderId").asText("");
        if (orderId.isBlank()) {
            throw new IllegalStateException("createOrder returned no Bybit orderId: " + root);
        }
        String orderLinkId = result.path("orderLinkId").asText(orderIntent.clientOrderId());
        return new BybitOrderSubmission(orderId, orderLinkId);
    }

    private void cancelDemoRest(String symbol, String clientOrderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", categoryValue());
        body.put("symbol", symbol);
        body.put("orderLinkId", clientOrderId);
        signedDemoPost("cancelOrder", "/v5/order/cancel", body);
    }

    private JsonNode signedDemoPost(String operation, String path, Map<String, Object> body) {
        BybitSdkConfig config = clientFactory.config();
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String recvWindow = String.valueOf(config.recvWindow());
            String signature = hmacSha256(config.apiSecret(), timestamp + config.apiKey() + recvWindow + bodyJson);
            HttpRequest request = HttpRequest.newBuilder(URI.create(DEMO_REST_DOMAIN + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-BAPI-API-KEY", config.apiKey())
                    .header("X-BAPI-TIMESTAMP", timestamp)
                    .header("X-BAPI-RECV-WINDOW", recvWindow)
                    .header("X-BAPI-SIGN", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return validateResponse(operation, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted while calling Bybit demo REST", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException(operation + " failed while calling Bybit demo REST", e);
        }
    }

    private String categoryValue() {
        return switch (category) {
            case SPOT -> "spot";
            case LINEAR -> "linear";
            case INVERSE -> "inverse";
            case OPTION -> "option";
        };
    }

    private String hmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private JsonNode validateResponse(String operation, Object response) {
        JsonNode root = objectMapper.valueToTree(response);
        if (root.isTextual()) {
            try {
                root = objectMapper.readTree(root.asText());
            } catch (Exception e) {
                throw new IllegalStateException(operation + " returned an unparseable Bybit response: " + root.asText(), e);
            }
        }
        if (!root.has("retCode")) {
            throw new IllegalStateException(operation + " returned an unexpected Bybit response: " + root);
        }
        int retCode = root.path("retCode").asInt();
        if (retCode != 0) {
            String retMsg = root.path("retMsg").asText("unknown Bybit error");
            throw new IllegalStateException(operation + " rejected by Bybit: retCode=" + retCode + ", retMsg=" + retMsg);
        }
        return root;
    }
}
