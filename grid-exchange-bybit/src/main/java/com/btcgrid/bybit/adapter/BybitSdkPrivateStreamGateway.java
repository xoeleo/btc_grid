package com.btcgrid.bybit.adapter;

import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateFundingFeeListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.FundingFeeEvent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderUpdateEvent;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import com.bybit.api.client.config.BybitApiConfig;
import com.bybit.api.client.domain.websocket_message.private_channel.ExecutionData;
import com.bybit.api.client.domain.websocket_message.private_channel.OrderData;
import com.bybit.api.client.domain.websocket_message.private_channel.WebSocketExecutionMessage;
import com.bybit.api.client.domain.websocket_message.private_channel.WebSocketOrderMessage;
import com.bybit.api.client.service.BybitApiClientFactory;
import com.bybit.api.client.websocket.httpclient.WebsocketStreamClient;
import com.bybit.api.client.websocket.impl.WebsocketStreamClientImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.WebSocket;

public class BybitSdkPrivateStreamGateway implements BybitPrivateStreamGateway {

    private static final String DEMO_STREAM_DOMAIN = "wss://stream-demo.bybit.com";
    private static final Logger LOGGER = Logger.getLogger(BybitSdkPrivateStreamGateway.class.getName());
    private static final List<String> PRIVATE_TOPICS = List.of("execution", "execution.fast", "order");

    private final BybitSdkConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebsocketStreamClient websocketClient;
    private WebSocket webSocket;

    public BybitSdkPrivateStreamGateway(BybitSdkConfig config) {
        this.config = config;
    }

    @Override
    public void connect(
            PrivateExecutionListener executionListener,
            PrivateFundingFeeListener fundingFeeListener,
            PrivateOrderListener orderListener,
            PrivateStreamLifecycleListener lifecycleListener
    ) {
        String streamDomain = config.demo()
                ? DEMO_STREAM_DOMAIN
                : (config.testnet() ? BybitApiConfig.STREAM_TESTNET_DOMAIN : BybitApiConfig.STREAM_MAINNET_DOMAIN);
        websocketClient = new WebsocketStreamClientImpl(
                config.apiKey(),
                config.apiSecret(),
                streamDomain,
                5,
                "60s",
                config.debugMode(),
                "slf4j",
                message -> handleMessage(message, executionListener, fundingFeeListener, orderListener, lifecycleListener)
        ) {
            @Override
            public void onOpen(WebSocket ws) {
                super.onOpen(ws);
                logInfo("bybit.private-stream.socket-open", Map.of(
                        "streamDomain", streamDomain,
                        "path", BybitApiConfig.V5_PRIVATE,
                        "topics", PRIVATE_TOPICS
                ));
                lifecycleListener.onConnected();
            }

            @Override
            public void onError(Throwable t) {
                super.onError(t);
                logWarning("bybit.private-stream.socket-error", Map.of(
                        "message", t == null || t.getMessage() == null ? "" : t.getMessage()
                ));
                lifecycleListener.onStreamError(t);
            }

            @Override
            public void onClose(WebSocket ws, int code, String reason) {
                super.onClose(ws, code, reason);
                logWarning("bybit.private-stream.socket-close", Map.of(
                        "code", code,
                        "reason", reason == null ? "" : reason
                ));
                lifecycleListener.onDisconnected(code, reason);
            }
        };
        webSocket = websocketClient.getPrivateChannelStream(PRIVATE_TOPICS, BybitApiConfig.V5_PRIVATE);
    }

    @Override
    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "btc-grid reconnect");
        }
        webSocket = null;
        websocketClient = null;
    }

    private void handleMessage(
            String message,
            PrivateExecutionListener executionListener,
            PrivateFundingFeeListener fundingFeeListener,
            PrivateOrderListener orderListener,
            PrivateStreamLifecycleListener lifecycleListener
    ) {
        try {
            var root = objectMapper.readTree(message);
            String op = root.path("op").asText("");
            String topic = root.path("topic").asText("");
            if (!op.isBlank()) {
                if ("pong".equalsIgnoreCase(op)) {
                    return;
                }
                lifecycleListener.onMessageReceived(op, "");
                boolean success = root.path("success").asBoolean(root.path("retCode").asInt(-1) == 0);
                handleOperationAck(op, success, message, lifecycleListener);
                if ("auth".equalsIgnoreCase(op) && success && webSocket != null) {
                    websocketClient.sendSubscribeMessage(webSocket, PRIVATE_TOPICS);
                    logInfo("bybit.private-stream.subscribe.sent", Map.of("topics", PRIVATE_TOPICS));
                }
                return;
            }
            if (!topic.isBlank()) {
                lifecycleListener.onMessageReceived("", topic);
                int dataCount = root.path("data").isArray() ? root.path("data").size() : 0;
                logInfo("bybit.private-stream.topic.raw", Map.of(
                        "topic", topic,
                        "dataCount", dataCount
                ));
            }
            if (topic.startsWith("execution")) {
                WebSocketExecutionMessage executionMessage = objectMapper.readValue(message, WebSocketExecutionMessage.class);
                int dispatched = 0;
                int skippedNoClientOrderId = 0;
                int fundingFees = 0;
                int index = 0;
                for (ExecutionData data : executionMessage.getData()) {
                    JsonNode dataNode = root.path("data").path(index++);
                    if (isFundingFee(data)) {
                        fundingFees++;
                        fundingFeeListener.onFundingFee(new FundingFeeEvent(
                                data.getExecId(),
                                data.getSymbol(),
                                Instant.ofEpochMilli(Long.parseLong(data.getExecTime())),
                                decimalOrZero(data.getExecFee()).negate(),
                                "USDT"
                        ));
                        continue;
                    }
                    if (data.getOrderLinkId() == null || data.getOrderLinkId().isBlank()) {
                        skippedNoClientOrderId++;
                        continue;
                    }
                    executionListener.onExecution(new FillEvent(
                            data.getExecId(),
                            data.getSymbol(),
                            data.getOrderLinkId(),
                            sideOf(data.getSide()),
                            decimal(data.getExecQty()),
                            decimal(data.getExecPrice()),
                            Instant.ofEpochMilli(Long.parseLong(data.getExecTime())),
                            decimalOrZero(data.getExecValue()),
                            decimalOrZero(data.getExecFee()),
                            feeCurrency(dataNode, data)
                    ));
                    dispatched++;
                }
                logInfo("bybit.private-stream.execution.dispatched", Map.of(
                        "topic", topic,
                        "received", executionMessage.getData().size(),
                        "fills", dispatched,
                        "fundingFees", fundingFees,
                        "skippedNoClientOrderId", skippedNoClientOrderId
                ));
            } else if (topic.startsWith("order")) {
                WebSocketOrderMessage orderMessage = objectMapper.readValue(message, WebSocketOrderMessage.class);
                int dispatched = 0;
                int skippedNoClientOrderId = 0;
                for (OrderData data : orderMessage.getData()) {
                    if (data.getOrderLinkId() == null || data.getOrderLinkId().isBlank()) {
                        skippedNoClientOrderId++;
                        continue;
                    }
                    orderListener.onOrderUpdate(new OrderUpdateEvent(
                            data.getSymbol(),
                            data.getOrderLinkId(),
                            data.getOrderId(),
                            normalizeStatus(data.getOrderStatus()),
                            sideOf(data.getSide()),
                            decimalOrZero(data.getPrice()),
                            decimalOrZero(data.getQty()),
                            decimalOrZero(data.getCumExecQty()),
                            Instant.ofEpochMilli(Long.parseLong(data.getUpdatedTime()))
                    ));
                    dispatched++;
                }
                logInfo("bybit.private-stream.order.dispatched", Map.of(
                        "topic", topic,
                        "received", orderMessage.getData().size(),
                        "orders", dispatched,
                        "skippedNoClientOrderId", skippedNoClientOrderId
                ));
            }
        } catch (IOException e) {
            logWarning("bybit.private-stream.message-parse-failed", Map.of("message", e.getMessage()));
            lifecycleListener.onStreamError(new IllegalStateException("Failed to parse private stream message", e));
        }
    }

    private void handleOperationAck(String op, boolean success, String message, PrivateStreamLifecycleListener lifecycleListener) {
        String eventName = switch (op.toLowerCase()) {
            case "auth" -> success ? "bybit.private-stream.auth.succeeded" : "bybit.private-stream.auth.failed";
            case "subscribe" -> success ? "bybit.private-stream.subscribe.succeeded" : "bybit.private-stream.subscribe.failed";
            default -> "bybit.private-stream.operation";
        };
        Map<String, Object> payload = Map.of(
                "op", op,
                "success", success,
                "message", message
        );
        if (success) {
            logInfo(eventName, payload);
            if ("subscribe".equalsIgnoreCase(op)) {
                lifecycleListener.onSubscribed();
            }
        } else {
            logWarning(eventName, payload);
            if ("auth".equalsIgnoreCase(op) || "subscribe".equalsIgnoreCase(op)) {
                lifecycleListener.onStreamError(new IllegalStateException("Bybit private stream " + op + " failed"));
            }
        }
    }

    private void logInfo(String event, Map<String, Object> payload) {
        LOGGER.info(() -> event + " " + payload);
    }

    private void logWarning(String event, Map<String, Object> payload) {
        LOGGER.log(Level.WARNING, event + " " + payload);
    }

    private boolean isFundingFee(ExecutionData data) {
        return "Funding".equalsIgnoreCase(data.getExecType())
                || "FundingFee".equalsIgnoreCase(data.getExecType());
    }

    private TradeSide sideOf(String side) {
        return "Buy".equalsIgnoreCase(side) ? TradeSide.BUY : TradeSide.SELL;
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

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private BigDecimal decimalOrZero(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private String feeCurrency(JsonNode node, ExecutionData data) {
        String explicit = node.path("feeCurrency").asText("");
        if (!explicit.isBlank()) {
            return explicit.trim().toUpperCase();
        }
        if ("spot".equalsIgnoreCase(data.getCategory()) && sideOf(data.getSide()) == TradeSide.BUY) {
            String base = FillEvent.baseCoin(data.getSymbol());
            return base == null ? "USDT" : base;
        }
        return "USDT";
    }
}
