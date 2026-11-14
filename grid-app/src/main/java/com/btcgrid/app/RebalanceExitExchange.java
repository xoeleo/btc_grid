package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitSdkClientFactory;
import com.btcgrid.common.model.*;
import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.bybit.api.client.domain.market.request.MarketDataRequest;
import com.bybit.api.client.domain.CategoryType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Strict, paginated, per-order evidence for the exit controller. API errors never mean no orders. */
public final class RebalanceExitExchange implements RebalanceProfitExit.Exchange {
    private final BybitSdkClientFactory factory;
    private final String symbol;
    private static final ObjectMapper JSON = new ObjectMapper();
    public RebalanceExitExchange(BybitSdkClientFactory factory, String symbol) {
        this.factory = factory; this.symbol = symbol;
    }
    private TradeOrderRequest.TradeOrderRequestBuilder request() {
        return TradeOrderRequest.builder().category(com.bybit.api.client.domain.CategoryType.LINEAR).symbol(symbol).limit(50);
    }
    private JsonNode checked(Object response) {
        JsonNode root = JSON.valueToTree(response);
        if (!root.has("retCode") || root.path("retCode").asInt(-1) != 0
                || !root.path("result").path("list").isArray()) {
            throw new IllegalStateException("Profit exit evidence API failed: " + root.path("retCode") + " " + root.path("retMsg"));
        }
        return root.path("result");
    }
    public List<WorkingOrder> openOrders() {
        List<WorkingOrder> orders = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            JsonNode result = checked(factory.createTradeRestClient().getOpenOrders(request().openOnly(0).cursor(cursor).build()));
            result.path("list").forEach(n -> orders.add(order(n)));
            cursor = result.path("nextPageCursor").asText("");
            if (!cursor.isBlank() && !cursors.add(cursor)) throw new IllegalStateException("Repeated open-order cursor");
        } while (!cursor.isBlank());
        return orders;
    }
    public BigDecimal bestOppositePrice(TradeSide closeSide) {
        JsonNode root = JSON.valueToTree(factory.createMarketRestClient().getMarketTickers(
                MarketDataRequest.builder().category(CategoryType.LINEAR).symbol(symbol).build()));
        JsonNode list = root.path("result").path("list");
        if (root.path("retCode").asInt(-1) != 0 || !list.isArray() || list.size() != 1
                || !symbol.equals(list.get(0).path("symbol").asText()))
            throw new IllegalStateException("Cannot verify current order book ticker");
        String key = closeSide == TradeSide.BUY ? "ask1Price" : "bid1Price";
        BigDecimal price = decimal(list.get(0), key);
        if (price.signum() <= 0) throw new IllegalStateException("No positive " + key);
        return price;
    }
    public RebalanceProfitExit.Evidence evidence(String id) {
        JsonNode result = checked(factory.createTradeRestClient().getOpenOrders(request().orderLinkId(id).build()));
        JsonNode node = find(result, id);
        if (node == null) {
            result = checked(factory.createTradeRestClient().getOrderHistory(request().orderLinkId(id).build()));
            node = find(result, id);
        }
        if (node == null) return null;
        Map<String, FillEvent> fills = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            result = checked(factory.createTradeRestClient().getTradeHistory(request().orderLinkId(id).cursor(cursor).build()));
            for (JsonNode n : result.path("list")) {
                if (!id.equals(n.path("orderLinkId").asText()) || !symbol.equals(n.path("symbol").asText()))
                    throw new IllegalStateException("Unexpected execution returned for exit order");
                if (!"Trade".equals(n.path("execType").asText("Trade"))) continue;
                if (n.path("execId").asText().isBlank() || n.path("execFee").asText().isBlank())
                    throw new IllegalStateException("Execution identity or fee missing");
                FillEvent fill = new FillEvent(n.path("execId").asText(), symbol, id,
                        "Buy".equals(n.path("side").asText()) ? TradeSide.BUY : TradeSide.SELL,
                        decimal(n,"execQty"), decimal(n,"execPrice"), time(n,"execTime"),
                        decimal(n,"execValue"), decimal(n,"execFee"), "USDT");
                fills.put(fill.executionId(), fill);
            }
            cursor = result.path("nextPageCursor").asText("");
            if (!cursor.isBlank() && !cursors.add(cursor)) throw new IllegalStateException("Repeated execution cursor");
        } while (!cursor.isBlank());
        return new RebalanceProfitExit.Evidence(order(node), decimal(node, "cumExecQty"),
                fills.values().stream().sorted(Comparator.comparing(FillEvent::fillTime)).toList());
    }
    private JsonNode find(JsonNode result, String id) {
        for (JsonNode node : result.path("list"))
            if (id.equals(node.path("orderLinkId").asText()) && symbol.equals(node.path("symbol").asText())) return node;
        return null;
    }
    private WorkingOrder order(JsonNode n) {
        OrderStatus status = switch (n.path("orderStatus").asText()) {
            case "New", "PartiallyFilled", "Untriggered" -> OrderStatus.NEW;
            case "Filled" -> OrderStatus.FILLED;
            case "Cancelled", "Canceled", "PartiallyFilledCanceled", "Deactivated" -> OrderStatus.CANCELED;
            case "Rejected", "Expired" -> OrderStatus.EXPIRED;
            default -> throw new IllegalStateException("Unknown exchange order status " + n.path("orderStatus"));
        };
        return new WorkingOrder(n.path("symbol").asText(), n.path("orderLinkId").asText(), n.path("orderId").asText(),
                "Buy".equals(n.path("side").asText()) ? TradeSide.BUY : TradeSide.SELL,
                "Limit".equals(n.path("orderType").asText()) ? OrderType.LIMIT : OrderType.MARKET,
                decimal(n,"price"), decimal(n,"qty"), n.path("reduceOnly").asBoolean(), status,
                time(n,"createdTime"), time(n,"updatedTime"));
    }
    private BigDecimal decimal(JsonNode n, String key) { return new BigDecimal(n.path(key).asText()); }
    private Instant time(JsonNode n, String key) { return Instant.ofEpochMilli(Long.parseLong(n.path(key).asText())); }
}
