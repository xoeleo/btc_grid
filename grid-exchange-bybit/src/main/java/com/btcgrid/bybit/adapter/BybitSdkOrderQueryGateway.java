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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class BybitSdkOrderQueryGateway implements BybitOrderQueryGateway {

    private static final int PAGE_LIMIT = 50;
    /**
     * Bybit's execution-history retention and query window vary by account and
     * category. Seven days is the conservative bound used by this adapter.
     * Callers that need older fills must recover them through an exchange
     * export or another explicitly supported history source.
     */
    private static final Duration MAX_EXECUTION_LOOKBACK = Duration.ofDays(7);

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
        List<JsonNode> rows = fetchPages(cursor -> TradeOrderRequest.builder()
                        .category(BybitSdkMapper.toCategory(category))
                        .symbol(symbol)
                        .openOnly(0)
                        .limit(PAGE_LIMIT)
                        .cursor(cursor)
                        .build(),
                client::getOpenOrders,
                "open orders");
        return workingOrders(rows, symbol, null, "open orders");
    }

    @Override
    public List<WorkingOrder> fetchRecentOrders(String symbol, Set<String> clientOrderIds) {
        if (clientOrderIds == null || clientOrderIds.isEmpty()) {
            return List.of();
        }
        BybitApiTradeRestClient client = clientFactory.createTradeRestClient();
        Map<String, WorkingOrder> result = new LinkedHashMap<>();
        for (String clientOrderId : clientOrderIds) {
            if (clientOrderId == null || clientOrderId.isBlank()) {
                continue;
            }
            List<JsonNode> rows = fetchPages(cursor -> TradeOrderRequest.builder()
                            .category(BybitSdkMapper.toCategory(category))
                            .symbol(symbol)
                            .orderLinkId(clientOrderId)
                            .limit(PAGE_LIMIT)
                            .cursor(cursor)
                            .build(),
                    client::getOrderHistory,
                    "order history for " + clientOrderId);
            for (WorkingOrder order : workingOrders(rows, symbol, clientOrderIds, "order history for " + clientOrderId)) {
                result.putIfAbsent(order.clientOrderId(), order);
            }
        }
        return new ArrayList<>(result.values());
    }

    private List<WorkingOrder> workingOrders(
            List<JsonNode> rows,
            String symbol,
            Set<String> clientOrderIds,
            String operation
    ) {
        Map<String, WorkingOrder> result = new LinkedHashMap<>();
        for (JsonNode node : rows) {
            String clientOrderId = node.path("orderLinkId").asText("");
            if (clientOrderId.isBlank() || (clientOrderIds != null && !clientOrderIds.contains(clientOrderId))) {
                continue;
            }
            OrderStatus status = normalizeStatus(node.path("orderStatus").asText());
            BigDecimal cumulativeExecutedQty = cumulativeExecutedQty(node, status, operation, clientOrderId);
            result.putIfAbsent(clientOrderId, new WorkingOrder(
                    node.path("symbol").asText(symbol),
                    clientOrderId,
                    node.path("orderId").asText(null),
                    "Buy".equalsIgnoreCase(node.path("side").asText()) ? TradeSide.BUY : TradeSide.SELL,
                    "Market".equalsIgnoreCase(node.path("orderType").asText()) ? OrderType.MARKET : OrderType.LIMIT,
                    decimalOrNull(node.path("price").asText()),
                    decimalOrNull(node.path("qty").asText()),
                    node.path("reduceOnly").asBoolean(false),
                    status,
                    epochMillis(node.path("createdTime").asText()),
                    epochMillis(node.path("updatedTime").asText()),
                    null,
                    cumulativeExecutedQty
            ));
        }
        return new ArrayList<>(result.values());
    }

    private BigDecimal cumulativeExecutedQty(
            JsonNode node,
            OrderStatus status,
            String operation,
            String clientOrderId
    ) {
        JsonNode raw = node.get("cumExecQty");
        if (raw == null || raw.isNull() || raw.asText().isBlank()) {
            if (status == OrderStatus.FILLED || status == OrderStatus.CANCELED || status == OrderStatus.EXPIRED) {
                throw new IllegalStateException(
                        "Bybit " + operation + " terminal order " + clientOrderId + " has no cumExecQty"
                );
            }
            return BigDecimal.ZERO;
        }
        return decimalOrZero(raw.asText());
    }

    @Override
    public List<FillEvent> fetchRecentExecutions(String symbol, Set<String> clientOrderIds) {
        if (clientOrderIds == null || clientOrderIds.isEmpty()) {
            return List.of();
        }
        Instant end = Instant.now();
        return fetchExecutions(symbol, clientOrderIds, end.minus(MAX_EXECUTION_LOOKBACK), end);
    }

    /** Returns recent executions for the symbol, including orders without an orderLinkId. */
    public List<FillEvent> fetchRecentExecutions(String symbol) {
        Instant end = Instant.now();
        return fetchExecutions(symbol, null, end.minus(MAX_EXECUTION_LOOKBACK), end);
    }

    /**
     * Fetches all executions in an explicit, bounded interval. The interval is
     * sent to Bybit and every page is consumed. The adapter refuses a range
     * older than the conservative seven-day exchange-history bound instead of
     * returning a plausible but incomplete result.
     */
    @Override
    public List<FillEvent> fetchExecutions(
            String symbol,
            Set<String> clientOrderIds,
            Instant startInclusive,
            Instant endInclusive
    ) {
        validateExecutionRange(startInclusive, endInclusive);
        // null means an intentional whole-symbol query. An empty set means
        // there are no target strategy orders and must never broaden into a
        // wallet-wide query.
        if (clientOrderIds != null && clientOrderIds.isEmpty()) {
            return List.of();
        }
        BybitApiTradeRestClient client = clientFactory.createTradeRestClient();
        Map<String, FillEvent> result = new LinkedHashMap<>();
        List<String> targetIds = clientOrderIds == null
                ? Collections.singletonList(null)
                : clientOrderIds.stream().filter(id -> id != null && !id.isBlank()).toList();
        for (String targetId : targetIds) {
            List<JsonNode> rows = fetchPages(cursor -> TradeOrderRequest.builder()
                            .category(BybitSdkMapper.toCategory(category))
                            .symbol(symbol)
                            .orderLinkId(targetId)
                            .startTime(startInclusive.toEpochMilli())
                            .endTime(endInclusive.toEpochMilli())
                            .limit(PAGE_LIMIT)
                            .cursor(cursor)
                            .build(),
                    client::getTradeHistory,
                    targetId == null ? "execution history" : "execution history for " + targetId);
            for (JsonNode node : rows) {
                FillEvent parsed = parseExecution(node, symbol, targetId == null ? "execution history" : "execution history for " + targetId);
                String clientOrderId = parsed.clientOrderId();
                if (clientOrderIds != null && (clientOrderId.isBlank() || !clientOrderIds.contains(clientOrderId))) {
                    continue;
                }
                String executionId = parsed.executionId();
                FillEvent existing = result.putIfAbsent(executionId, parsed);
                if (existing != null && !sameExecution(existing, parsed)) {
                    throw new IllegalStateException(
                            "Bybit execution history returned contradictory records for execId=" + executionId
                    );
                }
            }
        }
        List<FillEvent> ordered = new ArrayList<>(result.values());
        ordered.sort(Comparator.comparing(FillEvent::fillTime));
        return ordered;
    }

    private FillEvent parseExecution(JsonNode node, String fallbackSymbol, String operation) {
        String symbol = requiredText(node, "symbol", operation);
        String executionId = requiredText(node, "execId", operation);
        String clientOrderId = node.path("orderLinkId").asText("");
        String sideText = requiredText(node, "side", operation);
        TradeSide side;
        if ("Buy".equalsIgnoreCase(sideText)) {
            side = TradeSide.BUY;
        } else if ("Sell".equalsIgnoreCase(sideText)) {
            side = TradeSide.SELL;
        } else {
            throw new IllegalStateException("Bybit " + operation + " has invalid side for execId=" + executionId);
        }
        BigDecimal qty = requiredDecimal(node, "execQty", operation, executionId);
        BigDecimal price = requiredDecimal(node, "execPrice", operation, executionId);
        Instant time = requiredInstant(node, "execTime", operation, executionId);
        BigDecimal value = requiredDecimal(node, "execValue", operation, executionId);
        BigDecimal fee = requiredDecimal(node, "execFee", operation, executionId);
        if (qty.signum() <= 0 || price.signum() <= 0 || value.signum() <= 0 || time.equals(Instant.EPOCH)) {
            throw new IllegalStateException("Bybit " + operation + " has non-positive execution fields for execId=" + executionId);
        }
        return new FillEvent(
                executionId,
                symbol.isBlank() ? fallbackSymbol : symbol,
                clientOrderId,
                side,
                qty,
                price,
                time,
                value,
                fee,
                feeCurrency(node, symbol.isBlank() ? fallbackSymbol : symbol, side)
        );
    }

    private String requiredText(JsonNode node, String field, String operation) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException("Bybit " + operation + " execution is missing " + field);
        }
        return value.asText().trim();
    }

    private BigDecimal requiredDecimal(JsonNode node, String field, String operation, String executionId) {
        String value = requiredText(node, field, operation);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Bybit " + operation + " has invalid " + field + " for execId=" + executionId,
                    e
            );
        }
    }

    private Instant requiredInstant(JsonNode node, String field, String operation, String executionId) {
        String value = requiredText(node, field, operation);
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Bybit " + operation + " has invalid " + field + " for execId=" + executionId,
                    e
            );
        }
    }

    private boolean sameExecution(FillEvent left, FillEvent right) {
        return Objects.equals(left.executionId(), right.executionId())
                && Objects.equals(left.symbol(), right.symbol())
                && Objects.equals(left.clientOrderId(), right.clientOrderId())
                && left.side() == right.side()
                && sameDecimal(left.fillQty(), right.fillQty())
                && sameDecimal(left.fillPrice(), right.fillPrice())
                && Objects.equals(left.fillTime(), right.fillTime())
                && sameDecimal(left.fillValue(), right.fillValue())
                && sameDecimal(left.fee(), right.fee())
                && Objects.equals(left.feeCurrency(), right.feeCurrency());
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private void validateExecutionRange(Instant startInclusive, Instant endInclusive) {
        if (startInclusive == null || endInclusive == null) {
            throw new IllegalArgumentException("execution history range must include start and end");
        }
        if (endInclusive.isBefore(startInclusive)) {
            throw new IllegalArgumentException("execution history end is before start");
        }
        Instant now = Instant.now();
        if (endInclusive.isAfter(now.plusSeconds(5))) {
            throw new IllegalArgumentException("execution history end is in the future");
        }
        // Allow the few milliseconds between capturing the end instant and
        // validating it; the recent-queries below intentionally use exactly
        // the seven-day bound.
        if (startInclusive.isBefore(now.minus(MAX_EXECUTION_LOOKBACK).minusSeconds(5))) {
            throw new IllegalArgumentException(
                    "execution history starts beyond the conservative seven-day Bybit query window; manual history recovery is required"
            );
        }
        if (Duration.between(startInclusive, endInclusive).compareTo(MAX_EXECUTION_LOOKBACK) > 0) {
            throw new IllegalArgumentException("execution history range exceeds the conservative seven-day Bybit query window");
        }
    }

    private List<JsonNode> fetchPages(
            Function<String, TradeOrderRequest> requestFactory,
            Function<TradeOrderRequest, Object> fetcher,
            String operation
    ) {
        List<JsonNode> rows = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        while (true) {
            if (cursor != null && !seenCursors.add(cursor)) {
                throw new IllegalStateException("Bybit " + operation + " pagination repeated cursor");
            }
            JsonNode root = objectMapper.valueToTree(fetcher.apply(requestFactory.apply(cursor)));
            validateResponse(root, operation);
            JsonNode result = root.get("result");
            JsonNode list = result.get("list");
            if (list == null || !list.isArray()) {
                throw new IllegalStateException("Bybit " + operation + " response has no result.list");
            }
            list.forEach(rows::add);
            String nextCursor = result.path("nextPageCursor").asText("").trim();
            if (nextCursor.isEmpty()) {
                return rows;
            }
            if (nextCursor.equals(cursor)) {
                throw new IllegalStateException("Bybit " + operation + " pagination did not advance");
            }
            cursor = nextCursor;
        }
    }

    private void validateResponse(JsonNode root, String operation) {
        if (root == null || root.isNull() || !root.isObject()) {
            throw new IllegalStateException("Bybit " + operation + " returned an invalid response");
        }
        JsonNode retCode = root.get("retCode");
        if (retCode == null || !retCode.isNumber() && !retCode.isTextual()) {
            throw new IllegalStateException("Bybit " + operation + " response has no retCode");
        }
        String code = retCode.asText();
        if (!"0".equals(code)) {
            String message = root.path("retMsg").asText("unknown error");
            throw new IllegalStateException("Bybit " + operation + " failed: retCode=" + code + ", retMsg=" + message);
        }
        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) {
            throw new IllegalStateException("Bybit " + operation + " response has no result");
        }
    }

    private OrderStatus normalizeStatus(String status) {
        if (status == null) {
            return OrderStatus.NEW;
        }
        return switch (status.toUpperCase()) {
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELLED", "CANCELED", "DEACTIVATED", "PARTIALLYFILLEDCANCELED", "PARTIALLY_FILLED_CANCELED" -> OrderStatus.CANCELED;
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

    private String feeCurrency(JsonNode node, String symbol, TradeSide side) {
        String explicit = node.path("feeCurrency").asText("");
        if (!explicit.isBlank()) {
            return explicit.trim().toUpperCase();
        }
        if (category == ProductCategory.SPOT && side == TradeSide.BUY) {
            String base = FillEvent.baseCoin(symbol);
            return base == null ? "USDT" : base;
        }
        return "USDT";
    }
}
