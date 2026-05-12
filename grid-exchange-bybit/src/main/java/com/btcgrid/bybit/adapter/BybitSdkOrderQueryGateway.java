package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.FillEvent;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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

    @Override
    public List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds) {
        if (clientOrderIds == null || clientOrderIds.isEmpty()) {
            return List.of();
        }
        BybitApiTradeRestClient client = clientFactory.createTradeRestClient();
        TradeOrderRequest request = TradeOrderRequest.builder()
                .category(BybitSdkMapper.toCategory(category))
                .symbol(symbol)
                .limit(50)
                .build();
        JsonNode root = objectMapper.valueToTree(client.getTradeHistory(request));
        JsonNode list = root.path("result").path("list");
        List<FillEvent> result = new ArrayList<>();
        if (!list.isArray()) {
            return result;
        }
        for (JsonNode node : list) {
            String clientOrderId = node.path("orderLinkId").asText("");
            if (clientOrderId.isBlank() || !clientOrderIds.contains(clientOrderId)) {
                continue;
            }
            String executionId = node.path("execId").asText("");
            if (executionId.isBlank()) {
                continue;
            }
            result.add(new FillEvent(
                    executionId,
                    node.path("symbol").asText(symbol),
                    clientOrderId,
                    "Buy".equalsIgnoreCase(node.path("side").asText()) ? TradeSide.BUY : TradeSide.SELL,
                    decimalOrZero(node.path("execQty").asText()),
                    decimalOrZero(node.path("execPrice").asText()),
                    epochMillis(node.path("execTime").asText()),
                    decimalOrZero(node.path("execValue").asText()),
                    decimalOrZero(node.path("execFee").asText()),
                    "USDT"
            ));
        }
        result.sort(Comparator.comparing(FillEvent::fillTime));
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

    private BigDecimal decimalOrZero(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private Instant epochMillis(String value) {
        return value == null || value.isBlank() ? Instant.EPOCH : Instant.ofEpochMilli(Long.parseLong(value));
    }
}
