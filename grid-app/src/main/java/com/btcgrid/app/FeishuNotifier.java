package com.btcgrid.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A bounded, daemon queue keeps webhook I/O off the trading thread. */
final class FeishuNotifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100), runnable -> {
                Thread thread = new Thread(runnable, "rebalance-feishu");
                thread.setDaemon(true);
                return thread;
            });
    private final String webhook;
    private final String secret;
    private final EventLogger logger;

    FeishuNotifier(String webhook, EventLogger logger) {
        this(webhook, null, logger);
    }

    FeishuNotifier(String webhook, String secret, EventLogger logger) {
        this.webhook = webhook;
        this.secret = secret;
        this.logger = logger;
    }

    static FeishuNotifier fromEnvironment(Map<String, String> env, EventLogger logger) {
        // Select the URL and its matching secret together; never sign one bot with another bot's key.
        String prefix = nonBlank(env.get("REBALANCE_FEISHU_WEBHOOK_URL")) ? "REBALANCE_" : "";
        return new FeishuNotifier(env.get(prefix + "FEISHU_WEBHOOK_URL"),
                env.get(prefix + "FEISHU_WEBHOOK_SECRET"), logger);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    static String payload(String message, String secret, long timestamp) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", message));
        if (nonBlank(secret)) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            payload.put("timestamp", Long.toString(timestamp));
            payload.put("sign", Base64.getEncoder().encodeToString(mac.doFinal(new byte[0])));
        }
        return JSON.writeValueAsString(payload);
    }

    void send(String message) {
        if (webhook == null || webhook.isBlank()) {
            logger.warn("rebalance.feishu.disabled", Map.of("reason", "FEISHU_WEBHOOK_URL / REBALANCE_FEISHU_WEBHOOK_URL is not configured"));
            return;
        }
        try {
            WORKER.execute(() -> deliver(message));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.error("rebalance.feishu.failed", Map.of("reason", "notification queue is full"));
        }
    }

    private void deliver(String message) {
        try {
            String body = payload(message, secret, Instant.now().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .build().send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(response.body());
            int code = result == null ? -1 : result.path("code").asInt(result.path("StatusCode").asInt(-1));
            if (response.statusCode() / 100 != 2 || code != 0) {
                logger.error("rebalance.feishu.failed", Map.of("httpStatus", response.statusCode(), "code", code));
                return;
            }
            logger.info("rebalance.feishu.sent", Map.of());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Exception messages may contain the secret webhook URL.
            logger.error("rebalance.feishu.failed", Map.of("reason", e.getClass().getSimpleName()));
        }
    }
}
