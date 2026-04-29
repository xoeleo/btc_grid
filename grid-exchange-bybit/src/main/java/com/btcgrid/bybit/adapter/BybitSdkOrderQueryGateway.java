package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.bybit.api.client.restApi.BybitApiTradeRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BybitSdkOrderQueryGateway implements BybitOrderQueryGateway {

    private final BybitSdkClientFactory clientFactory;
    private final ProductCategory category;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BybitSdkOrderQueryGateway(BybitSdkClientFactory clientFactory, ProductCategory category) {
        this.clientFactory = clientFactory;
        this.category = category;
    }

    @Override
    public List<WorkingOrder> fetchOpenOrders(String symbol) {
        BybitApiTradeRestClient client = clientFactory.createTradeRestClient();
        TradeOrderRequest request = TradeOrderRequest.builder()
                .category(BybitSdkMapper.toCategory(category))
                .symbol(symbol)
                .openOnly(0)
                .limit(50)
                .build();
        JsonNode root = objectMapper.valueToTree(client.getOpenOrders(request));
        JsonNode list = root.path("result").path("list");
        List<WorkingOrder> result = new ArrayList<>();
        if (!list.isArray()) {
            return result;
        }
        for (JsonNode node : list) {
            String clientOrderId = node.path("orderLinkId").asText("");
            if (clientOrderId.isBlank()) {
                continue;
            }
            result.add(new WorkingOrder(
                    node.path("symbol").asText(symbol),
                    clientOrderId,
                    node.path("orderId").asText(null),
                    "Buy".equalsIgnoreCase(node.path("side").asText()) ? TradeSide.BUY : TradeSide.SELL,
                    "Market".equalsIgnoreCase(node.path("orderType").asText()) ? OrderType.MARKET : OrderType.LIMIT,
                    decimalOrNull(node.path("price").asText()),
                    decimalOrNull(node.path("qty").asText()),
                    node.path("reduceOnly").asBoolean(false),
                    normalizeStatus(node.path("orderStatus").asText()),
                    epochMillis(node.path("createdTime").asText()),
                    epochMillis(node.path("updatedTime").asText())
            ));
        }
        return result;
    }

    private OrderStatus normalizeStatus(String status) {
        if (status == null) {
            return OrderStatus.NEW;
        }
        return switch (status.toUpperCase()) {
            case "FILLED", "PARTIALLYFILLED", "PARTIALLY_FILLED" -> OrderStatus.FILLED;
            case "CANCELLED", "CANCELED", "DEACTIVATED" -> OrderStatus.CANCELED;
            case "REJECTED", "EXPIRED" -> OrderStatus.EXPIRED;
            default -> OrderStatus.NEW;
        };
    }

    private BigDecimal decimalOrNull(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private Instant epochMillis(String value) {
        return value == null || value.isBlank() ? Instant.EPOCH : Instant.ofEpochMilli(Long.parseLong(value));
    }
}
