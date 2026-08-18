package com.btcgrid.bybit.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.btcgrid.common.live.PrivateStreamLifecycleListener;
import com.bybit.api.client.security.HmacSHA256Signer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.jupiter.api.Test;

class BybitSdkPrivateStreamGatewayTest {

    @Test
    void buildsOfficialPrivateStreamAuthenticationPayload() {
        long nowMillis = 1_725_000_000_000L;
        String secret = "test-secret";

        Map<String, Object> message =
                BybitSdkPrivateStreamGateway.authMessage("test-key", secret, nowMillis);

        assertEquals("auth", message.get("op"));
        @SuppressWarnings("unchecked")
        List<Object> args = (List<Object>) message.get("args");
        assertEquals("test-key", args.get(0));
        assertEquals(nowMillis + 10_000L, args.get(1));
        assertEquals(
                HmacSHA256Signer.getSignature("GET/realtime" + (nowMillis + 10_000L), secret),
                args.get(2)
        );
    }

    @Test
    void buildsOneSubscriptionRequestForAllRequiredTopics() {
        Map<String, Object> first = BybitSdkPrivateStreamGateway.subscribeMessage();
        Map<String, Object> second = BybitSdkPrivateStreamGateway.subscribeMessage();

        assertEquals("subscribe", first.get("op"));
        assertEquals(List.of("execution", "execution.fast", "order"), first.get("args"));
        assertNotEquals(first.get("req_id"), second.get("req_id"));
    }

    @Test
    void subscribesOnlyAfterSuccessfulAuthenticationAndWaitsForSubscribeAck() {
        BybitSdkPrivateStreamGateway gateway = new BybitSdkPrivateStreamGateway(
                new BybitSdkConfig("key", "secret", false, false, false, false, 5_000L)
        );
        RecordingWebSocket socket = new RecordingWebSocket();
        RecordingLifecycle lifecycle = new RecordingLifecycle();

        gateway.handleMessage(
                "{\"success\":true,\"ret_msg\":\"\",\"op\":\"auth\",\"conn_id\":\"c1\"}",
                socket,
                fill -> {
                },
                fee -> {
                },
                order -> {
                },
                lifecycle
        );

        assertEquals(1, socket.messages.size());
        assertEquals("subscribe", jsonField(socket.messages.get(0), "op"));
        assertEquals(0, lifecycle.subscribed);

        gateway.handleMessage(
                "{\"success\":true,\"ret_msg\":\"\",\"op\":\"subscribe\",\"conn_id\":\"c1\"}",
                socket,
                fill -> {
                },
                fee -> {
                },
                order -> {
                },
                lifecycle
        );

        assertEquals(1, socket.messages.size());
        assertEquals(1, lifecycle.subscribed);
        assertEquals(0, lifecycle.errors);
    }

    @Test
    void authenticationFailureDoesNotSubscribeAndSignalsLifecycleError() {
        BybitSdkPrivateStreamGateway gateway = new BybitSdkPrivateStreamGateway(
                new BybitSdkConfig("key", "secret", false, false, false, false, 5_000L)
        );
        RecordingWebSocket socket = new RecordingWebSocket();
        RecordingLifecycle lifecycle = new RecordingLifecycle();

        gateway.handleMessage(
                "{\"success\":false,\"ret_msg\":\"invalid sign\",\"op\":\"auth\",\"conn_id\":\"c1\"}",
                socket,
                fill -> {
                },
                fee -> {
                },
                order -> {
                },
                lifecycle
        );

        assertEquals(0, socket.messages.size());
        assertEquals(0, lifecycle.subscribed);
        assertEquals(1, lifecycle.errors);
    }

    private static String jsonField(String json, String field) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path(field).asText();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingLifecycle implements PrivateStreamLifecycleListener {
        private int subscribed;
        private int errors;

        @Override
        public void onConnected() {
        }

        @Override
        public void onDisconnected(int code, String reason) {
        }

        @Override
        public void onStreamError(Throwable throwable) {
            errors++;
        }

        @Override
        public void onSubscribed() {
            subscribed++;
        }
    }

    private static final class RecordingWebSocket implements WebSocket {
        private final List<String> messages = new ArrayList<>();

        @Override
        public Request request() {
            return new Request.Builder().url("https://example.test").build();
        }

        @Override
        public long queueSize() {
            return 0;
        }

        @Override
        public boolean send(String text) {
            messages.add(text);
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            return true;
        }

        @Override
        public void cancel() {
        }
    }
}
