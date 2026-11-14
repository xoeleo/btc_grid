package com.btcgrid.app;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeishuNotifierTest {
    @Test
    void signsWithPredictCompatibleTimestampAndHmac() throws Exception {
        var body = new ObjectMapper().readTree(FeishuNotifier.payload("再平衡", "test-secret", 1599360473L));
        assertEquals("1599360473", body.path("timestamp").asText());
        assertEquals("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=", body.path("sign").asText());
        var unsigned = new ObjectMapper().readTree(FeishuNotifier.payload("再平衡", " ", 1599360473L));
        assertFalse(unsigned.has("sign"));
        assertFalse(unsigned.has("timestamp"));
    }

    @Test
    void sendsTextAndChecksBusinessResponse() throws Exception {
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            byte[] body = "{\"code\":19001}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        EventLogger logger = new EventLogger() {
            public void info(String type, Map<String, Object> payload) { events.add(type); }
            public void warn(String type, Map<String, Object> payload) { events.add(type); }
            public void error(String type, Map<String, Object> payload) { events.add(type); }
        };
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            FeishuNotifier.fromEnvironment(Map.of("FEISHU_WEBHOOK_URL", url + "/ok",
                    "FEISHU_WEBHOOK_SECRET", "test-secret"), logger).send("再平衡大额成交");
            assertEquals("rebalance.feishu.sent", events.poll(15, TimeUnit.SECONDS));
            var request = new ObjectMapper().readTree(requests.poll(1, TimeUnit.SECONDS));
            assertEquals("text", request.path("msg_type").asText());
            assertEquals("再平衡大额成交", request.path("content").path("text").asText());
            assertTrue(request.has("sign"));
            assertTrue(request.has("timestamp"));
            FeishuNotifier.fromEnvironment(Map.of("FEISHU_WEBHOOK_URL", url + "/error",
                    "FEISHU_WEBHOOK_SECRET", "different-bot-secret",
                    "REBALANCE_FEISHU_WEBHOOK_URL", url + "/ok"), logger).send("独立配置");
            assertEquals("rebalance.feishu.sent", events.poll(15, TimeUnit.SECONDS));
            var override = new ObjectMapper().readTree(requests.poll(1, TimeUnit.SECONDS));
            assertFalse(override.has("sign"));
            new FeishuNotifier(url + "/error", logger).send("再平衡大额成交");
            assertEquals("rebalance.feishu.failed", events.poll(15, TimeUnit.SECONDS));
            new FeishuNotifier(null, logger).send("再平衡大额成交");
            assertEquals("rebalance.feishu.disabled", events.poll(1, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }
}
