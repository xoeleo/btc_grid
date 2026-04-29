package com.btcgrid.bybit.adapter;

import com.btcgrid.common.live.PrivateExecutionListener;
import com.btcgrid.common.live.PrivateOrderListener;
import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderUpdateEvent;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import okhttp3.WebSocket;

public class BybitSdkPrivateStreamGateway implements BybitPrivateStreamGateway {

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
            PrivateOrderListener orderListener,
            PrivateStreamLifecycleListener lifecycleListener
    ) {
        String streamDomain = config.testnet() ? BybitApiConfig.STREAM_TESTNET_DOMAIN : BybitApiConfig.STREAM_MAINNET_DOMAIN;
        websocketClient = new WebsocketStreamClientImpl(
                config.apiKey(),
                config.apiSecret(),
                streamDomain,
                5,
                "60s",
                config.debugMode(),
                "slf4j",
                message -> handleMessage(message, executionListener, orderListener)
        ) {
            @Override
            public void onOpen(WebSocket ws) {
                super.onOpen(ws);
                lifecycleListener.onConnected();
            }

            @Override
            public void onError(Throwable t) {
                super.onError(t);
                lifecycleListener.onStreamError(t);
            }

            @Override
            public void onClose(WebSocket ws, int code, String reason) {
                super.onClose(ws, code, reason);
                lifecycleListener.onDisconnected(code, reason);
            }
        };
        webSocket = websocketClient.getPrivateChannelStream(List.of("execution", "order"), BybitApiConfig.V5_PRIVATE);
    }

    @Override
    public void close() {
        if (websocketClient != null && webSocket != null) {
            websocketClient.onClose(webSocket, 1000, "btc-grid shutdown");
        }
    }

    private void handleMessage(
            String message,
            PrivateExecutionListener executionListener,
            PrivateOrderListener orderListener
    ) {
        try {
            String topic = objectMapper.readTree(message).path("topic").asText("");
            if (topic.startsWith("execution")) {
                WebSocketExecutionMessage executionMessage = objectMapper.readValue(message, WebSocketExecutionMessage.class);
                for (ExecutionData data : executionMessage.getData()) {
                    if (data.getOrderLinkId() == null || data.getOrderLinkId().isBlank()) {
                        continue;
                    }
                    executionListener.onExecution(new FillEvent(
                            data.getExecId(),
                            data.getSymbol(),
                            data.getOrderLinkId(),
                            sideOf(data.getSide()),
                            decimal(data.getExecQty()),
                            decimal(data.getExecPrice()),
                            Instant.ofEpochMilli(Long.parseLong(data.getExecTime()))
                    ));
                }
            } else if (topic.startsWith("order")) {
                WebSocketOrderMessage orderMessage = objectMapper.readValue(message, WebSocketOrderMessage.class);
                for (OrderData data : orderMessage.getData()) {
                    if (data.getOrderLinkId() == null || data.getOrderLinkId().isBlank()) {
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
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse private stream message: " + message, e);
        }
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
}
