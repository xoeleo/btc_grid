package com.btcgrid.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.btcgrid.common.model.OrderStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

public class HttpStatusServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier;
    private final LiveControlService liveControlService;
    private final EventLogger eventLogger;
    private final String httpUsername;
    private final String httpPassword;
    private final String httpRealm;
    private final String ssoSecret;
    private final String sessionToken = UUID.randomUUID().toString();
    private final String gridUiUrl;
    private final String rebalanceUiUrl;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public HttpStatusServer(int port, RuntimeStatusProvider runtimeStatusProvider, EventLogger eventLogger) {
        this("0.0.0.0", port, runtimeStatusProvider::snapshot, null, eventLogger);
    }

    public HttpStatusServer(
            String host,
            int port,
            Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier,
            LiveControlService liveControlService,
            EventLogger eventLogger
    ) {
        this(
                host,
                port,
                runtimeStatusSupplier,
                liveControlService,
                eventLogger,
                System.getenv("GRID_HTTP_USERNAME"),
                System.getenv("GRID_HTTP_PASSWORD"),
                System.getenv("GRID_MANUAL_UI_URL"),
                System.getenv("GRID_REBALANCE_UI_URL"),
                System.getenv("QUANT_HOME_SSO_KEY_B64")
        );
    }

    HttpStatusServer(
            String host,
            int port,
            Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier,
            LiveControlService liveControlService,
            EventLogger eventLogger,
            String httpUsername,
            String httpPassword,
            String gridUiUrl,
            String rebalanceUiUrl
    ) {
        this(
                host,
                port,
                runtimeStatusSupplier,
                liveControlService,
                eventLogger,
                httpUsername,
                httpPassword,
                gridUiUrl,
                rebalanceUiUrl,
                ""
        );
    }

    HttpStatusServer(
            String host,
            int port,
            Supplier<RuntimeStatusSnapshot> runtimeStatusSupplier,
            LiveControlService liveControlService,
            EventLogger eventLogger,
            String httpUsername,
            String httpPassword,
            String gridUiUrl,
            String rebalanceUiUrl,
            String ssoSecret
    ) {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create HTTP status server on " + host + ":" + port, e);
        }
        this.httpExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "grid-http-handler");
            thread.setDaemon(true);
            return thread;
        });
        this.httpServer.setExecutor(httpExecutor);
        this.runtimeStatusSupplier = runtimeStatusSupplier;
        this.liveControlService = liveControlService;
        this.eventLogger = eventLogger;
        this.httpUsername = normalized(httpUsername);
        this.httpPassword = normalized(httpPassword);
        this.httpRealm = "BTC Grid " + httpServer.getAddress().getPort();
        this.ssoSecret = normalized(ssoSecret);
        this.gridUiUrl = normalized(gridUiUrl);
        this.rebalanceUiUrl = normalized(rebalanceUiUrl);
        if (this.httpUsername.isBlank() != this.httpPassword.isBlank()) {
            throw new IllegalStateException("GRID_HTTP_USERNAME and GRID_HTTP_PASSWORD must both be set");
        }
        registerContexts();
    }

    public void start() {
        httpServer.start();
        eventLogger.info("http.started", Map.of(
                "host", httpServer.getAddress().getHostString(),
                "port", httpServer.getAddress().getPort()
        ));
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        httpServer.stop(0);
        httpExecutor.shutdownNow();
    }

    private void registerContexts() {
        httpServer.createContext("/login", this::handleLogin);
        createProtectedContext("/", this::writeIndex);
        createProtectedContext("/status", exchange -> writeJson(exchange, runtimeStatusSupplier.get()));
        createProtectedContext("/ledger", exchange -> writeJson(exchange, runtimeStatusSupplier.get().ledgerSnapshot()));
        createProtectedContext("/runtime", exchange -> writeJson(exchange, runtimeStatusSupplier.get().runtimeState()));
        createProtectedContext("/orders", exchange -> writeJson(exchange, runtimeStatusSupplier.get().workingOrders()));
        httpServer.createContext("/health", exchange -> writeJson(exchange, Map.of("ok", true)));
        createProtectedContext("/api/rebalance-price-range", this::handleRebalancePriceRange);
        if (liveControlService != null) {
            createProtectedContext("/api/control", exchange -> writeJson(exchange, liveControlService.controlState()));
            createProtectedContext("/api/active-status", exchange -> writeJson(exchange, activeStatusSnapshot()));
            createProtectedContext("/api/sol-contract-boll-monitor", exchange -> writeJson(exchange, liveControlService.solContractBollMonitorState()));
            createProtectedContext("/api/rebalance-config", exchange -> {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, liveControlService.rebalanceConfig());
                    return;
                }
                requirePost(exchange);
                LiveControlService.RebalanceConfigRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.RebalanceConfigRequest.class);
                writeJson(exchange, liveControlService.saveRebalanceConfig(request));
            });
            createProtectedContext("/api/rebalance-monitors", exchange -> {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, liveControlService.rebalanceMonitors());
                    return;
                }
                requirePost(exchange);
                java.util.List<RebalanceMonitorRule> rules = objectMapper.readValue(
                        exchange.getRequestBody(), new TypeReference<java.util.List<RebalanceMonitorRule>>() {});
                writeJson(exchange, liveControlService.saveRebalanceMonitors(rules));
            });
            createProtectedContext("/api/rebalance-state", exchange -> writeJson(exchange, liveControlService.rebalanceState()));
            createProtectedContext("/api/rebalance-orders", exchange -> writeJson(exchange, liveControlService.rebalanceOrders()));
            createProtectedContext("/api/rebalance-history", exchange -> writeJson(exchange, liveControlService.rebalanceHistory()));
            createProtectedContext("/api/rebalance-fills", exchange -> writeJson(exchange, liveControlService.rebalanceFillsPage(
                    queryInt(exchange, "limit", 50),
                    queryInt(exchange, "offset", 0),
                    queryParam(exchange, "price")
            )));
            createProtectedContext("/api/rebalance-funding-fees", exchange -> writeJson(
                    exchange,
                    liveControlService.rebalanceFundingFeesPage(
                            queryInt(exchange, "limit", 50),
                            queryInt(exchange, "offset", 0)
                    )
            ));
            createProtectedContext("/api/rebalance-cycles", exchange -> writeJson(exchange, liveControlService.rebalanceCyclesPage(
                    queryInt(exchange, "limit", 50),
                    queryInt(exchange, "offset", 0)
            )));
            createProtectedContext("/api/rebalance-current-cycle", exchange -> writeJson(exchange, liveControlService.rebalanceCurrentCycle()));
            createProtectedContext("/api/rebalance-cycle-fills", exchange -> writeJson(exchange, liveControlService.rebalanceCycleFills(queryParam(exchange, "executionId"))));
            createProtectedContext("/api/rebalance-start", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.startRebalance());
            });
            createProtectedContext("/api/rebalance-stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.stopRebalance());
            });
            createProtectedContext("/api/rebalance-close-cycle", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.closeRebalanceCycle());
            });
            createProtectedContext("/api/rebalance-reduce-cycle", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.reduceRebalanceCycle());
            });
            createProtectedContext("/api/fills", exchange -> writeJson(exchange, liveControlService.recentFills()));
            createProtectedContext("/api/recent-fills", exchange -> writeJson(
                    exchange,
                    tail(liveControlService.recentFills(), queryInt(exchange, "limit", 200))
            ));
            createProtectedContext("/api/fills-page", exchange -> writeJson(exchange, liveControlService.fillsPage(
                    queryInt(exchange, "limit", 50),
                    queryInt(exchange, "offset", 0),
                    queryParam(exchange, "minPrice"),
                    queryParam(exchange, "maxPrice")
            )));
            createProtectedContext("/api/funding-fees", exchange -> writeJson(exchange, liveControlService.fundingFees()));
            createProtectedContext("/api/cycles", exchange -> writeJson(exchange, liveControlService.recentCycles()));
            createProtectedContext("/api/current-cycle", exchange -> writeJson(exchange, liveControlService.currentCycle()));
            createProtectedContext("/api/cycle-fills", exchange -> writeJson(exchange, liveControlService.cycleFillsPage(
                    queryParam(exchange, "executionId"),
                    queryInt(exchange, "limit", 50),
                    queryInt(exchange, "offset", 0)
            )));
            createProtectedContext("/api/cycle-config", exchange -> writeJson(exchange, liveControlService.cycleConfig(queryParam(exchange, "executionId"))));
            createProtectedContext("/api/start", exchange -> {
                requirePost(exchange);
                LiveControlService.StartRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.StartRequest.class);
                writeJson(exchange, liveControlService.start(request));
            });
            createProtectedContext("/api/stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.stop());
            });
            createProtectedContext("/api/resume", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.resume());
            });
            createProtectedContext("/api/flatten-stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.flattenAndStop());
            });
            createProtectedContext("/api/unlock-flatten-stop", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.unlockFlattenStop());
            });
            createProtectedContext("/api/reconcile-flatten-cycle", exchange -> {
                requirePost(exchange);
                writeJson(exchange, liveControlService.reconcileFlattenCycle());
            });
            createProtectedContext("/api/cancel-order", exchange -> {
                requirePost(exchange);
                LiveControlService.CancelOrderRequest request = objectMapper.readValue(exchange.getRequestBody(), LiveControlService.CancelOrderRequest.class);
                writeJson(exchange, liveControlService.cancelOrder(request));
            });
        }
    }

    private synchronized RebalancePriceRangeStore priceRangeStore() {
        String configured = System.getProperty("btcgrid.priceRangeConfigFile", "");
        if (configured.isBlank() && liveControlService != null) {
            String mainPath = objectMapper.valueToTree(liveControlService.rebalanceConfig())
                    .path("config").path("configFilePath").asText("");
            if (!mainPath.isBlank()) configured = java.nio.file.Path.of(mainPath).toAbsolutePath()
                    .getParent().resolve("rebalance-price-range.properties").toString();
        }
        if (configured.isBlank()) configured = "runtime/mainnet/rebalance-price-range.properties";
        return new RebalancePriceRangeStore(java.nio.file.Path.of(configured));
    }

    private void handleRebalancePriceRange(HttpExchange exchange) throws IOException {
        try {
            RebalancePriceRangeStore store = priceRangeStore();
            RebalancePriceRangeStore.Range range;
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                range = store.load();
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonNode body = objectMapper.readTree(exchange.getRequestBody());
                if (body == null) throw new IllegalArgumentException("请输入最低价和最高价");
                range = store.save(new RebalancePriceRangeStore.Range(
                        new java.math.BigDecimal(body.path("lowPrice").asText("")),
                        new java.math.BigDecimal(body.path("highPrice").asText(""))));
            } else {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
            }
            java.math.BigDecimal price = null;
            if (liveControlService != null) {
                JsonNode node = objectMapper.valueToTree(liveControlService.rebalanceState()).path("state").path("lastPrice");
                if (!node.isMissingNode() && !node.isNull()) price = new java.math.BigDecimal(node.asText());
            }
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("ok", true); result.put("config", range); result.put("lastPrice", price);
            result.put("theoreticalRatio", range.targetRatio(price));
            writeJson(exchange, result);
        } catch (IllegalArgumentException | IOException e) {
            int code = e instanceof IllegalArgumentException ? 400 : 500;
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of("ok", false, "message", e.getMessage() == null ? "价格配置保存失败" : e.getMessage()));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        }
    }

    private void createProtectedContext(String path, HttpHandler handler) {
        httpServer.createContext(path, exchange -> {
            if (!authenticate(exchange)) {
                return;
            }
            handler.handle(exchange);
        });
    }

    private RuntimeStatusSnapshot activeStatusSnapshot() {
        RuntimeStatusSnapshot snapshot = runtimeStatusSupplier.get();
        return new RuntimeStatusSnapshot(
                snapshot.generatedAt(),
                snapshot.symbol(),
                snapshot.runtimeState(),
                snapshot.ledgerSnapshot(),
                snapshot.latestMarketSnapshot(),
                snapshot.processedExecutionCount(),
                snapshot.workingOrders().stream()
                        .filter(order -> order.status() == OrderStatus.NEW)
                        .toList()
        );
    }

    private <T> java.util.List<T> tail(java.util.List<T> values, int requestedLimit) {
        int limit = Math.min(500, Math.max(1, requestedLimit));
        int start = Math.max(0, values.size() - limit);
        return java.util.List.copyOf(values.subList(start, values.size()));
    }

    private boolean authenticate(HttpExchange exchange) throws IOException {
        if (httpUsername.isBlank()) {
            return true;
        }
        if (handleSsoHandoff(exchange)) {
            return false;
        }
        if (hasSessionCookie(exchange)) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String credentials = new String(
                        Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8
                );
                int separator = credentials.indexOf(':');
                if (separator >= 0
                        && constantTimeEquals(httpUsername, credentials.substring(0, separator))
                        && constantTimeEquals(httpPassword, credentials.substring(separator + 1))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid Base64 is handled as an authentication failure.
            }
        }
        if (isBrowserPage(exchange)) {
            redirect(exchange, "/login?next=" + java.net.URLEncoder.encode(
                    exchange.getRequestURI().getPath(),
                    StandardCharsets.UTF_8
            ));
            return false;
        }
        byte[] body = "Authentication required".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"" + httpRealm + "\", charset=\"UTF-8\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(401, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
        return false;
    }

    private boolean handleSsoHandoff(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String audience = switch (path) {
            case "/grid" -> "grid-direct";
            case "/rebalance" -> "rebalance-direct";
            default -> "";
        };
        if (ssoSecret.isBlank()
                || !"GET".equalsIgnoreCase(exchange.getRequestMethod())
                || audience.isBlank()) {
            return false;
        }
        String token = queryParam(exchange, "token");
        if (!verifySsoHandoff(token, ssoSecret, System.currentTimeMillis() / 1000L, audience)) {
            return false;
        }
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                "btc_grid_session=" + sessionToken + "; Path=/; HttpOnly; SameSite=Lax"
        );
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        redirect(exchange, path);
        return true;
    }

    static boolean verifySsoHandoff(String token, String secretBase64, long nowEpochSeconds) {
        return verifySsoHandoff(token, secretBase64, nowEpochSeconds, "grid-direct");
    }

    static boolean verifySsoHandoff(
            String token,
            String secretBase64,
            long nowEpochSeconds,
            String expectedAudience
    ) {
        if (token == null || token.isBlank() || secretBase64 == null || secretBase64.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        try {
            byte[] secret = Base64.getDecoder().decode(secretBase64);
            if (secret.length < 32) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] supplied = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                return false;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long issuedAt = claims.path("iat").asLong(0L);
            long expiresAt = claims.path("exp").asLong(0L);
            return "HS256".equals(header.path("alg").asText())
                    && "QH-HANDOFF".equals(header.path("typ").asText())
                    && "quant-home".equals(claims.path("iss").asText())
                    && expectedAudience.equals(claims.path("aud").asText())
                    && !claims.path("sub").asText().isBlank()
                    && issuedAt <= nowEpochSeconds + 30L
                    && expiresAt >= nowEpochSeconds
                    && expiresAt - issuedAt <= 60L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasSessionCookie(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return false;
        }
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2
                    && "btc_grid_session".equals(pair[0])
                    && constantTimeEquals(sessionToken, pair[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean isBrowserPage(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return "GET".equalsIgnoreCase(exchange.getRequestMethod())
                && ("/".equals(path) || "/grid".equals(path) || "/rebalance".equals(path));
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (httpUsername.isBlank()) {
            redirect(exchange, safeLoginTarget(queryParam(exchange, "next")));
            return;
        }
        String next = safeLoginTarget(queryParam(exchange, "next"));
        boolean invalid = false;
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            next = safeLoginTarget(form.get("next"));
            if (constantTimeEquals(httpUsername, form.getOrDefault("username", ""))
                    && constantTimeEquals(httpPassword, form.getOrDefault("password", ""))) {
                exchange.getResponseHeaders().add(
                        "Set-Cookie",
                        "btc_grid_session=" + sessionToken + "; Path=/; HttpOnly; SameSite=Strict"
                );
                redirect(exchange, next);
                return;
            }
            invalid = true;
        } else if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String message = invalid ? "<p class=\"error\">用户名或密码错误</p>" : "";
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>BTC Grid 登录</title>
                  <style>
                    body{font-family:system-ui,sans-serif;background:#f6f3ec;margin:0;display:grid;place-items:center;min-height:100vh;color:#222}
                    form{width:min(380px,calc(100vw - 40px));background:#fffaf0;border:1px solid #dfd3bd;border-radius:12px;padding:28px;display:grid;gap:16px}
                    label{display:grid;gap:6px} input{font:inherit;padding:10px;border:1px solid #cdbf9f;border-radius:6px}
                    button{font:inherit;padding:11px;border:0;border-radius:6px;background:#176b4d;color:white;font-weight:700}
                    .error{color:#b3261e;margin:0}
                  </style>
                </head>
                <body>
                  <form method="post" action="/login">
                    <h1>BTC Grid 登录</h1>
                    %s
                    <input type="hidden" name="next" value="%s">
                    <label>用户名<input name="username" autocomplete="username" required autofocus></label>
                    <label>密码<input name="password" type="password" autocomplete="current-password" required></label>
                    <button type="submit">登录</button>
                  </form>
                </body>
                </html>
                """.formatted(message, escapeHtml(next));
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        if (invalid) {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(401, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
            return;
        }
        writeResponse(exchange, "text/html; charset=utf-8", bytes);
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private String safeLoginTarget(String target) {
        return "/rebalance".equals(target) ? "/rebalance" : "/grid";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private void requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            byte[] bytes = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
            throw new IOException("Method not allowed");
        }
    }

    private void writeIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/grid".equals(path) && !gridUiUrl.isBlank()) {
            redirect(exchange, gridUiUrl);
            return;
        }
        if ("/rebalance".equals(path) && !rebalanceUiUrl.isBlank()) {
            redirect(exchange, rebalanceUiUrl);
            return;
        }
        String html = "/grid".equals(path) || "/rebalance".equals(path) ? indexHtml() : landingHtml();
        writeResponse(exchange, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private String landingHtml() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>BTC Grid 入口</title>
                  <style>
                    * { box-sizing: border-box; }
                    body { font-family: system-ui, sans-serif; margin: 0; padding: 28px; background: #f6f3ec; color: #222; }
                    .wrap { max-width: 820px; margin: 0 auto; }
                    .panel { background: #fffaf0; border: 1px solid #dfd3bd; border-radius: 10px; padding: 22px; }
                    .entry { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; margin-top: 18px; }
                    a { display: block; text-decoration: none; color: #222; background: #fff; border: 1px solid #dfd3bd; border-radius: 8px; padding: 18px; }
                    a strong { display: block; font-size: 20px; margin-bottom: 8px; }
                    a span { color: #6f5a30; }
                  </style>
                </head>
                <body>
                  <div class="wrap">
                    <div class="panel">
                      <h1>BTC Grid 入口</h1>
                      <div class="entry">
                        <a href="%s"><strong>手动网格</strong><span>启动、停止、清仓、查看 Cycle 和成交。</span></a>
                        <a href="%s"><strong>BTC 再平衡</strong><span>配置资产占比目标，独立监控和运行再平衡。</span></a>
                      </div>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                        gridUiUrl.isBlank() ? "/grid" : gridUiUrl,
                        rebalanceUiUrl.isBlank() ? "/rebalance" : rebalanceUiUrl
                );
    }

    private String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (name.equals(decode(key))) {
                return equals < 0 ? "" : decode(part.substring(equals + 1));
            }
        }
        return "";
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int queryInt(HttpExchange exchange, String name, int fallback) {
        String value = queryParam(exchange, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void writeJson(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        writeResponse(exchange, "application/json; charset=utf-8", bytes);
    }

    private void writeResponse(HttpExchange exchange, String contentType, byte[] bytes) throws IOException {
        byte[] responseBytes = bytes;
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Vary", "Accept-Encoding");
        if (acceptsGzip(exchange)) {
            responseBytes = gzip(bytes);
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }

    private boolean acceptsGzip(HttpExchange exchange) {
        String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return false;
        }
        for (String encoding : acceptEncoding.split(",")) {
            String[] parts = encoding.trim().split(";", 2);
            if (!"gzip".equalsIgnoreCase(parts[0].trim())) {
                continue;
            }
            return parts.length < 2 || !parts[1].replace(" ", "").equalsIgnoreCase("q=0");
        }
        return false;
    }

    private byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes);
        }
        return compressed.toByteArray();
    }

    private String indexHtml() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>BTC Grid 控制台</title>
                  <style>
                    * { box-sizing: border-box; }
                    body { font-family: system-ui, sans-serif; margin: 0; padding: 18px; background: #f6f3ec; color: #222; }
                    .card { background: #fffaf0; border: 1px solid #dfd3bd; border-radius: 10px; padding: 18px; width: 100%; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
                    .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; margin: 14px 0; }
                    .metric { background: #ffffff; border: 1px solid #dfd3bd; border-radius: 8px; padding: 10px 12px; }
                    .metric span { display: block; color: #726650; font-size: 12px; }
                    .metric strong { display: block; margin-top: 4px; font-size: 18px; }
                    .metric-combined { grid-column: span 2; }
                    .metric-combined .metric-lines { display: grid; gap: 5px; margin-top: 7px; }
                    .metric-combined .metric-line { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
                    .metric-combined .metric-line strong { margin: 0; font-size: 15px; text-align: right; }
                    @media (max-width: 600px) { .metric-combined { grid-column: auto; } }
                    .cycle-price-scenario-row { grid-column: span 5; min-width: 0; display: grid; grid-template-columns: minmax(220px, 1fr) minmax(0, 2fr); gap: 10px; }
                    @media (max-width: 1000px) { .cycle-price-scenario-row { grid-column: 1 / -1; } }
                    @media (max-width: 680px) { .cycle-price-scenario-row { grid-template-columns: minmax(0, 1fr); } }
                    .metric-scenario { min-width: 0; }
                    .metric-scenario > span { white-space: nowrap; }
                    .metric-scenario > strong { overflow-x: auto; font-size: clamp(14px, 1.6vw, 18px); }
                    .metric-scenario > strong > span { display: block; white-space: nowrap; color: inherit; font-size: inherit; }
                    .metric-price-summary { grid-column: span 2; min-width: 0; }
                    .cycle-price-summary > span { white-space: nowrap; overflow-x: auto; }
                    @media (max-width: 400px) { .metric-price-summary { grid-column: 1 / -1; } }
                    .cycle-price-summary { display: grid; gap: 4px; }
                    .cycle-price-summary > span { color: inherit; font-size: inherit; }
                    .cycle-price-summary > span > span { display: inline; }
                    .cycle-price-summary .price-label { display: inline; color: #726650; font-size: 12px; font-weight: 600; }
                    .badge { display: inline-flex; align-items: center; min-height: 28px; padding: 0 10px; border-radius: 999px; font-size: 13px; font-weight: 800; }
                    .badge-running { background: #d7f0e4; color: #0f5c3f; }
                    .badge-idle { background: #eee6d4; color: #6f5a30; }
                    .badge-error { background: #f7d6d1; color: #8d251b; }
                    .notice { display: none; margin: 12px 0; padding: 13px 14px; border-radius: 8px; font-weight: 800; border-left: 6px solid transparent; }
                    .notice-info { display: block; background: #e8eef7; color: #26405f; border-color: #5f7ea8; }
                    .notice-ok { display: block; background: #d7f0e4; color: #0f5c3f; border-color: #229366; }
                    .notice-error { display: block; background: #f7d6d1; color: #8d251b; border-color: #c0392b; }
                    .notice-warning { display: block; background: #fff2c7; color: #744c05; border-color: #c79216; }
                    .action-banner { display: none; margin: 12px 0; padding: 14px 16px; border-radius: 8px; border: 1px solid #c7bca8; font-weight: 900; }
                    .action-banner.active { display: block; }
                    .action-banner-info { background: #e8eef7; color: #26405f; border-color: #5f7ea8; }
                    .action-banner-ok { background: #d7f0e4; color: #0f5c3f; border-color: #229366; }
                    .action-banner-error { background: #f7d6d1; color: #8d251b; border-color: #c0392b; }
                    .nav { display: flex; gap: 8px; margin: 0 0 16px; }
                    .nav a { display: inline-flex; align-items: center; min-height: 34px; padding: 0 12px; border-radius: 8px; border: 1px solid #c7bca8; color: #4f422f; text-decoration: none; font-weight: 800; background: #fff; }
                    .nav a.active { background: #176b4d; color: #fff; border-color: #176b4d; }
                    label { display: grid; gap: 5px; font-size: 13px; color: #544; }
                    input, select { padding: 9px; border: 1px solid #c7bca8; border-radius: 8px; font-size: 14px; background: white; }
                    small { color: #8a7d69; font-size: 11px; }
                    button { padding: 10px 14px; border: 0; border-radius: 9px; margin-right: 8px; cursor: pointer; font-weight: 700; }
                    button:disabled { cursor: not-allowed; opacity: 0.55; }
                    .start { background: #176b4d; color: white; }
                    .stop { background: #9b6a17; color: white; }
                    .danger { background: #a33125; color: white; }
                    .compact { padding: 6px 10px; border-radius: 7px; margin-right: 0; font-size: 12px; }
                    .metric-button { border: 0; background: transparent; color: inherit; font: inherit; font-weight: 800; padding: 0; cursor: pointer; text-align: left; }
                    .metric-button:hover { text-decoration: underline; }
                    .pager { display: flex; align-items: center; gap: 8px; margin: 8px 0 10px; }
                    .pager span { color: #6f5a30; font-size: 12px; font-weight: 700; }
                    .table-wrap { width: 100%; overflow-x: auto; border: 1px solid #dfd3bd; border-radius: 8px; background: #fffaf0; }
                    table { width: 100%; min-width: max-content; border-collapse: collapse; font-size: 13px; }
                    th, td { border-bottom: 1px solid #dfd3bd; padding: 8px; text-align: left; white-space: nowrap; }
                    th { color: #5c4b34; background: #f3ead7; }
                    td.muted { color: #8a7d69; }
                    .order-orphan { background: #fff7df; }
                    .order-role { display: inline-flex; align-items: center; min-height: 24px; padding: 0 8px; border-radius: 999px; font-size: 12px; font-weight: 800; }
                    .order-role-pair { background: #d7f0e4; color: #0f5c3f; }
                    .order-role-orphan { background: #fff2c7; color: #744c05; }
                    .section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 22px; }
                    .toggle-row { display: flex; align-items: center; gap: 8px; min-height: 38px; }
                    .toggle-row input { width: 18px; height: 18px; }
                    .monitor-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin: 10px 0; }
                    .monitor-actions button { margin-right: 0; }
                    .monitor-rule input[type="number"] { width: 110px; }
                    .monitor-rule input[type="text"] { width: 140px; }
                    .monitor-rule select { min-width: 84px; }
                    .monitor-rule input[type="checkbox"] { width: 18px; height: 18px; }
                    .monitor-rule.current-cycle { background: #edf8f1; }
                    .monitor-current-badge { margin-left: 6px; color: #0f5c3f; font-weight: 800; white-space: nowrap; }
                    .pnl-pos { color: #0f6b45; font-weight: 700; }
                    .pnl-neg { color: #a33125; font-weight: 700; }
                    .pnl-flat { color: #6f5a30; }
                    .boost-fill-row { background: #edf8f1; }
                    .boost-fill-badge { display: inline-flex; align-items: center; min-height: 24px; padding: 0 8px; border-radius: 999px; background: #cfeeda; color: #0f5c3f; font-size: 12px; font-weight: 800; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>BTC Grid 控制台</h2>
                    <p class="nav">
                      <a href="__GRID_UI_URL__">手动网格</a>
                      <a href="__REBALANCE_UI_URL__">BTC 再平衡</a>
                    </p>
                    <div class="manual-section">
                    <div class="summary">
                      <div class="metric"><span>运行状态</span><strong><span id="runBadge" class="badge badge-idle">读取中</span></strong></div>
                      <div class="metric"><span>当前标的</span><strong id="activeSymbol">-</strong></div>
                      <div class="metric"><span>Cycle</span><strong id="cycleStatus">-</strong></div>
                      <div class="metric"><span>策略状态</span><strong id="strategyState">-</strong></div>
                      <div class="metric"><span>后台操作</span><strong id="operationStatus">-</strong></div>
                      <div class="metric"><span>最新价 / 标记价</span><strong id="lastPrice">-</strong></div>
                      <div class="metric"><span>挂单数量</span><strong id="orderCount">0</strong></div>
                      <div class="metric"><span>最近订单</span><strong id="lastOrderStatus">-</strong></div>
                      <div class="metric"><span id="solBollMonitorLabel">布林监控</span><strong id="solBollMonitorState">-</strong></div>
                      <div class="metric"><span>SOL实时价 / 下轨</span><strong id="solBollPrice">-</strong></div>
                      <div class="metric"><span>自动触发信号</span><strong id="solBollSignal">-</strong></div>
                      <div class="metric"><span>自动网格参数</span><strong id="solBollGridConfig">-</strong></div>
                    </div>
                    <div class="grid" id="form"></div>
                    <p>
                      <button id="startButton" class="start" onclick="start()">启动</button>
                      <button id="resumeButton" class="start" onclick="post('/api/resume')">继续运行</button>
                      <button id="stopButton" class="stop" onclick="post('/api/stop')">停止</button>
                      <button id="flattenButton" class="danger" onclick="post('/api/flatten-stop')">清仓并停止</button>
                      <button id="unlockFlattenButton" class="stop" onclick="unlockFlattenStop()">解除清仓停止锁</button>
                    </p>
                    <div id="actionNotice" class="notice"></div>
                    <p>本轮结束自动停止。</p>
                    </div>
                    <div class="rebalance-section">
                    <div class="section-title">
                      <h3>BTC 再平衡配置</h3>
                      <small id="rebalanceConfigPath">-</small>
                    </div>
                    <div class="grid" id="rebalanceForm"></div>
                    <div class="summary">
                      <div class="metric"><span>当前模式</span><strong id="rebalanceMode">-</strong></div>
                      <div class="metric"><span>再平衡状态</span><strong id="rebalanceStatus">-</strong></div>
                      <div class="metric"><span>Cycle 锁</span><strong id="rebalanceCycleLock">-</strong></div>
                      <div class="metric"><span>当前价格</span><strong id="rebalanceCurrentPrice">-</strong></div>
                      <div class="metric"><span>波段最低价—最高价（USDT）</span><strong id="rebalancePriceRange">-</strong></div>
                      <div class="metric"><span>当前理论 BTC 占比</span><strong id="rebalanceTheoryRatio">-</strong></div>
                      <div class="metric"><span>当前 / 执行目标 BTC 占比</span><strong id="rebalanceRatio">-</strong></div>
                      <div class="metric"><span>现货 BTC</span><strong id="rebalanceSpotQty">-</strong></div>
                      <div class="metric"><span>合约 BTC 仓位</span><strong id="rebalanceContractQty">-</strong></div>
                      <div class="metric"><span>净 BTC 敞口</span><strong id="rebalanceNetQty">-</strong></div>
                      <div class="metric"><span>总权益 USDT</span><strong id="rebalanceEquity">-</strong></div>
                      <div class="metric"><span>当前总资产（BTC）</span><strong id="rebalanceCycleTotalEquityBtc">-</strong></div>
                    </div>
                    <h3>波段高低价</h3>
                    <div class="grid" id="rebalancePriceRangeForm">
                      <label>波段最低价（USDT）<input id="rebalanceRangeLow" type="number" min="0.01" step="0.1" value="60000"><small>对应100% BTC</small></label>
                      <label>波段最高价（USDT）<input id="rebalanceRangeHigh" type="number" min="0.01" step="0.1" value="200000"><small>对应0% BTC</small></label>
                    </div>
                    <p><button id="saveRebalancePriceRangeButton" class="start" onclick="saveRebalancePriceRange()">保存波段高低价</button> <span id="rebalancePriceRangeNotice" class="muted">-</span></p>
                    <p class="muted">理论占比按当前价格在高低点之间线性计算。当前 Cycle 的执行目标单独显示。</p>
                    <p>
                      <button id="saveRebalanceButton" class="start" onclick="saveRebalanceConfig()">保存再平衡配置</button>
                      <button id="startRebalanceButton" class="start" onclick="postRebalance('/api/rebalance-start')">启动再平衡</button>
                      <button id="stopRebalanceButton" class="stop" onclick="postRebalance('/api/rebalance-stop')">停止再平衡</button>
                      <button id="closeRebalanceCycleButton" class="danger" onclick="closeRebalanceCycle()" disabled>平仓并停止当前 Cycle</button>
                      <button id="reduceRebalanceCycleButton" onclick="reduceRebalanceCycle()" disabled>手动减仓 10%</button>
                    </p>
                    <div id="rebalanceActionBanner" class="action-banner"></div>
                    <div id="rebalanceNotice" class="notice"></div>
                    <div class="section-title"><h3>价格监控配置</h3><small>按列表顺序匹配；一次只运行一个 Cycle</small></div>
                    <div class="monitor-actions">
                      <button class="start" onclick="addRebalanceMonitor()">添加监控</button>
                      <button class="start" onclick="saveRebalanceMonitors()">保存监控配置</button>
                      <span id="rebalanceMonitorStatus" class="muted">-</span>
                    </div>
                    <div class="table-wrap"><table>
                      <thead><tr><th>启用</th><th>名称</th><th>方向</th><th>价格条件</th><th>触发价 USDT</th><th>起始倍数</th><th>单格 BTC</th><th>结束 BTC 比例 %</th><th>操作</th></tr></thead>
                      <tbody id="rebalanceMonitorRules"><tr><td colspan="9" class="muted">暂无监控配置</td></tr></tbody>
                    </table></div>
                    <h3>再平衡挂单</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>状态</th><th>标的</th><th>方向</th><th>类型</th><th>数量</th><th>价格</th><th>较上笔</th><th>减仓</th><th>客户端订单ID</th><th>交易所订单ID</th><th>操作</th></tr></thead>
                        <tbody id="rebalanceOrders"><tr><td colspan="11" class="muted">暂无再平衡挂单</td></tr></tbody>
                      </table>
                    </div>
                    <h3>当前再平衡 Cycle</h3>
                    <div class="summary">
                      <div class="metric metric-combined"><span>本 Cycle 监控配置</span><strong id="rebalanceCycleMonitorRule">-</strong></div>
                      <div class="metric metric-combined"><span>Cycle 盈亏与仓位</span><div class="metric-lines">
                        <div class="metric-line"><span>市场价盈亏</span><strong id="rebalanceCyclePnl">-</strong></div>
                        <div class="metric-line"><span>开单方向</span><strong id="rebalanceCycleDirection">-</strong></div>
                        <div class="metric-line"><span>累计净数量</span><strong id="rebalanceCycleCumQty">-</strong></div>
                        <div class="metric-line"><span>持续时长</span><strong id="rebalanceCycleDuration">-</strong></div>
                      </div></div>
                      <div class="metric metric-combined"><span>BTC 占比</span><div class="metric-lines">
                        <div class="metric-line"><span>启动比例</span><strong id="rebalanceCycleStartRatio">-</strong></div>
                        <div class="metric-line"><span>当前比例</span><strong id="rebalanceCycleCurrentRatio">-</strong></div>
                      </div></div>
                      <div class="metric metric-combined"><span>动态水位止盈</span><div class="metric-lines">
                        <div class="metric-line"><span>水位</span><strong id="rebalanceCycleWaterPrice">-</strong></div>
                        <div class="metric-line"><span>当前价格</span><strong id="rebalanceCycleWaterCurrentPrice">-</strong></div>
                        <div class="metric-line"><span>1% 减半</span><strong id="rebalanceCycleHalfPrice">-</strong></div>
                        <div class="metric-line"><span>2% 清仓</span><strong id="rebalanceCycleExitPrice">-</strong></div>
                      </div><small class="muted">自动触发同时要求：做多现价高于本 Cycle 开单价，做空现价低于开单价；相等不触发。</small></div>
                      <div class="cycle-price-scenario-row">
                        <div class="metric"><span>启动 / 当前 / 成本价格</span><strong id="rebalanceCyclePriceSummary" class="cycle-price-summary">-</strong></div>
                        <div class="metric metric-scenario"><span id="rebalanceCycleScenarioLabel"></span><strong id="rebalanceCycleFullInventoryPrice">-</strong></div>
                      </div>
                      <div class="metric metric-combined"><span>买入 / 卖出</span><div class="metric-lines">
                        <div class="metric-line"><span>数量</span><strong id="rebalanceCycleQty">-</strong></div>
                        <div class="metric-line"><span>次数</span><strong id="rebalanceCycleCounts">-</strong></div>
                      </div></div>
                      <div class="metric metric-combined"><span>固定单格网格理论 / 实际净增</span><div class="metric-lines">
                        <div class="metric-line"><span>数量</span><strong id="rebalanceCyclePlannedQty">-</strong></div>
                        <div class="metric-line"><span>实际偏离理论</span><strong id="rebalanceCycleQtyDeviation">-</strong></div>
                      </div></div>
                      <div class="metric metric-combined"><span>累计费用</span><div class="metric-lines">
                        <div class="metric-line"><span>交易手续费</span><strong id="rebalanceCycleTradingFee">-</strong></div>
                        <div class="metric-line"><span>资金费</span><strong id="rebalanceCycleFundingFee">-</strong></div>
                        <div class="metric-line"><span>净费用</span><strong id="rebalanceCycleFee">-</strong></div>
                      </div></div>
                      <div class="metric metric-combined"><span>历史额外仓位（旧算法）</span><div class="metric-lines">
                        <div class="metric-line"><span>额外减仓</span><strong id="rebalanceCycleExtraReduce">-</strong></div>
                        <div class="metric-line"><span>历史未释放减仓</span><strong id="rebalanceCycleReleaseReserve">-</strong></div>
                        <div class="metric-line"><span>额外加仓</span><strong id="rebalanceCycleExtraAdd">-</strong></div>
                      </div></div>
                      <div class="metric metric-combined"><span>平均额外成交价</span><div class="metric-lines">
                        <div class="metric-line"><span>减仓</span><strong id="rebalanceCycleExtraReduceAvg">-</strong></div>
                        <div class="metric-line"><span>加仓</span><strong id="rebalanceCycleExtraAddAvg">-</strong></div>
                      </div></div>
                      <div class="metric"><span>其中永久减仓（不加回）</span><strong id="rebalanceCyclePermanentReduce">-</strong></div>
                      <div class="metric"><span>净额外减仓 / 50%上限</span><strong id="rebalanceCycleReduceCap">-</strong></div>
                      <div class="metric metric-combined"><span>待累计数量</span><div class="metric-lines">
                        <div class="metric-line"><span>减仓待累计</span><strong id="rebalanceCycleReduceCarry">-</strong></div>
                        <div class="metric-line"><span>加仓待累计</span><strong id="rebalanceCycleAddCarry">-</strong></div>
                      </div></div>
                    </div>
                    <details id="rebalanceFundingFeesDetails" ontoggle="toggleRebalanceFundingFees()">
                      <summary><strong>再平衡资金费记录</strong>（点击显示）</summary>
                      <div id="rebalanceFundingFeesPager" class="pager"></div>
                      <div class="table-wrap">
                        <table>
                          <thead><tr><th>时间</th><th>标的</th><th>资金费</th><th>币种</th><th>价格</th><th>累计资金费</th><th>累计净费用</th><th>市场价盈亏</th><th>执行ID</th></tr></thead>
                          <tbody id="rebalanceFundingFees"><tr><td colspan="9" class="muted">展开后读取资金费记录</td></tr></tbody>
                        </table>
                      </div>
                    </details>
                    <h3>再平衡成交记录</h3>
                    <small id="rebalancePnlFormula">盈亏 = 累计净数量 * 市场价 - 累计净价值 - 累计交易手续费 + 累计资金费。</small>
                    <div class="pager">
                      <label>成交价格（按十位） <input id="rebalanceFillPrice" style="width:140px" inputmode="decimal" placeholder="如 63943 查 63940-63950" onkeydown="if(event.key==='Enter')applyRebalanceFillPriceFilter()"></label>
                      <button class="compact" onclick="applyRebalanceFillPriceFilter()">查询</button>
                      <button class="compact" onclick="clearRebalanceFillPriceFilter()">清空</button>
                    </div>
                    <div id="rebalanceFillsPager" class="pager"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>加减仓标记</th><th>价格</th><th>较上笔</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>订单ID</th></tr></thead>
                        <tbody id="rebalanceFills"><tr><td colspan="15" class="muted">暂无再平衡成交</td></tr></tbody>
                      </table>
                    </div>
                    <h3>再平衡 Cycle 历史</h3>
                    <div id="rebalanceCyclesPager" class="pager"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>结束时间</th><th>标的</th><th>方向</th><th>启动价</th><th>结束价</th><th>启动比例</th><th>结束比例</th><th>目标比例</th><th>买入/卖出次数</th><th>耗时</th><th>结束方式</th><th>总盈亏</th><th>订单ID</th><th>执行ID</th><th>操作</th></tr></thead>
                        <tbody id="rebalanceCycles"><tr><td colspan="15" class="muted">暂无再平衡 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    <h3>再平衡 Cycle 明细</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>加减仓标记</th><th>价格</th><th>较上笔</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>订单ID</th></tr></thead>
                        <tbody id="rebalanceCycleDetail"><tr><td colspan="15" class="muted">请选择一个再平衡 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    </div>
                    <div class="manual-section">
                    <h3>当前挂单</h3>
                    <div id="orderNotice" class="notice"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>状态</th><th>关系</th><th>标的</th><th>方向</th><th>类型</th><th>数量</th><th>价格</th><th>减仓</th><th>客户端订单ID</th><th>交易所订单ID</th><th>操作</th></tr></thead>
                        <tbody id="orders"><tr><td colspan="11" class="muted">暂无挂单</td></tr></tbody>
                      </table>
                    </div>
                    <h3>当前 Cycle</h3>
                    <div class="summary">
                      <div class="metric"><span>市场价盈亏</span><strong id="cycleMarkPricePnl">-</strong></div>
                      <div class="metric"><span>开单方向</span><strong id="cycleEntryDirection">-</strong></div>
                      <div class="metric metric-price-summary"><span>启动 / 当前 / 成本价格</span><strong id="cyclePriceSummary" class="cycle-price-summary">-</strong></div>
                      <div class="metric"><span>买入 / 卖出次数</span><strong id="cycleCounts">-</strong></div>
                      <div class="metric"><span>买入 / 卖出数量</span><strong id="cycleQty">-</strong></div>
                      <div class="metric"><span>持续时长</span><strong id="cycleDuration">-</strong></div>
                      <div class="metric"><span>开始 / 最近成交</span><strong id="cycleTimes">-</strong></div>
                      <div class="metric"><span>累计净数量</span><strong id="cycleCumQty">-</strong></div>
                      <div class="metric"><span>累计交易手续费</span><strong id="cycleTradingFee">-</strong></div>
                      <div class="metric"><span>累计资金费</span><strong><button id="cycleFundingFee" class="metric-button" data-action="funding-detail">-</button></strong></div>
                      <div class="metric"><span>累计净费用</span><strong id="cycleFee">-</strong></div>
                      <div class="metric"><span>盈利减仓</span><strong id="cPRQ">-</strong></div>
                      <div class="metric"><span>额外减仓</span><strong id="cPBE">-</strong></div>
                      <div class="metric"><span>平均额外减仓价格</span><strong id="cPBEAvg">-</strong></div>
                      <div class="metric"><span>额外加仓</span><strong id="cLABE">-</strong></div>
                      <div class="metric"><span>平均额外加仓价格</span><strong id="cLABEAvg">-</strong></div>
                      <div class="metric"><span>待释放额外减仓</span><strong id="cPRR">-</strong></div>
                      <div class="metric"><span>净额外减仓 / 50%上限</span><strong id="cPRCap">-</strong></div>
                      <div class="metric"><span>减仓待累计</span><strong id="cPRCarry">-</strong></div>
                      <div class="metric"><span>加仓待累计</span><strong id="cLACarry">-</strong></div>
                    </div>
                    <h3>成交记录</h3>
                    <small id="pnlFormula">盈亏 = cumQty * 市场价 - cumValue - 净费用。</small>
                    <div class="pager">
                      <label>成交价格（忽略小数点后第二位） <input id="fillPrice" style="width:160px" inputmode="decimal" placeholder="如 69.87 查 69.8-69.9" onkeydown="if(event.key==='Enter')applyFillPriceFilter()"></label>
                      <button class="compact" onclick="applyFillPriceFilter()">查询</button>
                      <button class="compact" onclick="clearFillPriceFilter()">清空</button>
                    </div>
                    <div class="pager">
                      <button id="fillsPrev" class="compact" onclick="previousFillsPage()">上一页</button>
                      <span id="fillsPager">-</span>
                      <button id="fillsNext" class="compact" onclick="nextFillsPage()">下一页</button>
                    </div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>加减仓标记</th><th>价格</th><th>较上笔</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>仓位均价</th><th>订单ID</th></tr></thead>
                        <tbody id="fills"><tr><td colspan="16">暂无成交</td></tr></tbody>
                      </table>
                    </div>
                    <h3>Cycle 历史</h3>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>结束时间</th><th>标的</th><th>最后成交价</th><th>总盈亏</th><th>订单ID</th><th>执行ID</th><th>操作</th></tr></thead>
                        <tbody id="cycles"><tr><td colspan="7">暂无 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    <h3>Cycle 成交明细</h3>
                    <div id="cycleDetailNotice" class="notice"></div>
                    <div id="cycleDetailPager" class="pager"></div>
                    <div class="table-wrap">
                      <table>
                        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>数量</th><th>加减仓标记</th><th>价格</th><th>成交额</th><th>手续费/资金费</th><th>市场价盈亏</th><th>累计净数量</th><th>累计成交额</th><th>累计净费用</th><th>成本价</th><th>仓位均价</th><th>订单ID</th></tr></thead>
                        <tbody id="cycleDetail"><tr><td colspan="15">请选择一个历史 Cycle</td></tr></tbody>
                      </table>
                    </div>
                    </div>
                  </div>
                """.concat("""
                  <script>
                    const pageMode = location.pathname === '/rebalance' ? 'rebalance' : 'grid';
                    function applyPageMode() {
                      document.title = pageMode === 'rebalance' ? 'BTC 再平衡控制台' : '手动网格控制台';
                      document.querySelectorAll('.manual-section').forEach(el => el.style.display = pageMode === 'grid' ? '' : 'none');
                      document.querySelectorAll('.rebalance-section').forEach(el => el.style.display = pageMode === 'rebalance' ? '' : 'none');
                      document.querySelectorAll('.nav a').forEach(a => {
                        const active = (pageMode === 'grid' && a.getAttribute('href') === '/grid') || (pageMode === 'rebalance' && a.getAttribute('href') === '/rebalance');
                        a.className = active ? 'active' : '';
                      });
                    }
                    const fields = {
                      category:'linear', symbol:'BTCUSDT', entryDirection:'long', baseGridSpacing:'0.0016', gridRequoteShrinkFactor:'0.85', baseGridQty:'0.005',
                      maxInitialSteps:'5', maxOrderQty:'0.20', maxInventoryQty:'0.15', maxDirectionalInventoryQty:'0.15',
                      emaLength4h:'20', bollLength4h:'20', bollStdDevMultiplier:'2.0',
                      pairOrderTimeoutMinutes:'15', triggerCooldownMinutes:'5'
                    };
                    const labels = {
                      category:'交易类型',
                      symbol:'交易标的',
                      entryDirection:'开单方向',
                      baseGridSpacing:'基础网格间距',
                      gridRequoteShrinkFactor:'重挂间距收缩系数',
                      baseGridQty:'基础下单数量',
                      maxInitialSteps:'开单步数',
                      maxOrderQty:'单笔最大数量',
                      maxInventoryQty:'总持仓上限',
                      maxDirectionalInventoryQty:'单方向持仓上限',
                      emaLength4h:'4小时 EMA 周期',
                      bollLength4h:'4小时布林周期',
                      bollStdDevMultiplier:'布林标准差倍数',
                      pairOrderTimeoutMinutes:'挂单超时分钟',
                      triggerCooldownMinutes:'开单冷却分钟'
                    };
                    const directionOptions = {
                      both:'多空',
                      long:'做多',
                      short:'做空',
                      spot_long_only:'现货只做多'
                    };
                    const categoryOptions = {
                      linear:'U本位合约',
                      spot:'现货'
                    };
                    const rebalanceFields = {
                      enabled:false, mode:'price_target', autoMinTotalBtc:'6', autoBaseGridQty:'0.003', autoInitialSteps:'5',
                      category:'linear', symbol:'BTCUSDT', accountType:'UNIFIED', baseCoin:'BTC', quoteCoin:'USDT',
                      priceTargets:'0:0.80,60000:0.75,65000:0.70,100000:0.50', ratioThreshold:'0.02',
                      baseGridSpacing:'0.0016', gridRequoteShrinkFactor:'0.85', baseGridQty:'0.001', maxInitialSteps:'5',
                      maxOrderQty:'0.001', maxInventoryQty:'0.15', priceOffsetRatio:'0.001', bandHysteresisRatio:'0.001',
                      orderTtlMinutes:'15', confirmationSamples:'3'
                    };
                    const rebalanceLabels = {
                      enabled:'启用再平衡',
                      mode:'运行模式',
                      autoMinTotalBtc:'自动启动最低总资产（BTC）',
                      autoBaseGridQty:'自动模式单格数量（BTC）',
                      autoInitialSteps:'自动模式初始格数',
                      category:'交易类型',
                      symbol:'交易标的',
                      accountType:'账户类型',
                      baseCoin:'基础币',
                      quoteCoin:'计价币',
                      priceTargets:'价格档位目标',
                      ratioThreshold:'触发偏差',
                      baseGridSpacing:'基础网格间距',
                      gridRequoteShrinkFactor:'重挂间距收缩系数',
                      baseGridQty:'基础下单数量',
                      maxInitialSteps:'最大初始步数',
                      maxOrderQty:'单次最大数量',
                      maxInventoryQty:'最大持仓数量',
                      priceOffsetRatio:'挂单价格偏移',
                      bandHysteresisRatio:'价格档位滞回',
                      orderTtlMinutes:'挂单超时分钟',
                      confirmationSamples:'资产确认次数'
                    };
                    const fillsPageSize = 50;
                    let fillsOffset = 0;
                    let fillsPrice = '';
                    let fillsRequestId = 0;
                    let latestFillsPage = null;
                    const gridFillsPageCache = new Map();
                    const gridFillsCacheLimit = 10;
                    const rebalancePageSize = 50;
                    let rebalanceFillsOffset = 0;
                    let rebalanceFillPrice = '';
                    let rebalanceFillsRequestId = 0;
                    const rebalanceFillsPageCache = new Map();
                    const rebalanceFillsCacheLimit = 10;
                    let rebalanceFundingFeesOffset = 0;
                    let rebalanceFundingFeesRequestId = 0;
                    let rebalanceCyclesOffset = 0;
                    let rebalanceCyclesRequestId = 0;
                    const cycleDetailPageSize = 50;
                    let cycleDetailOffset = 0;
                    let cycleDetailExecutionId = '';
                    let cycleDetailLabel = '';
                    let cycleDetailRequestId = 0;
                    let priceRangeConfig = null;
                    let priceRangeState = null;
                    let priceRangeDirty = false;
                    let priceRangeRequest = 0;
                    document.getElementById('rebalancePriceRangeForm').addEventListener('input', () => {
                      priceRangeDirty = true; renderPriceRange();
                    });
                    function theoreticalRatio(price, low, high) {
                      if (![price, low, high].every(Number.isFinite) || price <= 0 || low <= 0 || high <= low) return null;
                      return Math.max(0, Math.min(1, (high - price) / (high - low)));
                    }
                    function renderPriceRange() {
                      const price = Number(priceRangeState && priceRangeState.lastPrice);
                      const config = priceRangeConfig;
                      setText('rebalancePriceRange', config ? `${formatNumber(config.lowPrice)} — ${formatNumber(config.highPrice)}` : '-');
                      const ratio = config ? theoreticalRatio(price, Number(config.lowPrice), Number(config.highPrice)) : null;
                      setText('rebalanceTheoryRatio', ratio == null ? '-' : formatPercent(ratio));
                      if (priceRangeDirty) {
                        const low = Number(document.getElementById('rebalanceRangeLow').value);
                        const high = Number(document.getElementById('rebalanceRangeHigh').value);
                        const preview = theoreticalRatio(price, low, high);
                        setText('rebalancePriceRangeNotice', low > 0 && high > low
                          ? `未保存${preview == null ? '' : '；修改后理论占比 ' + formatPercent(preview)}` : '最高价必须大于最低价，且最低价大于0');
                      }
                    }
                    function applyPriceRange(config) {
                      priceRangeConfig = config;
                      if (!priceRangeDirty && !document.getElementById('rebalancePriceRangeForm').contains(document.activeElement)) {
                        document.getElementById('rebalanceRangeLow').value = config.lowPrice;
                        document.getElementById('rebalanceRangeHigh').value = config.highPrice;
                      }
                      renderPriceRange();
                    }
                    async function refreshRebalancePriceRange() {
                      const request = ++priceRangeRequest;
                      try {
                        const response = await fetch('/api/rebalance-price-range');
                        const result = await response.json();
                        if (request !== priceRangeRequest) return;
                        if (!response.ok || !result.ok) throw new Error(result.message || '价格配置读取失败');
                        applyPriceRange(result.config);
                      } catch (error) {
                        if (request !== priceRangeRequest) return;
                        priceRangeConfig = null; renderPriceRange();
                        setText('rebalancePriceRangeNotice', error.message || '价格配置读取失败');
                      }
                    }
                    async function saveRebalancePriceRange() {
                      const low = document.getElementById('rebalanceRangeLow').value.trim();
                      const high = document.getElementById('rebalanceRangeHigh').value.trim();
                      if (!Number.isFinite(Number(low)) || !Number.isFinite(Number(high)) || Number(low) <= 0 || Number(high) <= Number(low)) {
                        setText('rebalancePriceRangeNotice', '最高价必须大于最低价，且最低价大于0'); return;
                      }
                      const request = ++priceRangeRequest;
                      const controls = ['rebalanceRangeLow', 'rebalanceRangeHigh', 'saveRebalancePriceRangeButton'].map(id => document.getElementById(id));
                      controls.forEach(input => input.disabled = true);
                      try {
                        const response = await fetch('/api/rebalance-price-range', {method:'POST', headers:{'content-type':'application/json'}, body:JSON.stringify({lowPrice:low, highPrice:high})});
                        const result = await response.json();
                        if (!response.ok || !result.ok) throw new Error(result.message || '价格配置保存失败');
                        priceRangeRequest++; priceRangeDirty = false; applyPriceRange(result.config);
                        document.getElementById('rebalanceRangeLow').value = result.config.lowPrice;
                        document.getElementById('rebalanceRangeHigh').value = result.config.highPrice;
                        setText('rebalancePriceRangeNotice', '已保存');
                      } catch (error) { setText('rebalancePriceRangeNotice', error.message || '价格配置保存失败'); }
                      finally { controls.forEach(input => input.disabled = false); }
                    }
                    let rebalanceDirty = false;
                    let savedRebalanceMode = 'price_target';
                    let latestRebalanceConfig = null;
                    let rebalanceMonitorsDirty = false;
                    const form = document.getElementById('form');
                    Object.entries(fields).forEach(([k,v]) => {
                      const control = k === 'entryDirection'
                        ? `<select id="${k}">${Object.entries(directionOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                        : k === 'category'
                          ? `<select id="${k}">${Object.entries(categoryOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                          : `<input id="${k}" value="${v}">`;
                      form.insertAdjacentHTML('beforeend', `<label>${labels[k] || k}${control}<small>${k}</small></label>`);
                    });
                    const rebalanceForm = document.getElementById('rebalanceForm');
                    Object.entries(rebalanceFields).forEach(([k,v]) => {
                      const control = k === 'enabled'
                        ? `<div class="toggle-row"><input id="rebalance_${k}" type="checkbox"><span>开启</span></div>`
                        : k === 'mode'
                          ? `<select id="rebalance_${k}"><option value="price_target">价格目标再平衡</option><option value="auto_cycle">自动做多 Cycle</option><option value="auto_short_cycle">高位做空 / 低位做多 Cycle</option><option value="auto_long_below">低于阈值做多 Cycle</option></select>`
                        : k === 'category'
                          ? `<select id="rebalance_${k}">${Object.entries(categoryOptions).map(([value,text]) => `<option value="${value}" ${value === v ? 'selected' : ''}>${text}</option>`).join('')}</select>`
                          : `<input id="rebalance_${k}" value="${v}">`;
                      const help = k === 'priceTargets'
                        ? '价格目标模式填价格下限:目标占比；低于阈值做多模式填触发价:1，例如 100000:1（严格低于触发）'
                        : k === 'autoMinTotalBtc'
                          ? '仅空闲且总资产折合 BTC 严格大于此值时启动'
                        : k === 'autoBaseGridQty'
                          ? '自动做多及低于阈值做多模式的单格数量；首笔数量还会乘以初始格数'
                        : k === 'autoInitialSteps'
                          ? '首笔买入量 = 单格数量 × 初始格数'
                        : `rebalance.${k}`;
                      rebalanceForm.insertAdjacentHTML('beforeend', `<label>${rebalanceLabels[k] || k}${control}<small>${help}</small></label>`);
                    });
                    rebalanceForm.addEventListener('input', () => { rebalanceDirty = true; });
                    rebalanceForm.addEventListener('change', () => { rebalanceDirty = true; });
                    document.getElementById('rebalance_mode').addEventListener('change', updateRebalanceModeUi);
                    function updateRebalanceModeUi() {
                      const mode = document.getElementById('rebalance_mode').value;
                      const autoLong = mode === 'auto_cycle';
                      const autoShort = mode === 'auto_short_cycle';
                      const longBelow = mode === 'auto_long_below';
                      document.getElementById('rebalance_autoMinTotalBtc').disabled = !autoLong;
                      document.getElementById('rebalance_autoBaseGridQty').disabled = !(autoLong || autoShort || longBelow);
                      document.getElementById('rebalance_autoInitialSteps').disabled = !(autoLong || autoShort || longBelow);
                      ['ratioThreshold', 'bandHysteresisRatio'].forEach(k => {
                        document.getElementById(`rebalance_${k}`).disabled = autoLong || autoShort || longBelow;
                      });
                      document.getElementById('rebalance_priceTargets').disabled = autoLong;
                      setText('rebalanceMode', autoLong ? '自动做多 Cycle' : (autoShort ? '高位做空 / 低位做多 Cycle' : (longBelow ? '低于阈值做多 Cycle' : '价格目标再平衡')));
                    }
                    function payload() {
                      const out = {};
                      Object.keys(fields).forEach(k => out[k] = document.getElementById(k).value);
                      return out;
                    }
                    function applyConfig(config) {
                      Object.keys(fields).forEach(k => {
                        const input = document.getElementById(k);
                        if (input && config && config[k] !== undefined && config[k] !== null) input.value = config[k];
                      });
                    }
                    function rebalancePayload() {
                      const out = {};
                      Object.keys(rebalanceFields).forEach(k => {
                        const input = document.getElementById(`rebalance_${k}`);
                        out[k] = input.type === 'checkbox' ? input.checked : input.value;
                      });
                      return out;
                    }
                    function applyRebalanceConfig(config) {
                      if (rebalanceDirty || rebalanceForm.contains(document.activeElement)) return;
                      latestRebalanceConfig = config || latestRebalanceConfig;
                      Object.keys(rebalanceFields).forEach(k => {
                        const input = document.getElementById(`rebalance_${k}`);
                        if (!input || !config || config[k] === undefined || config[k] === null) return;
                        if (input.type === 'checkbox') input.checked = Boolean(config[k]);
                        else input.value = config[k];
                      });
                      savedRebalanceMode = config && config.mode ? config.mode : 'price_target';
                      updateRebalanceModeUi();
                      const path = document.getElementById('rebalanceConfigPath');
                      if (path) path.textContent = config && config.configFilePath ? `保存到 ${config.configFilePath}` : '-';
                    }
                    function monitorRow(rule) {
                      const row = document.createElement('tr');
                      row.className = 'monitor-rule';
                      row.dataset.id = rule.id || '';
                      row.innerHTML = `<td><input data-field="enabled" type="checkbox"></td>
                        <td><input data-field="name" type="text" placeholder="监控名称"></td>
                        <td><select data-field="side"><option value="BUY">做多</option><option value="SELL">做空</option></select></td>
                        <td><select data-field="trigger"><option value="BELOW">小于</option><option value="ABOVE">大于</option></select></td>
                        <td><input data-field="triggerPrice" type="number" min="0.01" step="any"></td>
                        <td><input data-field="initialSteps" type="number" min="0.001" step="any"></td>
                        <td><input data-field="gridQty" type="number" min="0.000001" step="any"></td>
                        <td><input data-field="targetPercent" type="number" min="0" max="100" step="any"></td>
                        <td><button type="button" data-action="up" title="上移">↑</button><button type="button" data-action="down" title="下移">↓</button><button class="danger" type="button" data-action="delete">删除</button></td>`;
                      for (const field of ['name', 'side', 'trigger', 'triggerPrice', 'initialSteps', 'gridQty']) {
                        row.querySelector(`[data-field="${field}"]`).value = rule[field] ?? '';
                      }
                      row.querySelector('[data-field="enabled"]').checked = Boolean(rule.enabled);
                      row.querySelector('[data-field="targetPercent"]').value = Number(rule.targetRatio ?? 1) * 100;
                      row.querySelector('[data-action="up"]').onclick = () => {
                        if (row.previousElementSibling && row.previousElementSibling.classList.contains('monitor-rule')) {
                          row.parentNode.insertBefore(row, row.previousElementSibling);
                          rebalanceMonitorsDirty = true;
                        }
                      };
                      row.querySelector('[data-action="down"]').onclick = () => {
                        if (row.nextElementSibling && row.nextElementSibling.classList.contains('monitor-rule')) {
                          row.parentNode.insertBefore(row.nextElementSibling, row);
                          rebalanceMonitorsDirty = true;
                        }
                      };
                      row.querySelector('[data-action="delete"]').onclick = () => { row.remove(); rebalanceMonitorsDirty = true; };
                      return row;
                    }
                    function renderRebalanceMonitors(data, state) {
                      const cycleRunning = state && (state.status === 'GRID_ACTIVE' || state.status === 'GRID_OPENING');
                      const active = cycleRunning && data && data.active && data.active.id ? data.active : null;
                      const startRule = active && data.activeSnapshot && data.activeSnapshot.id === active.id
                        ? data.activeSnapshot : active;
                      const activeText = active
                        ? `${active.name || active.id}｜${startRule.side === 'BUY' ? '做多' : '做空'}｜启动条件：价格${startRule.trigger === 'BELOW' ? '小于' : '大于'}${formatNumber(startRule.triggerPrice)}｜起始 ${formatNumber(startRule.initialSteps)} × ${formatNumber(startRule.gridQty)} BTC｜当前结束比例 ${formatPercent(active.targetRatio)}`
                        : cycleRunning ? '原配置启动（无关联监控规则）' : '-';
                      setText('rebalanceCycleMonitorRule', activeText);
                      setText('rebalanceMonitorStatus', active
                        ? `当前 Cycle：${active.name || active.id}（${active.side === 'BUY' ? '做多' : '做空'}）`
                        : cycleRunning ? '当前 Cycle 由原配置启动' : data && data.configured ? '监控待命' : '未配置');
                      if (rebalanceMonitorsDirty) return;
                      const body = document.getElementById('rebalanceMonitorRules');
                      body.replaceChildren();
                      for (const rule of (data && data.rules) || []) {
                        const row = monitorRow(rule);
                        if (active && active.id === rule.id) {
                          row.classList.add('current-cycle');
                          row.querySelector('[data-action="delete"]').disabled = true;
                          const badge = document.createElement('span');
                          badge.className = 'monitor-current-badge';
                          badge.textContent = '当前 Cycle（结束比例修改后生效）';
                          row.children[1].appendChild(badge);
                        }
                        body.appendChild(row);
                      }
                      if (!body.children.length) body.innerHTML = '<tr><td colspan="9" class="muted">暂无监控配置</td></tr>';
                    }
                    function addRebalanceMonitor() {
                      const body = document.getElementById('rebalanceMonitorRules');
                      if (body.querySelector('.muted')) body.replaceChildren();
                      body.appendChild(monitorRow({enabled:false, name:'', side:'BUY', trigger:'BELOW',
                        triggerPrice:'10000', initialSteps:'5', gridQty:'0.006', targetRatio:1}));
                      rebalanceMonitorsDirty = true;
                    }
                    document.getElementById('rebalanceMonitorRules').addEventListener('input', () => { rebalanceMonitorsDirty = true; });
                    document.getElementById('rebalanceMonitorRules').addEventListener('change', () => { rebalanceMonitorsDirty = true; });
                    function rebalanceMonitorPayload() {
                      return [...document.querySelectorAll('#rebalanceMonitorRules .monitor-rule')].map(row => {
                        const field = key => row.querySelector(`[data-field="${key}"]`);
                        return {id:row.dataset.id || null, name:field('name').value,
                          enabled:field('enabled').checked, side:field('side').value,
                          trigger:field('trigger').value, triggerPrice:field('triggerPrice').value,
                          initialSteps:field('initialSteps').value, gridQty:field('gridQty').value,
                          targetRatio:String(Number(field('targetPercent').value) / 100)};
                      });
                    }
                    async function saveRebalanceMonitors() {
                      try {
                        const response = await fetch('/api/rebalance-monitors', {method:'POST',
                          headers:{'content-type':'application/json'}, body:JSON.stringify(rebalanceMonitorPayload())});
                        const result = await response.json();
                        if (!result.ok) throw new Error(result.message || '保存失败');
                        rebalanceMonitorsDirty = false;
                        renderRebalanceMonitors({configured:true, rules:result.rules});
                        showRebalanceResult('ok', result.message || '监控配置已保存');
                        await refresh();
                      } catch (error) { showRebalanceResult('error', error.message || '保存失败'); }
                    }
                    async function saveRebalanceConfig() {
                      const nextMode = document.getElementById('rebalance_mode').value;
                      if (nextMode !== savedRebalanceMode) {
                        const label = nextMode === 'auto_cycle' ? '自动循环网格' : '价格目标再平衡';
                        if (!window.confirm(`确认切换为“${label}”？运行中的 Cycle 不允许切换，原模式配置会保留。`)) return;
                      }
                      setBusy(true, 'save');
                      showRebalanceResult('info', '保存中...');
                      showRebalanceBanner('info', '保存中...');
                      try {
                        const r = await fetch('/api/rebalance-config', {method:'POST', body: JSON.stringify(rebalancePayload()), headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        if (result.ok) rebalanceDirty = false;
                        if (result.config) {
                          const active = document.activeElement;
                          if (active && rebalanceForm.contains(active)) active.blur();
                          applyRebalanceConfig(result.config);
                        }
                        showRebalanceResult(result.ok ? 'ok' : 'error', result.message || (result.ok ? '保存成功' : '保存失败'));
                        showRebalanceBanner(result.ok ? 'ok' : 'error', result.ok ? '保存完成' : '保存失败');
                      } catch (err) {
                        const message = err && err.message ? err.message : '保存失败';
                        showRebalanceResult('error', message);
                        showRebalanceBanner('error', '保存失败');
                      } finally {
                        setBusy(false);
                        setTimeout(refresh, 500);
                      }
                    }
                    async function postRebalance(url) {
                      const action = url.includes('reduce-cycle') ? 'reduce' : url.includes('close-cycle') ? 'close' : (url.includes('stop') ? 'stop' : 'start');
                      const actionText = action === 'reduce' ? '手动减仓 10%' : action === 'close' ? '平仓并停止当前 Cycle' : (action === 'stop' ? '停止再平衡' : '启动再平衡');
                      setBusy(true, action);
                      showRebalanceResult('info', `${actionText}请求处理中...`);
                      showRebalanceBanner('info', `正在${actionText}...`);
                      try {
                        const r = await fetch(url, {method:'POST', body: '{}', headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        const message = result.message || (result.ok ? '操作成功' : '操作失败');
                        showRebalanceResult(result.ok ? 'ok' : 'error', message);
                        showRebalanceBanner(result.ok ? 'ok' : 'error', result.ok ? `${actionText}完成` : `${actionText}失败`);
                      } catch (err) {
                        const message = err && err.message ? err.message : '请求失败';
                        showRebalanceResult('error', message);
                        showRebalanceBanner('error', `${actionText}失败`);
                      } finally {
                        setBusy(false);
                        setTimeout(refresh, 500);
                      }
                    }
                    function closeRebalanceCycle() {
                      if (!confirm('确认撤销当前 Cycle 挂单，并按盘口最近价限价平掉累计净数量？成交后 Cycle 结束。')) return;
                      postRebalance('/api/rebalance-close-cycle');
                    }
                    function reduceRebalanceCycle() {
                      if (!confirm('确认按当前 Cycle 累计净数量减仓 10%（按数量步长向下取整）？将先撤单，再按盘口限价减仓；成交后继续当前 Cycle。')) return;
                      postRebalance('/api/rebalance-reduce-cycle');
                    }
                    async function post(url, body) {
                      setBusy(true);
                      showActionResult('info', '请求处理中...');
                      try {
                        const r = await fetch(url, {method:'POST', body: JSON.stringify(body || {}), headers:{'content-type':'application/json'}});
                        const result = await r.json();
                        showActionResult(result.ok ? 'ok' : 'error', result.message || (result.ok ? '操作成功' : '操作失败'));
                      } catch (err) {
                        showActionResult('error', err && err.message ? err.message : '请求失败');
                      } finally {
                        setBusy(false);
                        setTimeout(refresh, 500);
                      }
                    }
                    function start() { post('/api/start', payload()); }
                    function unlockFlattenStop() {
                      if (!confirm('确认解除清仓停止锁？解除后策略才允许再次启动。')) return;
                      post('/api/unlock-flatten-stop');
                    }
                    function cancelOrder(clientOrderId, symbol) {
                      post('/api/cancel-order', {clientOrderId, symbol});
                    }
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cancel-order"]');
                      if (!button) return;
                      cancelOrder(button.dataset.clientOrderId, button.dataset.symbol);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cycle-detail"]');
                      if (!button) return;
                      cycleDetailOffset = 0;
                      cycleDetailExecutionId = button.dataset.executionId || '';
                      cycleDetailLabel = button.dataset.label || '';
                      loadCycleDetail();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cycle-detail-page"]');
                      if (!button || !cycleDetailExecutionId) return;
                      cycleDetailOffset = Math.max(0, cycleDetailOffset + Number(button.dataset.delta || 0) * cycleDetailPageSize);
                      loadCycleDetail();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="cycle-load-config"]');
                      if (!button) return;
                      loadCycleConfig(button.dataset.executionId, button.dataset.label);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-cycle-detail"]');
                      if (!button) return;
                      loadRebalanceCycleDetail(button.dataset.executionId);
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-fills-page"]');
                      if (!button) return;
                      rebalanceFillsOffset = Math.max(0, rebalanceFillsOffset + Number(button.dataset.delta || 0) * rebalancePageSize);
                      loadRebalanceFillsPage();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-cycles-page"]');
                      if (!button) return;
                      rebalanceCyclesOffset = Math.max(0, rebalanceCyclesOffset + Number(button.dataset.delta || 0) * rebalancePageSize);
                      loadRebalanceCyclesPage();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="rebalance-funding-fees-page"]');
                      if (!button) return;
                      rebalanceFundingFeesOffset = Math.max(
                        0,
                        rebalanceFundingFeesOffset + Number(button.dataset.delta || 0) * rebalancePageSize
                      );
                      loadRebalanceFundingFeesPage();
                    });
                    document.addEventListener('click', event => {
                      const button = event.target.closest('[data-action="funding-detail"]');
                      if (!button) return;
                      renderFundingDetail();
                    });
                    function setBusy(busy, action) {
                      ['startButton', 'resumeButton', 'stopButton', 'flattenButton', 'saveRebalanceButton', 'startRebalanceButton', 'stopRebalanceButton', 'closeRebalanceCycleButton', 'reduceRebalanceCycleButton'].forEach(id => {
                        const button = document.getElementById(id);
                        if (button) button.dataset.busy = busy ? 'true' : 'false';
                        if (button && (id === 'saveRebalanceButton' || id === 'startRebalanceButton' || id === 'stopRebalanceButton' || id === 'closeRebalanceCycleButton' || id === 'reduceRebalanceCycleButton')) button.disabled = busy;
                      });
                      setRebalanceButtonText(busy ? action : null);
                      document.querySelectorAll('[data-action="cancel-order"]').forEach(button => button.disabled = busy);
                      document.body.dataset.busy = busy ? 'true' : 'false';
                    }
                    function setRebalanceButtonText(action) {
                      const labels = {
                        saveRebalanceButton: action === 'save' ? '保存中...' : '保存再平衡配置',
                        startRebalanceButton: action === 'start' ? '启动中...' : '启动再平衡',
                        stopRebalanceButton: action === 'stop' ? '停止中...' : '停止再平衡',
                        closeRebalanceCycleButton: action === 'close' ? '平仓请求中...' : '平仓并停止当前 Cycle',
                        reduceRebalanceCycleButton: action === 'reduce' ? '减仓请求中...' : '手动减仓 10%'
                      };
                      Object.entries(labels).forEach(([id, text]) => {
                        const button = document.getElementById(id);
                        if (button) button.textContent = text;
                      });
                    }
                    function showActionResult(type, message) {
                      const notice = document.getElementById('actionNotice');
                      notice.className = `notice notice-${type}`;
                      notice.textContent = message;
                    }
                    function showRebalanceResult(type, message) {
                      const notice = document.getElementById('rebalanceNotice');
                      notice.className = `notice notice-${type}`;
                      notice.textContent = message;
                    }
                    function showRebalanceBanner(type, title) {
                      const banner = document.getElementById('rebalanceActionBanner');
                      if (!banner) return;
                      banner.className = `action-banner active action-banner-${type}`;
                      banner.textContent = title || '';
                    }
                    let refreshInFlight = false;
                    async function refresh() {
                      if (refreshInFlight) return;
                      refreshInFlight = true;
                      try {
                        return await (pageMode === 'rebalance' ? refreshRebalance() : refreshGrid());
                      } catch (error) {
                        const message = `刷新失败：${error && error.message ? error.message : error}`;
                        if (pageMode === 'rebalance') showRebalanceResult('error', message);
                        else showActionResult('error', message);
                      } finally {
                        refreshInFlight = false;
                      }
                    }
                    async function refreshRebalance() {
                      const priceRangePromise = refreshRebalancePriceRange();
                      const rebalanceFillsPromise = loadRebalanceFillsPage(true);
                      const [control, rebalanceConfig, rebalanceState, rebalanceOrders, rebalanceCycles, rebalanceCurrentCycle, rebalanceRecentFills, rebalanceMonitors] = await Promise.all([
                        fetch('/api/control').then(r=>r.json()),
                        fetch('/api/rebalance-config').then(r=>r.json()),
                        fetch('/api/rebalance-state').then(r=>r.json()),
                        fetch('/api/rebalance-orders').then(r=>r.json()),
                        fetch(`/api/rebalance-cycles?limit=${rebalancePageSize}&offset=${rebalanceCyclesOffset}`).then(r=>r.json()),
                        fetch('/api/rebalance-current-cycle').then(r=>r.json()),
                        fetch('/api/rebalance-fills?limit=20&offset=0').then(r=>r.json()),
                        fetch('/api/rebalance-monitors').then(r=>r.json())
                      ]);
                      const rebalanceCycleItems = rebalanceCycles && rebalanceCycles.items ? rebalanceCycles.items : [];
                      if (rebalanceConfig && rebalanceConfig.config) applyRebalanceConfig(rebalanceConfig.config);
                      renderRebalanceMonitors(rebalanceMonitors,
                        rebalanceState && rebalanceState.state ? rebalanceState.state : null);
                      renderRebalanceState(rebalanceState && rebalanceState.state ? rebalanceState.state : null, control);
                      const activeRebalanceOrders = (rebalanceOrders || []).filter(isActiveOrder);
                      renderRebalanceOrders(activeRebalanceOrders, rebalanceCurrentCycle);
                      renderRebalanceCurrentCycle(
                        rebalanceCurrentCycle,
                        rebalanceState && rebalanceState.state ? rebalanceState.state : null,
                        activeRebalanceOrders,
                        rebalanceRecentFills && rebalanceRecentFills.items ? rebalanceRecentFills.items : []
                      );
                      renderRebalanceCycles(rebalanceCycleItems, rebalanceCycles && rebalanceCycles.summary);
                      renderRebalancePager('rebalanceCyclesPager', rebalanceCycles, rebalanceCyclesOffset, 'rebalance-cycles-page');
                      const fundingDetails = document.getElementById('rebalanceFundingFeesDetails');
                      if (fundingDetails && fundingDetails.open) loadRebalanceFundingFeesPage();
                      await rebalanceFillsPromise;
                      await priceRangePromise;
                    }
                    function rebalanceFillsCacheKey(offset, price) {
                      return `${price || '*'}|${offset}`;
                    }
                    function cacheRebalanceFillsPage(key, page) {
                      const knownPage = rebalanceFillsPageCache.values().next().value;
                      if (knownPage && Number(knownPage.total) !== Number(page && page.total)) {
                        rebalanceFillsPageCache.clear();
                      }
                      rebalanceFillsPageCache.delete(key);
                      rebalanceFillsPageCache.set(key, page);
                      while (rebalanceFillsPageCache.size > rebalanceFillsCacheLimit) {
                        rebalanceFillsPageCache.delete(rebalanceFillsPageCache.keys().next().value);
                      }
                    }
                    async function fetchRebalanceFillsPage(offset, price) {
                      return fetch(`/api/rebalance-fills?limit=${rebalancePageSize}&offset=${offset}${price ? '&price=' + encodeURIComponent(price) : ''}`)
                        .then(r => r.json());
                    }
                    function renderRebalanceFillsPage(page, offset) {
                      const fills = page && page.items ? page.items.map(item => item.fill || item) : [];
                      renderSimpleFills('rebalanceFills', fills, true);
                      renderRebalancePager('rebalanceFillsPager', page, offset, 'rebalance-fills-page');
                    }
                    function prefetchNextRebalanceFillsPage(page, offset, price) {
                      if (!page || !page.hasMore) return;
                      const nextOffset = offset + rebalancePageSize;
                      const nextKey = rebalanceFillsCacheKey(nextOffset, price);
                      if (rebalanceFillsPageCache.has(nextKey)) return;
                      fetchRebalanceFillsPage(nextOffset, price)
                        .then(nextPage => cacheRebalanceFillsPage(nextKey, nextPage))
                        .catch(() => {});
                    }
                    async function loadRebalanceFillsPage(force = false) {
                      const requestId = ++rebalanceFillsRequestId;
                      const offset = rebalanceFillsOffset;
                      const price = rebalanceFillPrice;
                      const key = rebalanceFillsCacheKey(offset, price);
                      if (!force && rebalanceFillsPageCache.has(key)) {
                        const cachedPage = rebalanceFillsPageCache.get(key);
                        renderRebalanceFillsPage(cachedPage, offset);
                        prefetchNextRebalanceFillsPage(cachedPage, offset, price);
                        return cachedPage;
                      }
                      if (!rebalanceFillsPageCache.has(key)) {
                        setText('rebalanceFillsPager', '读取成交记录...');
                      }
                      const page = await fetchRebalanceFillsPage(offset, price);
                      cacheRebalanceFillsPage(key, page);
                      if (requestId !== rebalanceFillsRequestId || offset !== rebalanceFillsOffset || price !== rebalanceFillPrice) return page;
                      renderRebalanceFillsPage(page, offset);
                      prefetchNextRebalanceFillsPage(page, offset, price);
                      return page;
                    }
                    function gridFillsCacheKey(offset, price) {
                      return `${price || '*'}|${offset}`;
                    }
                    function cacheGridFillsPage(key, page) {
                      const knownPage = gridFillsPageCache.values().next().value;
                      if (knownPage && Number(knownPage.total) !== Number(page && page.total)) {
                        gridFillsPageCache.clear();
                      }
                      gridFillsPageCache.delete(key);
                      gridFillsPageCache.set(key, page);
                      while (gridFillsPageCache.size > gridFillsCacheLimit) {
                        gridFillsPageCache.delete(gridFillsPageCache.keys().next().value);
                      }
                    }
                    async function fetchGridFillsPage(offset, price) {
                      const query = new URLSearchParams({limit: fillsPageSize, offset});
                      if (price) {
                        query.set('minPrice', price);
                        query.set('maxPrice', (Number(price) + 0.09999999).toFixed(8));
                      }
                      return fetch(`/api/fills-page?${query}`).then(r => r.json());
                    }
                    function prefetchNextGridFillsPage(page, offset, price) {
                      if (!page || !page.hasMore) return;
                      const nextOffset = offset + fillsPageSize;
                      const nextKey = gridFillsCacheKey(nextOffset, price);
                      if (gridFillsPageCache.has(nextKey)) return;
                      fetchGridFillsPage(nextOffset, price)
                        .then(nextPage => cacheGridFillsPage(nextKey, nextPage))
                        .catch(() => {});
                    }
                    async function loadGridFillsPage(force = false) {
                      const requestId = ++fillsRequestId;
                      const offset = fillsOffset;
                      const price = fillsPrice;
                      const key = gridFillsCacheKey(offset, price);
                      if (!force && gridFillsPageCache.has(key)) {
                        const cachedPage = gridFillsPageCache.get(key);
                        renderFills(cachedPage);
                        prefetchNextGridFillsPage(cachedPage, offset, price);
                        return;
                      }
                      if (!gridFillsPageCache.has(key)) {
                        setText('fillsPager', '读取成交记录...');
                      }
                      const page = await fetchGridFillsPage(offset, price);
                      cacheGridFillsPage(key, page);
                      if (requestId !== fillsRequestId || offset !== fillsOffset || price !== fillsPrice) return;
                      renderFills(page);
                      prefetchNextGridFillsPage(page, offset, price);
                    }
                    async function refreshGrid() {
                      const [control, status, cycles, currentCycle, solBollMonitor] = await Promise.all([
                        fetch('/api/control').then(r=>r.json()),
                        fetch('/api/active-status').then(r=>r.json()),
                        fetch('/api/cycles').then(r=>r.json()),
                        fetch('/api/current-cycle').then(r=>r.json()),
                        fetch('/api/sol-contract-boll-monitor').then(r=>r.json())
                      ]);
                      const activeOrders = (status.workingOrders || []).filter(isActiveOrder);
                      renderSummary(control, status, activeOrders);
                      renderSolBollMonitor(solBollMonitor);
                      renderOrders(activeOrders, status);
                      renderCurrentCycle(currentCycle);
                      renderCycles(cycles);
                      await loadGridFillsPage(true);
                    }
                    function renderRebalanceState(state, control) {
                      priceRangeState = state; renderPriceRange();
                      const equityUsdt = Number(state && state.totalEquityUsdt);
                      const btcPrice = Number(state && state.lastPrice);
                      setText('rebalanceCycleTotalEquityBtc', Number.isFinite(equityUsdt) && Number.isFinite(btcPrice) && btcPrice > 0
                        ? formatNumber(equityUsdt / btcPrice)
                        : '-');
                      if (!state) {
                        document.getElementById('closeRebalanceCycleButton').disabled = true;
                        document.getElementById('reduceRebalanceCycleButton').disabled = true;
                        setText('rebalanceStatus', '-');
                        setText('rebalanceCycleLock', '-');
                        setText('rebalanceCurrentPrice', '-');
                        setText('rebalanceRatio', '-');
                        setText('rebalanceSpotQty', '-');
                        setText('rebalanceContractQty', '-');
                        setText('rebalanceNetQty', '-');
                        setText('rebalanceEquity', '-');
                        return;
                      }
                      const monitor = control && control.rebalanceRunning ? '监控运行中' : '监控未运行';
                      setText('rebalanceStatus', `${monitor} / ${state.status || '-'}${state.message ? ' / ' + state.message : ''}`);
                      const closeButton = document.getElementById('closeRebalanceCycleButton');
                      if (closeButton) closeButton.disabled = document.body.dataset.busy === 'true'
                        || !Boolean(control && control.rebalanceRunning) || state.status !== 'GRID_ACTIVE';
                      const reduceButton = document.getElementById('reduceRebalanceCycleButton');
                      if (reduceButton) reduceButton.disabled = document.body.dataset.busy === 'true'
                        || !Boolean(control && control.rebalanceRunning) || state.status !== 'GRID_ACTIVE';
                      setText('rebalanceCycleLock', state.status === 'GRID_ACTIVE' ? '已锁定，禁止重复开单' : '未锁定');
                      setText('rebalanceCurrentPrice', formatNumber(state.lastPrice));
                      setText('rebalanceRatio', `${formatPercent(state.currentRatio)} / ${formatPercent(state.targetRatio)}`);
                      setText('rebalanceSpotQty', formatNumber(state.baseBalance));
                      setText('rebalanceContractQty', formatNumber(state.contractPositionQty));
                      setText('rebalanceNetQty', formatNumber(state.netBaseQty));
                      setText('rebalanceEquity', formatNumber(state.totalEquityUsdt));
                    }
                    function renderRebalanceOrders(orders, cycle) {
                      const tbody = document.getElementById('rebalanceOrders');
                      if (!tbody) return;
                      if (!orders || orders.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="11" class="muted">暂无再平衡挂单</td></tr>';
                        return;
                      }
                      const latestFillPrice = Number(cycle && cycle.latestFillPrice);
                      tbody.innerHTML = orders.map(order => `
                        <tr>
                          <td>${esc(order.status)}</td>
                          <td>${esc(order.symbol)}</td>
                          <td>${formatSide(order.side)}</td>
                          <td>${esc(order.orderType)}</td>
                          <td>${esc(order.qty)}</td>
                          <td>${esc(order.price ?? '市价')}</td>
                          <td>${formatOrderPriceDiff(order, latestFillPrice)}</td>
                          <td>${order.reduceOnly ? '是' : '否'}</td>
                          <td>${esc(order.clientOrderId)}</td>
                          <td>${esc(order.exchangeOrderId || '-')}</td>
                          <td><button class="danger compact" data-action="cancel-order" data-client-order-id="${esc(order.clientOrderId)}" data-symbol="${esc(order.symbol)}">撤单</button></td>
                        </tr>
                      `).join('');
                    }
                """).concat("""
                    function renderRebalanceCurrentCycle(cycle, state, activeOrders, recentFillItems) {
                      if (!cycle || !cycle.startedAt) {
                        setText('rebalanceCycleScenarioLabel', rebalanceScenarioLabel(null, state));
                        setText('rebalanceCyclePnl', '-');
                        setText('rebalanceCycleDirection', '-');
                        setText('rebalanceCycleStartRatio', '-');
                        setText('rebalanceCycleCurrentRatio', '-');
                        setText('rebalanceCyclePriceSummary', '-');
                        setText('rebalanceCycleWaterPrice', '-');
                        setText('rebalanceCycleWaterCurrentPrice', '-');
                        setText('rebalanceCycleHalfPrice', '-');
                        setText('rebalanceCycleExitPrice', '-');
                        setText('rebalanceCycleFullInventoryPrice', '-');
                        setText('rebalanceCycleCounts', '-');
                        setText('rebalanceCycleQty', '-');
                        setText('rebalanceCycleDuration', '-');
                        setText('rebalanceCycleCumQty', '-');
                        setText('rebalanceCycleTradingFee', '-');
                        setText('rebalanceCycleFundingFee', '-');
                        setText('rebalanceCycleFee', '-');
                        setText('rebalanceCycleExtraReduce', '-');
                        setText('rebalanceCyclePermanentReduce', '-');
                        setText('rebalanceCyclePlannedQty', '-');
                        setText('rebalanceCycleQtyDeviation', '-');
                        setText('rebalanceCycleExtraReduceAvg', '-');
                        setText('rebalanceCycleExtraAdd', '-');
                        setText('rebalanceCycleExtraAddAvg', '-');
                        setText('rebalanceCycleReleaseReserve', '-');
                        setText('rebalanceCycleReduceCap', '-');
                        setText('rebalanceCycleReduceCarry', '-');
                        setText('rebalanceCycleAddCarry', '-');
                        setText('rebalancePnlFormula', '盈亏 = 累计净数量 * 市场价 - 累计净价值 - 累计交易手续费 + 累计资金费。');
                        return;
                      }
                      setHtml('rebalanceCyclePnl', formatPnl(cycle.markPricePnl));
                      setText('rebalanceCycleDirection', formatEntryDirection(cycle.entryDirection));
                      setText('rebalanceCycleStartRatio', formatPercent(cycle.startRatio));
                      setText('rebalanceCycleCurrentRatio', formatPercent(state && state.currentRatio));
                      setHtml('rebalanceCyclePriceSummary', formatCyclePriceSummary(cycle));
                      const equityUsdt = Number(state && state.totalEquityUsdt);
                      const btcPrice = Number(cycle.marketPrice || (state && state.lastPrice));
                      const netBtcQty = Number(state && state.netBaseQty);
                      const shortCycle = isShortRebalanceCycle(cycle, state);
                      const waterPrice = costPrice(cycle);
                      setText('rebalanceCycleWaterPrice', formatPrice2(waterPrice));
                      setText('rebalanceCycleWaterCurrentPrice', formatPrice2(btcPrice));
                      setText('rebalanceCycleHalfPrice', formatPrice2(waterPrice == null ? null : waterPrice * (shortCycle ? 0.99 : 1.01)));
                      setText('rebalanceCycleExitPrice', formatPrice2(waterPrice == null ? null : waterPrice * (shortCycle ? 0.98 : 1.02)));
                      setText('rebalanceCycleScenarioLabel', rebalanceScenarioLabel(cycle, state));
                      if (shortCycle) {
                        const targetRatioEstimate = Number.isFinite(equityUsdt) && Number.isFinite(netBtcQty) && netBtcQty > 0
                          ? estimateTargetRatioPrice(state, btcPrice, cycle, activeOrders, recentFillItems)
                          : null;
                        setText('rebalanceCycleFullInventoryPrice', formatTargetRatioEstimate(targetRatioEstimate));
                      } else {
                        const fullInventoryEstimate = Number.isFinite(equityUsdt) && Number.isFinite(netBtcQty) && netBtcQty > 0
                          ? estimateFullInventoryPrice(state, btcPrice, cycle, activeOrders, recentFillItems)
                          : null;
                        setHtml('rebalanceCycleFullInventoryPrice', formatFullInventoryEstimate(fullInventoryEstimate));
                      }
                      setText('rebalanceCycleCounts', `${cycle.buyCount || 0} / ${cycle.sellCount || 0}，共 ${cycle.fillCount || 0}`);
                      setText('rebalanceCycleQty', `${formatNumber(cycle.buyQty)} / ${formatNumber(cycle.sellQty)}`);
                      setText('rebalanceCycleDuration', formatDuration(cycle.durationSeconds));
                      setText('rebalanceCycleCumQty', formatNumber(cycle.cumulativeQty));
                      const plannedCycleQty = estimatePlannedCycleQty(cycle);
                      const actualCycleQty = Number(cycle.cumulativeQty);
                      const cycleQtyDeviation = Number.isFinite(plannedCycleQty) && Number.isFinite(actualCycleQty)
                        ? actualCycleQty - plannedCycleQty
                        : NaN;
                      setText(
                        'rebalanceCyclePlannedQty',
                        Number.isFinite(plannedCycleQty)
                          ? `${formatNumber(plannedCycleQty)} / ${formatNumber(actualCycleQty)} BTC`
                          : '-'
                      );
                      setText(
                        'rebalanceCycleQtyDeviation',
                        Number.isFinite(cycleQtyDeviation)
                          ? `${cycleQtyDeviation >= 0 ? '+' : ''}${formatNumber(cycleQtyDeviation)} BTC`
                          : '-'
                      );
                      setText('rebalanceCycleTradingFee', formatNumber(cycle.cumulativeTradingFee));
                      setText('rebalanceCycleFundingFee', formatNumber(cycle.cumulativeFundingFee));
                      setText('rebalanceCycleFee', formatNumber(cycle.cumulativeFee));
                      setText('rebalanceCycleExtraReduce', formatNumber(cycle.profitReduceBoostExtraQty));
                      setText('rebalanceCyclePermanentReduce', formatNumber(cycle.permanentProfitReduceQty));
                      setText('rebalanceCycleExtraReduceAvg', formatNumber(cycle.profitReduceBoostAveragePrice));
                      setText('rebalanceCycleExtraAdd', formatNumber(cycle.lossAddBoostExtraQty));
                      setText('rebalanceCycleExtraAddAvg', formatNumber(cycle.lossAddBoostAveragePrice));
                      setText('rebalanceCycleReleaseReserve', formatNumber(cycle.profitReleaseReserveQty));
                      const extraReduceReserve = Number(cycle.profitReleaseReserveQty || 0);
                      const extraReduceCap = Math.abs(Number(cycle.cumulativeQty || 0)) * 0.5;
                      setText(
                        'rebalanceCycleReduceCap',
                        extraReduceCap > 0
                          ? `${formatNumber(extraReduceReserve)} / ${formatNumber(extraReduceCap)} (${formatPercent(extraReduceReserve / extraReduceCap)})`
                          : '-'
                      );
                      setText('rebalanceCycleReduceCarry', formatNumber(cycle.profitReduceCarryQty));
                      setText('rebalanceCycleAddCarry', formatNumber(cycle.lossAddCarryQty));
                      setText(
                        'rebalancePnlFormula',
                        `${cycle.symbol || '当前再平衡 Cycle'} 盈亏 = ${pnlFormulaText(cycle, cycle.marketPrice, cycle.markPricePnl)}`
                      );
                    }
                    function renderSimpleFills(tbodyId, fills, newestFirst = false) {
                      const tbody = document.getElementById(tbodyId);
                      if (!tbody) return;
                      if (!fills || fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="15" class="muted">暂无记录</td></tr>';
                        return;
                      }
                      const priceDiffs = priceDiffMap(fills);
                      const cumulativeTurnover = cumulativeTurnoverMap(fills);
                      const rows = newestFirst ? fills : [...fills].reverse();
                      tbody.innerHTML = rows.slice(0, 200).map(f => {
                        const boost = fillSizingBoost(f);
                        return `
                        <tr class="${boost ? 'boost-fill-row' : ''}">
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(displaySymbol(f.symbol))}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(formatNumber(f.qty))}</td>
                          <td>${sizingBoostBadge(boost)}</td>
                          <td>${esc(formatNumber(f.price))}</td>
                          <td>${formatPriceDiff(priceDiffs.get(fillKey(f)))}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(cumulativeTurnover.get(fillKey(f))))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `;
                      }).join('');
                    }
                    function toggleRebalanceFundingFees() {
                      const details = document.getElementById('rebalanceFundingFeesDetails');
                      if (!details || !details.open) return;
                      rebalanceFundingFeesOffset = 0;
                      loadRebalanceFundingFeesPage();
                    }
                    async function loadRebalanceFundingFeesPage() {
                      const details = document.getElementById('rebalanceFundingFeesDetails');
                      if (!details || !details.open) return;
                      const requestId = ++rebalanceFundingFeesRequestId;
                      setText('rebalanceFundingFeesPager', '读取资金费记录...');
                      try {
                        const page = await fetch(
                          `/api/rebalance-funding-fees?limit=${rebalancePageSize}&offset=${rebalanceFundingFeesOffset}`
                        ).then(r => r.json());
                        if (requestId !== rebalanceFundingFeesRequestId) return;
                        renderRebalanceFundingFees(page && page.items ? page.items : []);
                        renderRebalancePager(
                          'rebalanceFundingFeesPager',
                          page,
                          rebalanceFundingFeesOffset,
                          'rebalance-funding-fees-page'
                        );
                      } catch (error) {
                        if (requestId !== rebalanceFundingFeesRequestId) return;
                        setText('rebalanceFundingFeesPager', `读取失败：${error && error.message ? error.message : error}`);
                      }
                    }
                    async function loadRebalanceCyclesPage() {
                      const requestId = ++rebalanceCyclesRequestId;
                      const offset = rebalanceCyclesOffset;
                      setText('rebalanceCyclesPager', '读取 Cycle 历史...');
                      try {
                        const page = await fetch(
                          `/api/rebalance-cycles?limit=${rebalancePageSize}&offset=${offset}`
                        ).then(r => r.json());
                        if (requestId !== rebalanceCyclesRequestId || offset !== rebalanceCyclesOffset) return;
                        renderRebalanceCycles(page && page.items ? page.items : [], page && page.summary);
                        renderRebalancePager(
                          'rebalanceCyclesPager',
                          page,
                          offset,
                          'rebalance-cycles-page'
                        );
                      } catch (error) {
                        if (requestId !== rebalanceCyclesRequestId || offset !== rebalanceCyclesOffset) return;
                        setText('rebalanceCyclesPager', `读取失败：${error && error.message ? error.message : error}`);
                      }
                    }
                    function renderRebalanceFundingFees(items) {
                      const tbody = document.getElementById('rebalanceFundingFees');
                      if (!tbody) return;
                      if (!items || items.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="9" class="muted">当前 Cycle 暂无资金费记录</td></tr>';
                        return;
                      }
                      tbody.innerHTML = items.map(item => {
                        const row = item && item.fill ? item.fill : item;
                        return `
                        <tr>
                          <td>${formatTime(row.time)}</td>
                          <td>${esc(displaySymbol(row.symbol))}</td>
                          <td>${esc(formatNumber(row.fee))}</td>
                          <td>${esc(row.feeCurrency || '')}</td>
                          <td>${esc(formatNumber(row.price))}</td>
                          <td>${esc(formatNumber(item && item.cumulativeFundingFee))}</td>
                          <td>${esc(formatNumber(row.cumulativeFee))}</td>
                          <td>${formatPnl(row.totalPnlAtFill)}</td>
                          <td>${esc(row.executionId || '')}</td>
                        </tr>
                      `;
                      }).join('');
                    }
                    function renderRebalanceCycles(cycles, summary) {
                      const tbody = document.getElementById('rebalanceCycles');
                      if (!tbody) return;
                      const cycleCount = Number(summary && summary.cycleCount) || 0;
                      const summaryRow = `
                        <tr class="summary-row">
                          <td><strong>累计</strong></td>
                          <td>${esc(displaySymbol(summary && summary.symbol))}</td>
                          <td>全部</td>
                          <td>-</td>
                          <td>-</td>
                          <td>-</td>
                          <td>-</td>
                          <td>-</td>
                          <td><strong>${formatCycleCounts(summary)}（${cycleCount} 个 Cycle）</strong></td>
                          <td><strong>${formatDuration(summary && summary.durationSeconds)}</strong></td>
                          <td>-</td>
                          <td><strong>${formatPnl(summary && summary.totalPnl)}</strong></td>
                          <td>-</td>
                          <td>-</td>
                          <td>-</td>
                        </tr>
                      `;
                      const emptyRow = !cycles || cycles.length === 0
                        ? '<tr><td colspan="15" class="muted">暂无再平衡 Cycle</td></tr>'
                        : '';
                      const cycleRows = (cycles || []).slice(0, 100).map(c => `
                        <tr>
                          <td>${formatTime(c.time)}</td>
                          <td>${esc(displaySymbol(c.symbol))}</td>
                          <td>${formatEntryDirection(c.entryDirection)}</td>
                          <td>${esc(formatNumber(c.startPrice))}</td>
                          <td>${esc(formatNumber(c.price))}</td>
                          <td>${formatPercent(c.startRatio)}</td>
                          <td>${formatPercent(c.endRatio)}</td>
                          <td>${formatPercent(c.targetRatio)}</td>
                          <td>${formatCycleCounts(c)}</td>
                          <td>${formatDuration(c.durationSeconds)}</td>
                          <td>${esc(c.endReasonLabel || c.endReason || '')}</td>
                          <td>${formatPnl(c.totalPnlAtFill)}</td>
                          <td>${esc(c.clientOrderId)}</td>
                          <td>${esc(c.executionId)}</td>
                          <td><button class="compact" data-action="rebalance-cycle-detail" data-execution-id="${esc(c.executionId)}">明细</button></td>
                        </tr>
                      `).join('');
                      tbody.innerHTML = summaryRow + emptyRow + cycleRows;
                    }
                    function renderRebalancePager(elementId, page, offset, action) {
                      const el = document.getElementById(elementId);
                      if (!el) return;
                      const limit = Number(page && page.limit ? page.limit : rebalancePageSize);
                      const count = page && page.items ? page.items.length : 0;
                      const start = count === 0 ? 0 : offset + 1;
                      const end = offset + count;
                      const hasPrev = offset > 0;
                      const hasNext = Boolean(page && page.hasMore);
                      const total = Number(page && page.total);
                      const totalText = Number.isFinite(total) ? ` / 共 ${total} 条` : '';
                      const bucketStart = Number(rebalanceFillPrice);
                      const filterText = action === 'rebalance-fills-page' && Number.isFinite(bucketStart)
                        ? `，价格 ${esc(formatNumber(bucketStart))}–${esc(formatNumber(bucketStart + 10))}（不含上限）`
                        : '';
                      el.innerHTML = `
                        <button class="compact" data-action="${action}" data-delta="-1" ${hasPrev ? '' : 'disabled'}>上一页</button>
                        <span class="muted">第 ${Math.floor(offset / limit) + 1} 页，${start}-${end}${totalText}${filterText}</span>
                        <button class="compact" data-action="${action}" data-delta="1" ${hasNext ? '' : 'disabled'}>下一页</button>
                      `;
                    }
                    function applyRebalanceFillPriceFilter() {
                      const input = document.getElementById('rebalanceFillPrice');
                      const value = input ? input.value.trim() : '';
                      if (value && !Number.isFinite(Number(value))) return;
                      rebalanceFillPrice = value ? String(Math.floor(Number(value) / 10) * 10) : '';
                      if (input) input.value = rebalanceFillPrice;
                      rebalanceFillsOffset = 0;
                      rebalanceFillsPageCache.clear();
                      loadRebalanceFillsPage();
                    }
                    function clearRebalanceFillPriceFilter() {
                      const input = document.getElementById('rebalanceFillPrice');
                      if (input) input.value = '';
                      rebalanceFillPrice = '';
                      rebalanceFillsOffset = 0;
                      rebalanceFillsPageCache.clear();
                      loadRebalanceFillsPage();
                    }
                    function renderSummary(control, status, activeOrders) {
                      const running = Boolean(control && control.running);
                      const flattenStop = control && control.flattenStop ? control.flattenStop : {};
                      const flattenRunning = Boolean(flattenStop.running);
                      const flattenLocked = Boolean(control && control.flattenStopLocked);
                      const canRetryFlatten = !running && flattenLocked;
                      const state = status && status.runtimeState ? status.runtimeState.state : '-';
                      const activeSymbol = (control && control.config && control.config.symbol) || (status && status.symbol) || '-';
                      const snapshot = status && status.latestMarketSnapshot ? status.latestMarketSnapshot : {};
                      const orders = status && status.workingOrders ? status.workingOrders : [];
                      const lastOrder = orders.length ? orders[orders.length - 1] : null;
                      const badge = document.getElementById('runBadge');
                      const monitoring = running && control.recoveryStatus && control.recoveryStatus !== 'ready';
                      badge.textContent = monitoring ? '仅监控 / 待核对' : (running ? '运行中' : '未运行');
                      badge.className = `badge ${running ? 'badge-running' : 'badge-idle'}`;
                      document.getElementById('activeSymbol').textContent = activeSymbol;
                      document.getElementById('cycleStatus').textContent = cycleLabel(control, status);
                      document.getElementById('strategyState').textContent = state || '-';
                      document.getElementById('operationStatus').textContent = monitoring
                        ? `${control.recoveryStatus}；${formatOperationStatus(flattenStop)}`
                        : formatOperationStatus(flattenStop);
                      document.getElementById('lastPrice').textContent = snapshot.markPrice == null
                        ? (snapshot.lastPrice ?? '-')
                        : `${snapshot.lastPrice ?? '-'} / ${snapshot.markPrice}`;
                      document.getElementById('orderCount').textContent = activeOrders.length;
                      document.getElementById('lastOrderStatus').textContent = lastOrder
                        ? `${formatSide(lastOrder.side)} ${lastOrder.orderType} ${lastOrder.status}`
                        : '-';
                      document.getElementById('startButton').disabled = running || flattenLocked;
                      document.getElementById('resumeButton').disabled = running || flattenLocked || !Boolean(control && control.canResume);
                      document.getElementById('stopButton').disabled = !running || flattenRunning;
                      document.getElementById('flattenButton').textContent = canRetryFlatten ? '重试清仓' : '清仓并停止';
                      document.getElementById('flattenButton').disabled = (!running && !canRetryFlatten) || flattenRunning;
                      document.getElementById('unlockFlattenButton').disabled = running || !flattenLocked;
                      if (document.body.dataset.busy === 'true') {
                        document.getElementById('startButton').disabled = true;
                        document.getElementById('resumeButton').disabled = true;
                        document.getElementById('stopButton').disabled = true;
                        document.getElementById('flattenButton').disabled = true;
                        document.getElementById('unlockFlattenButton').disabled = true;
                      }
                    }
                    function renderSolBollMonitor(monitor) {
                      const stateLabels = {
                        NOT_CONFIGURED: '尚未配置',
                        MONITORING: '监控中',
                        CYCLE_RUNNING: 'Cycle运行中',
                        CYCLE_STARTED: 'Cycle已启动',
                        TRIGGERING: '正在触发',
                        BLOCKED_FLATTEN_LOCK: '清仓锁阻止启动',
                        BLOCKED_OPEN_CYCLE: '存在未结束Cycle',
                        BLOCKED_ACTIVE_ORDERS: '存在活动挂单',
                        START_REJECTED: '启动被拒绝',
                        ERROR: '监控异常'
                      };
                      const state = monitor && monitor.state ? monitor.state : 'NOT_CONFIGURED';
                      const stateText = stateLabels[state] || state;
                      const intervalMinutes = monitor && monitor.intervalMinutes != null ? Number(monitor.intervalMinutes) : null;
                      setText('solBollMonitorLabel', intervalMinutes ? `${intervalMinutes}分钟布林监控` : '布林监控');
                      setText('solBollMonitorState', monitor && monitor.message ? `${stateText}：${monitor.message}` : stateText);
                      const lastPrice = monitor && monitor.lastPrice != null ? formatNumber(monitor.lastPrice) : '-';
                      const lower = monitor && monitor.bollLower != null ? formatNumber(monitor.bollLower) : '-';
                      setText('solBollPrice', `${lastPrice} / ${lower}`);
                      const signal = monitor ? monitor.signal : null;
                      setText('solBollSignal', signal === true ? '已跌破下轨' : (signal === false ? '尚未跌破' : '-'));
                      const spacing = monitor && monitor.baseGridSpacing != null ? `${Number(monitor.baseGridSpacing) * 100}%` : '0.1%';
                      const baseQty = monitor && monitor.baseGridQty != null ? monitor.baseGridQty : 0.5;
                      const initialSteps = monitor && monitor.initialSteps != null ? monitor.initialSteps : 5;
                      setText('solBollGridConfig', `间距 ${spacing}，每格 ${baseQty} SOL，首单 ${initialSteps} 步`);
                    }
                    function cycleLabel(control, status) {
                      if (control && control.flattenStop && control.flattenStop.running) return '平仓停止中';
                      if (!control || !control.running) return '未运行';
                      if (control.recoveryStatus && control.recoveryStatus !== 'ready') return control.recoveryStatus;
                      const pair = status && status.runtimeState ? status.runtimeState.activeOrderPair : null;
                      if (pair) return '网格挂单中';
                      if (status && status.runtimeState && status.runtimeState.state === 'OPENING_FIRST_POSITION') return '开仓中';
                      return control.stopAfterCycle ? '本轮结束后停止' : '连续运行';
                    }
                    function formatOperationStatus(flattenStop) {
                      if (!flattenStop || !flattenStop.state || flattenStop.state === 'IDLE') return '-';
                      if (flattenStop.state === 'RUNNING') return flattenStop.message || '平仓并停止中';
                      if (flattenStop.state === 'COMPLETED') return flattenStop.message || '平仓并停止完成';
                      if (flattenStop.state === 'PENDING_CONFIRMATION') return `待确认：${flattenStop.message || '-'}`;
                      if (flattenStop.state === 'FAILED') return `失败：${flattenStop.message || '-'}`;
                      return flattenStop.message || flattenStop.state;
                    }
                    function isActiveOrder(order) {
                      if (!order || !['NEW', 'PARTIALLY_FILLED', 'UNTRIGGERED'].includes(order.status)) return false;
                      return order.orderType !== 'MARKET' || Boolean(order.exchangeOrderId);
                    }
                    function currentPairOrderIds(status) {
                      const pair = status && status.runtimeState ? status.runtimeState.activeOrderPair : null;
                      const ids = new Set();
                      if (pair && pair.buyOrder && pair.buyOrder.clientOrderId) ids.add(pair.buyOrder.clientOrderId);
                      if (pair && pair.sellOrder && pair.sellOrder.clientOrderId) ids.add(pair.sellOrder.clientOrderId);
                      return ids;
                    }
                    function renderOrders(orders, status) {
                      const tbody = document.getElementById('orders');
                      const notice = document.getElementById('orderNotice');
                      if (!orders || orders.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="11" class="muted">暂无挂单</td></tr>';
                        notice.className = 'notice';
                        notice.textContent = '';
                        return;
                      }
                      const pairIds = currentPairOrderIds(status);
                      const orphanOrders = orders.filter(order => !pairIds.has(order.clientOrderId));
                      if (orphanOrders.length > 0) {
                        notice.className = 'notice notice-warning';
                        notice.textContent = `检测到 ${orphanOrders.length} 笔非当前 pair 的活跃挂单，请核对后手动撤单或等待成交回报同步。`;
                      } else {
                        notice.className = 'notice';
                        notice.textContent = '';
                      }
                      tbody.innerHTML = orders.map(order => {
                        const inActivePair = pairIds.has(order.clientOrderId);
                        const relationLabel = inActivePair ? '当前 pair' : '非当前 pair';
                        const relationClass = inActivePair ? 'order-role-pair' : 'order-role-orphan';
                        return `
                        <tr class="${inActivePair ? '' : 'order-orphan'}">
                          <td>${esc(order.status)}</td>
                          <td><span class="order-role ${relationClass}">${relationLabel}</span></td>
                          <td>${esc(order.symbol)}</td>
                          <td>${formatSide(order.side)}</td>
                          <td>${esc(order.orderType)}</td>
                          <td>${esc(order.qty)}</td>
                          <td>${esc(order.price ?? '市价')}</td>
                          <td>${order.reduceOnly ? '是' : '否'}</td>
                          <td>${esc(order.clientOrderId)}</td>
                          <td>${esc(order.exchangeOrderId || '-')}</td>
                          <td><button class="danger compact" data-action="cancel-order" data-client-order-id="${esc(order.clientOrderId)}" data-symbol="${esc(order.symbol)}">撤单</button></td>
                        </tr>
                      `;
                      }).join('');
                    }
                    function renderCurrentCycle(cycle) {
                      if (!cycle || !cycle.startedAt) {
                        setText('cycleMarkPricePnl', '-');
                        setText('cycleEntryDirection', '-');
                        setText('cyclePriceSummary', '-');
                        setText('cycleCounts', '-');
                        setText('cycleQty', '-');
                        setText('cycleDuration', '-');
                        setText('cycleTimes', '-');
                        setText('cycleCumQty', '-');
                        setText('cycleTradingFee', '-');
                        setText('cycleFee', '-');
                        setText('cycleFundingFee', '-');
                        setText('cPRQ', '-');
                        setText('cPBE', '-');
                        setText('cPBEAvg', '-');
                        setText('cLABE', '-');
                        setText('cLABEAvg', '-');
                        setText('cPRR', '-');
                        setText('cPRCap', '-');
                        setText('cPRCarry', '-');
                        setText('cLACarry', '-');
                        setText('pnlFormula', '盈亏 = cumQty * 市场价 - cumValue - 净费用。');
                        return;
                      }
                      setHtml('cycleMarkPricePnl', formatPnl(cycle.markPricePnl));
                      setText('cycleEntryDirection', formatEntryDirection(cycle.entryDirection));
                      setHtml('cyclePriceSummary', formatCyclePriceSummary(cycle));
                      setText('cycleCounts', `${cycle.buyCount || 0} / ${cycle.sellCount || 0}，共 ${cycle.fillCount || 0}`);
                      setText('cycleQty', `${formatNumber(cycle.buyQty)} / ${formatNumber(cycle.sellQty)}`);
                      setText('cycleDuration', formatDuration(cycle.durationSeconds));
                      setText('cycleTimes', `${formatTime(cycle.startedAt)} / ${formatTime(cycle.latestFillAt)}`);
                      setText('cycleCumQty', formatNumber(cycle.cumulativeQty));
                      setText('cycleTradingFee', formatNumber(cycle.cumulativeTradingFee));
                      setText('cycleFundingFee', formatNumber(cycle.cumulativeFundingFee));
                      setText('cycleFee', formatNumber(cycle.cumulativeFee));
                      setText('cPRQ', formatNumber(cycle.profitableReduceQty));
                      setText('cPBE', formatNumber(cycle.profitReduceBoostExtraQty));
                      setText('cPBEAvg', formatNumber(cycle.profitReduceBoostAveragePrice));
                      setText('cLABE', formatNumber(cycle.lossAddBoostExtraQty));
                      setText('cLABEAvg', formatNumber(cycle.lossAddBoostAveragePrice));
                      setText('cPRR', formatNumber(cycle.profitReleaseReserveQty));
                      const extraReduceReserve = Number(cycle.profitReleaseReserveQty || 0);
                      const extraReduceCap = Math.abs(Number(cycle.cumulativeQty || 0)) * 0.5;
                      setText(
                        'cPRCap',
                        extraReduceCap > 0
                          ? `${formatNumber(extraReduceReserve)} / ${formatNumber(extraReduceCap)} (${formatPercent(extraReduceReserve / extraReduceCap)})`
                          : '-'
                      );
                      setText('cPRCarry', formatNumber(cycle.profitReduceCarryQty));
                      setText('cLACarry', formatNumber(cycle.lossAddCarryQty));
                      setText('pnlFormula', `${cycle.symbol || '当前 Cycle'} 盈亏 = ${pnlFormulaText(cycle, cycle.marketPrice, cycle.markPricePnl)}`);
                    }
                    function renderFills(page) {
                      const tbody = document.getElementById('fills');
                      latestFillsPage = page || {};
                      const fills = (page && page.items ? page.items : []).map(item => {
                        const fill = item.fill || item;
                        return {
                          ...fill,
                          cumulativeTurnover: item.cumulativeTurnover,
                          previousTradingPrice: item.previousTradingPrice
                        };
                      });
                      const total = Number(page && page.total || 0);
                      const tradingCount = Number(page && page.tradingCount || 0);
                      const fundingCount = Number(page && page.fundingCount || 0);
                      const totalPages = Math.max(1, Math.ceil(total / fillsPageSize));
                      const currentPage = Math.floor(fillsOffset / fillsPageSize) + 1;
                      setText(
                        'fillsPager',
                        `${currentPage} / ${totalPages}，成交 ${tradingCount} 笔`
                          + (fundingCount ? `，资金费 ${fundingCount} 笔，共 ${total} 条` : '')
                      );
                      const prev = document.getElementById('fillsPrev');
                      const next = document.getElementById('fillsNext');
                      if (prev) prev.disabled = fillsOffset <= 0;
                      if (next) next.disabled = !Boolean(page && page.hasMore);
                      if (fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="16">暂无成交</td></tr>';
                        return;
                      }
                      tbody.innerHTML = fills.map(f => {
                        const boost = fillSizingBoost(f);
                        const previousPrice = Number(f.previousTradingPrice);
                        const priceDiff = Number.isFinite(previousPrice) ? Number(f.price) - previousPrice : null;
                        return `
                        <tr class="${boost ? 'boost-fill-row' : ''}">
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(f.qty)}</td>
                          <td>${sizingBoostBadge(boost)}</td>
                          <td>${esc(f.price)}</td>
                          <td>${formatPriceDiff(priceDiff)}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(f.cumulativeTurnover))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `;
                      }).join('');
                    }
                    function previousFillsPage() {
                      fillsOffset = Math.max(0, fillsOffset - fillsPageSize);
                      loadGridFillsPage();
                    }
                    function nextFillsPage() {
                      if (!latestFillsPage || !latestFillsPage.hasMore) return;
                      fillsOffset += fillsPageSize;
                      loadGridFillsPage();
                    }
                    function applyFillPriceFilter() {
                      const input = document.getElementById('fillPrice');
                      const value = input ? input.value.trim() : '';
                      if (value && !Number.isFinite(Number(value))) return;
                      fillsPrice = value ? (Math.trunc(Number(value) * 10) / 10).toFixed(1) : '';
                      if (input) input.value = fillsPrice;
                      fillsOffset = 0;
                      gridFillsPageCache.clear();
                      loadGridFillsPage();
                    }
                    function clearFillPriceFilter() {
                      const input = document.getElementById('fillPrice');
                      if (input) input.value = '';
                      fillsPrice = '';
                      fillsOffset = 0;
                      gridFillsPageCache.clear();
                      loadGridFillsPage();
                    }
                    function renderCycles(cycles) {
                      const tbody = document.getElementById('cycles');
                      if (!cycles || cycles.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="7">暂无 Cycle</td></tr>';
                        return;
                      }
                      tbody.innerHTML = cycles.slice(0, 100).map(c => `
                        <tr>
                          <td>${formatTime(c.time)}</td>
                          <td>${esc(c.symbol)}</td>
                          <td>${esc(formatNumber(c.price))}</td>
                          <td>${formatPnl(c.totalPnlAtFill)}</td>
                          <td>${esc(c.clientOrderId)}</td>
                          <td>${esc(c.executionId)}</td>
                          <td>
                            <button class="compact" data-action="cycle-detail" data-execution-id="${esc(c.executionId)}" data-label="${esc(`${c.symbol} ${formatTime(c.time)}`)}">明细</button>
                            <button class="compact" data-action="cycle-load-config" data-execution-id="${esc(c.executionId)}" data-label="${esc(`${c.symbol} ${formatTime(c.time)}`)}">加载参数</button>
                          </td>
                        </tr>
                      `).join('');
                    }
                    async function loadCycleConfig(executionId, label) {
                      const notice = document.getElementById('cycleDetailNotice');
                      notice.className = 'notice notice-info';
                      notice.textContent = '读取 Cycle 参数...';
                      try {
                        const result = await fetch(`/api/cycle-config?executionId=${encodeURIComponent(executionId)}`).then(r => r.json());
                        if (!result.ok) {
                          notice.className = 'notice notice-error';
                          notice.textContent = result.message || '读取 Cycle 参数失败';
                          return;
                        }
                        applyConfig(result.config);
                        notice.className = result.exact ? 'notice notice-ok' : 'notice notice-warning';
                        notice.textContent = result.exact
                          ? `${label || 'Cycle'}：参数已加载`
                          : `${label || 'Cycle'}：${result.message || '参数已尽量回填，请检查后再启动'}`;
                      } catch (err) {
                        notice.className = 'notice notice-error';
                        notice.textContent = err && err.message ? err.message : '读取 Cycle 参数失败';
                      }
                    }
                    async function loadCycleDetail() {
                      const executionId = cycleDetailExecutionId;
                      const label = cycleDetailLabel;
                      const offset = cycleDetailOffset;
                      const requestId = ++cycleDetailRequestId;
                      const notice = document.getElementById('cycleDetailNotice');
                      notice.className = 'notice notice-info';
                      notice.textContent = '读取 Cycle 成交明细...';
                      try {
                        const page = await fetch(`/api/cycle-fills?executionId=${encodeURIComponent(executionId)}&limit=${cycleDetailPageSize}&offset=${offset}`).then(r => r.json());
                        if (requestId !== cycleDetailRequestId || offset !== cycleDetailOffset) return;
                        const fills = page && page.items ? page.items : [];
                        renderCycleDetail(fills, Number(page && page.turnoverBefore ? page.turnoverBefore : 0));
                        renderCycleDetailPager(page);
                        notice.className = 'notice notice-ok';
                        const total = Number(page && page.total ? page.total : fills.length);
                        const start = fills.length ? offset + 1 : 0;
                        const end = offset + fills.length;
                        notice.textContent = `${label || 'Cycle'}：共 ${total} 条，当前显示 ${start}-${end}`;
                      } catch (err) {
                        notice.className = 'notice notice-error';
                        notice.textContent = err && err.message ? err.message : '读取 Cycle 成交明细失败';
                      }
                    }
                    function renderCycleDetailPager(page) {
                      const pager = document.getElementById('cycleDetailPager');
                      if (!pager) return;
                      const total = Number(page && page.total ? page.total : 0);
                      const limit = Number(page && page.limit ? page.limit : cycleDetailPageSize);
                      const offset = Number(page && page.offset != null ? page.offset : cycleDetailOffset);
                      const count = page && page.items ? page.items.length : 0;
                      const totalPages = Math.max(1, Math.ceil(total / limit));
                      pager.innerHTML = `
                        <button class="compact" data-action="cycle-detail-page" data-delta="-1" ${offset > 0 ? '' : 'disabled'}>上一页</button>
                        <span>第 ${Math.floor(offset / limit) + 1} / ${totalPages} 页，共 ${total} 条</span>
                        <button class="compact" data-action="cycle-detail-page" data-delta="1" ${page && page.hasMore ? '' : 'disabled'}>下一页</button>
                      `;
                    }
                    async function loadRebalanceCycleDetail(executionId) {
                      try {
                        const fills = await fetch(`/api/rebalance-cycle-fills?executionId=${encodeURIComponent(executionId)}`).then(r => r.json());
                        renderSimpleFills('rebalanceCycleDetail', fills);
                      } catch (err) {
                        const tbody = document.getElementById('rebalanceCycleDetail');
                        if (tbody) tbody.innerHTML = '<tr><td colspan="13" class="muted">读取失败</td></tr>';
                      }
                    }
                    function renderCycleDetail(fills, turnoverBefore = 0) {
                      const tbody = document.getElementById('cycleDetail');
                      if (!fills || fills.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="15">暂无明细</td></tr>';
                        return;
                      }
                      const cumulativeTurnover = cumulativeTurnoverMap(fills, turnoverBefore);
                      tbody.innerHTML = fills.map(f => {
                        const boost = fillSizingBoost(f);
                        return `
                        <tr class="${boost ? 'boost-fill-row' : ''}">
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(f.qty)}</td>
                          <td>${sizingBoostBadge(boost)}</td>
                          <td>${esc(f.price)}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(cumulativeTurnover.get(fillKey(f))))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `;
                      }).join('');
                    }
                    function profitReduceBoost(fill) {
                      if (!fill || !fill.configJson) return null;
                      try {
                        const metadata = typeof fill.configJson === 'string' ? JSON.parse(fill.configJson) : fill.configJson;
                        const extraQty = Number(metadata && metadata.profitReduceBoostExtraQty);
                        if (!Number.isFinite(extraQty) || extraQty <= 0) return null;
                        return {extraQty};
                      } catch (ignored) {
                        return null;
                      }
                    }
                    function lossAddBoost(fill) {
                      if (!fill || !fill.configJson) return null;
                      try {
                        const metadata = typeof fill.configJson === 'string' ? JSON.parse(fill.configJson) : fill.configJson;
                        const extraQty = Number(metadata && metadata.lossAddBoostExtraQty);
                        if (!Number.isFinite(extraQty) || extraQty <= 0) return null;
                        return {extraQty};
                      } catch (ignored) {
                        return null;
                      }
                    }
                    function fillSizingBoost(fill) {
                      const reduce = profitReduceBoost(fill);
                      if (reduce) return {...reduce, type: 'reduce'};
                      const add = lossAddBoost(fill);
                      return add ? {...add, type: 'add'} : null;
                    }
                    function sizingBoostBadge(boost) {
                      if (!boost) return '-';
                      const label = boost.type === 'add' ? '亏损额外加仓' : '盈利额外减仓';
                      return '<span class="boost-fill-badge">' + label + ' +' + esc(formatNumber(boost.extraQty)) + '</span>';
                    }
                    function fillKey(fill) {
                      return `${fill.executionId || fill.clientOrderId || ''}|${fill.time || ''}`;
                    }
                    function cumulativeTurnoverMap(fills, initialTotal = 0) {
                      const sorted = [...(fills || [])]
                        .filter(f => f)
                        .sort((a, b) => Number(a.time || 0) - Number(b.time || 0));
                      let total = Number(initialTotal || 0);
                      const byKey = new Map();
                      sorted.forEach(fill => {
                        if (fill.side === 'BUY' || fill.side === 'SELL') {
                          const value = Number(fill.value || 0);
                          total += Math.abs(value);
                        }
                        byKey.set(fillKey(fill), total);
                      });
                      return byKey;
                    }
                    function priceDiffMap(fills) {
                      const sorted = [...(fills || [])]
                        .filter(f => f && (f.side === 'BUY' || f.side === 'SELL'))
                        .sort((a, b) => Number(a.time || 0) - Number(b.time || 0));
                      const byKey = new Map();
                      sorted.forEach((fill, index) => {
                        if (index === 0) return;
                        const diff = Number(fill.price || 0) - Number(sorted[index - 1].price || 0);
                        if (Number.isFinite(diff)) byKey.set(fillKey(fill), diff);
                      });
                      return byKey;
                    }
                    function formatSide(side) {
                      if (side === 'BUY') return '买入';
                      if (side === 'SELL') return '卖出';
                      if (side === 'FUNDING_FEE') return '资金费';
                      if (side === 'CYCLE_END') return 'Cycle结束';
                      return esc(side || '');
                    }
                    function formatRebalanceEvent(event) {
                      if (event === 'command.place') return '下单';
                      if (event === 'command.cancel') return '撤单';
                      if (event === 'rebalance.fill.received') return '成交';
                      return esc(event || '');
                    }
                    function formatCycleCounts(cycle) {
                      const buy = Number(cycle && cycle.buyCount);
                      const sell = Number(cycle && cycle.sellCount);
                      const total = Number(cycle && cycle.fillCount);
                      const safeBuy = Number.isFinite(buy) ? buy : 0;
                      const safeSell = Number.isFinite(sell) ? sell : 0;
                      const safeTotal = Number.isFinite(total) ? total : safeBuy + safeSell;
                      return `${safeBuy} / ${safeSell}，共 ${safeTotal}`;
                    }
                    function displaySymbol(symbol) {
                      return typeof symbol === 'string' && symbol.startsWith('RBAL:') ? symbol.slice(5) : (symbol || '');
                    }
                    function formatEntryDirection(direction) {
                      if (direction === 'LONG') return '做多';
                      if (direction === 'SHORT') return '做空';
                      if (direction === 'SPOT_LONG_ONLY') return '现货只做多';
                      return '-';
                    }
                    function formatTime(value) {
                      if (!value) return '';
                      const time = typeof value === 'number' && value < 1000000000000 ? value * 1000 : value;
                      return new Date(time).toLocaleString('zh-CN', {hour12:false});
                    }
                    function formatDuration(seconds) {
                      const total = Math.max(0, Number(seconds || 0));
                      const d = Math.floor(total / 86400);
                      const h = Math.floor((total % 86400) / 3600);
                      const m = Math.floor((total % 3600) / 60);
                      if (d > 0) return `${d}天 ${h}时 ${m}分`;
                      if (h > 0) return `${h}时 ${m}分`;
                      return `${m}分`;
                    }
                    function formatPnl(value) {
                      const n = Number(value || 0);
                      const cls = n > 0 ? 'pnl-pos' : (n < 0 ? 'pnl-neg' : 'pnl-flat');
                      return `<span class="${cls}">${formatNumber(value)}</span>`;
                    }
                    function formatPriceDiff(diff) {
                      if (!Number.isFinite(diff)) return '-';
                      const cls = diff > 0 ? 'pnl-pos' : (diff < 0 ? 'pnl-neg' : 'pnl-flat');
                      const sign = diff > 0 ? '+' : '';
                      return `<span class="${cls}">${sign}${formatNumber(diff)}</span>`;
                    }
                    function formatOrderPriceDiff(order, latestFillPrice) {
                      const price = Number(order && order.price);
                      if (!Number.isFinite(price) || !Number.isFinite(latestFillPrice)) return '-';
                      return formatPriceDiff(price - latestFillPrice);
                    }
                    function formatPriceWithStartChange(price, startPrice) {
                      const current = Number(price);
                      const start = Number(startPrice);
                      if (!Number.isFinite(current)) return '-';
                      if (!Number.isFinite(start) || start === 0) return formatNumber(price);
                      const change = (current - start) / start * 100;
                      const cls = change > 0 ? 'pnl-pos' : (change < 0 ? 'pnl-neg' : 'pnl-flat');
                      const sign = change > 0 ? '+' : '';
                      return `${formatNumber(price)} <span class="${cls}">(${sign}${formatNumber(change)}%)</span>`;
                    }
                    function formatCyclePriceSummary(cycle) {
                      const currentPriceText = formatPriceWithStartChange(cycle.marketPrice, cycle.startPrice);
                      const cost = costPrice(cycle);
                      const current = Number(cycle.marketPrice);
                      const costNumber = Number(cost);
                      let costText = formatNumber(cost);
                      if (cost !== null && cost !== undefined
                          && Number.isFinite(costNumber) && Number.isFinite(current) && current > 0) {
                        const change = (costNumber / current - 1) * 100;
                        const cls = change > 0 ? 'pnl-pos' : (change < 0 ? 'pnl-neg' : 'pnl-flat');
                        const sign = change > 0 ? '+' : '';
                        costText += ` <span class="${cls}">(${sign}${formatNumber(change)}%)</span>`;
                      }
                      return `
                        <span><span class="price-label">启动</span> ${formatNumber(cycle.startPrice)}</span>
                        <span><span class="price-label">当前</span> ${currentPriceText}</span>
                        <span><span class="price-label">成本</span> ${costText}</span>
                      `;
                    }
                    function cumulativePnlAtPrice(fill, price) {
                      const tradingFee = Number(fill.cumulativeTradingFee ?? fill.cumulativeFee ?? 0);
                      const fundingFee = Number(fill.cumulativeFundingFee || 0);
                      return Number(fill.cumulativeQty || 0) * Number(price || 0)
                        - Number(fill.cumulativeValue || 0)
                        - tradingFee
                        + fundingFee;
                    }
                    function pnlFormulaText(source, price, pnlValue) {
                      const pnl = pnlValue === undefined ? cumulativePnlAtPrice(source, price) : pnlValue;
                      const tradingFee = source.cumulativeTradingFee ?? source.cumulativeFee;
                      const fundingFee = source.cumulativeFundingFee || 0;
                      return `${formatNumber(source.cumulativeQty)} * ${formatNumber(price)} - (${formatNumber(source.cumulativeValue)}) - ${formatNumber(tradingFee)} + ${formatNumber(fundingFee)} = ${formatNumber(pnl)}`;
                    }
                    async function renderFundingDetail() {
                      const rows = await fetch('/api/funding-fees').then(r => r.json());
                      const tbody = document.getElementById('cycleDetail');
                      const notice = document.getElementById('cycleDetailNotice');
                      const pager = document.getElementById('cycleDetailPager');
                      if (pager) pager.innerHTML = '';
                      if (rows.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="14">当前 Cycle 暂无资金费记录</td></tr>';
                        notice.className = 'notice notice-warning';
                        notice.textContent = '当前 Cycle 暂无资金费记录';
                        return;
                      }
                      notice.className = 'notice notice-ok';
                      notice.textContent = `资金费明细：${rows.length} 条，合计 ${formatNumber(rows.reduce((sum, row) => sum + Number(row.fee || 0), 0))} USDT`;
                      tbody.innerHTML = rows.map(f => `
                        <tr>
                          <td>${formatTime(f.time)}</td>
                          <td>${esc(f.symbol)}</td>
                          <td>${formatSide(f.side)}</td>
                          <td>${esc(formatNumber(f.qty))}</td>
                          <td>${esc(formatNumber(f.price))}</td>
                          <td>${esc(formatNumber(f.value))}</td>
                          <td>${esc(formatNumber(f.fee))} ${esc(f.feeCurrency || '')}</td>
                          <td>${formatPnl(f.totalPnlAtFill)}</td>
                          <td>${esc(formatNumber(f.cumulativeQty))}</td>
                          <td>${esc(formatNumber(f.cumulativeValue))}</td>
                          <td>${esc(formatNumber(f.cumulativeFee))}</td>
                          <td>${esc(formatNumber(costPrice(f)))}</td>
                          <td>${esc(formatNumber(f.avgEntryPrice))}</td>
                          <td>${esc(f.clientOrderId)}</td>
                        </tr>
                      `).join('');
                    }
                    function formatNumber(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      if (!Number.isFinite(n)) return String(value);
                      return n.toFixed(6).replace(/\\.0+$/, '').replace(/(\\.\\d*?)0+$/, '$1');
                    }
                    function formatPrice2(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      return Number.isFinite(n) ? n.toFixed(2) : '-';
                    }
                    function isShortRebalanceCycle(cycle, state) {
                      return String(cycle && cycle.entryDirection || '').toUpperCase() === 'SHORT'
                        || String(state && state.activeSide || '').toUpperCase() === 'SELL';
                    }
                    function rebalanceScenarioLabel(cycle, state) {
                      return isShortRebalanceCycle(cycle, state)
                        ? '达到目标比例时预计 BTC 价格'
                        : '单边下跌至 100% BTC 比例（情景推演）';
                    }
                    function estimateProjectedFeeRate(cycle, recentFillItems) {
                      const cumulativeTurnover = Math.max(
                        0,
                        ...(recentFillItems || [])
                          .map(item => Number(item && item.cumulativeTurnover))
                          .filter(Number.isFinite)
                      );
                      const cumulativeTradingFee = Number(cycle && cycle.cumulativeTradingFee);
                      return Number.isFinite(cumulativeTradingFee) && cumulativeTradingFee >= 0 && cumulativeTurnover > 0
                        ? Math.min(0.001, cumulativeTradingFee / cumulativeTurnover)
                        : 0;
                    }
                    function estimateFullInventoryPrice(state, currentPrice, cycle, activeOrders, recentFillItems) {
                      const equity = Number(state && state.totalEquityUsdt);
                      const netQty = Number(state && state.netBaseQty);
                      const spacing = Number(latestRebalanceConfig && latestRebalanceConfig.baseGridSpacing);
                      const baseBuyQty = Number(latestRebalanceConfig && (latestRebalanceConfig.autoBaseGridQty || latestRebalanceConfig.baseGridQty));
                      const maxInventoryQty = Number(latestRebalanceConfig && latestRebalanceConfig.maxInventoryQty);
                      const signedCycleQty = Number(cycle && cycle.cumulativeQty) || 0;
                      const cycleQty = Math.max(0, signedCycleQty);
                      const activeBuy = (activeOrders || []).find(order =>
                        order && order.side === 'BUY' && Number.isFinite(Number(order.price)) && Number(order.price) > 0
                      );
                      const projectedFeeRate = estimateProjectedFeeRate(cycle, recentFillItems);
                      const latestFillPrice = Number(cycle && cycle.latestFillPrice);
                      let price = activeBuy
                        ? Number(activeBuy.price)
                        : (Number.isFinite(latestFillPrice) && latestFillPrice > 0 ? latestFillPrice * (1 - spacing) : NaN);
                      let buyQty = activeBuy ? Number(activeBuy.qty) : baseBuyQty;
                      if (!Number.isFinite(equity) || !Number.isFinite(netQty) || !Number.isFinite(currentPrice)
                          || !Number.isFinite(spacing) || !Number.isFinite(baseBuyQty)
                          || !Number.isFinite(price) || !Number.isFinite(buyQty)
                          || equity <= 0 || netQty < 0 || currentPrice <= 0 || spacing <= 0 || spacing >= 1
                          || baseBuyQty <= 0 || price <= 0 || buyQty <= 0) return null;
                      let nonBtcValue = equity - netQty * currentPrice;
                      if (nonBtcValue <= 0) {
                        return {
                          nonBtcValue,
                          current: {price: currentPrice, requiredQty: 0, maxAdditionalQty: 0, reachable: true},
                          initial: null
                        };
                      }

                      const maxAdditionalQty = Number.isFinite(maxInventoryQty) && maxInventoryQty > 0
                        ? Math.max(0, maxInventoryQty - cycleQty)
                        : null;
                      const current = simulateFixedFullInventoryPrice(
                        nonBtcValue,
                        price,
                        buyQty,
                        baseBuyQty,
                        spacing,
                        maxAdditionalQty,
                        projectedFeeRate
                      );
                      const startRatio = Number(cycle && cycle.startRatio);
                      const startPrice = Number(cycle && cycle.startPrice);
                      const initialNetQty = netQty - signedCycleQty;
                      const initialSteps = Number(latestRebalanceConfig && latestRebalanceConfig.autoInitialSteps);
                      const initialOrderQty = latestRebalanceConfig && latestRebalanceConfig.mode === 'auto_cycle'
                        && Number.isFinite(initialSteps) && initialSteps > 0
                        ? baseBuyQty * initialSteps
                        : baseBuyQty;
                      const initialEquity = Number.isFinite(startRatio) && startRatio > 0
                          && Number.isFinite(startPrice) && startPrice > 0
                          && Number.isFinite(initialNetQty) && initialNetQty > 0
                        ? initialNetQty * startPrice / startRatio
                        : NaN;
                      const initialNonBtcValue = initialEquity - initialNetQty * startPrice;
                      const initial = Number.isFinite(initialNonBtcValue) && initialNonBtcValue > 0
                          && Number.isFinite(initialOrderQty) && initialOrderQty > 0
                        ? simulateInitialFullInventoryPrice(
                            initialNonBtcValue,
                            startPrice,
                            initialOrderQty,
                            baseBuyQty,
                            spacing,
                            Number.isFinite(maxInventoryQty) && maxInventoryQty > 0 ? maxInventoryQty : null,
                            projectedFeeRate
                          )
                        : null;
                      return {nonBtcValue, initialNonBtcValue, projectedFeeRate, current, initial};
                    }
                    function estimateTargetRatioPrice(state, currentPrice, cycle, activeOrders, recentFillItems) {
                      const equity = Number(state && state.totalEquityUsdt);
                      const netQty = Number(state && state.netBaseQty);
                      const targetRatio = Number(state && state.targetRatio);
                      const spacing = Number(latestRebalanceConfig && latestRebalanceConfig.baseGridSpacing);
                      const baseSellQty = Number(latestRebalanceConfig && latestRebalanceConfig.baseGridQty);
                      const maxInventoryQty = Number(latestRebalanceConfig && latestRebalanceConfig.maxInventoryQty);
                      const activeSell = (activeOrders || []).find(order =>
                        order && order.side === 'SELL' && Number.isFinite(Number(order.price)) && Number(order.price) > 0
                      );
                      const latestFillPrice = Number(cycle && cycle.latestFillPrice);
                      let price = activeSell
                        ? Number(activeSell.price)
                        : (Number.isFinite(latestFillPrice) && latestFillPrice > 0 ? latestFillPrice * (1 + spacing) : NaN);
                      let sellQty = activeSell ? Number(activeSell.qty) : baseSellQty;
                      if (!Number.isFinite(equity) || !Number.isFinite(netQty) || !Number.isFinite(currentPrice)
                          || !Number.isFinite(targetRatio) || !Number.isFinite(spacing) || !Number.isFinite(baseSellQty)
                          || !Number.isFinite(price) || !Number.isFinite(sellQty)
                          || equity <= 0 || netQty <= 0 || currentPrice <= 0 || targetRatio <= 0 || targetRatio >= 1
                          || spacing <= 0 || baseSellQty <= 0 || price <= 0 || sellQty <= 0) return null;
                      const currentRatio = netQty * currentPrice / equity;
                      if (currentRatio <= targetRatio) {
                        return {price: currentPrice, requiredQty: 0, targetRatio, reachable: true};
                      }
                      const projectedFeeRate = estimateProjectedFeeRate(cycle, recentFillItems);
                      const signedCycleQty = Number(cycle && cycle.cumulativeQty) || 0;
                      const maxAdditionalQty = Number.isFinite(maxInventoryQty) && maxInventoryQty > 0
                        ? Math.max(0, maxInventoryQty - Math.abs(signedCycleQty))
                        : null;
                      let nonBtcValue = equity - netQty * currentPrice;
                      let projectedNetQty = netQty;
                      let requiredQty = 0;
                      for (let step = 0; step < 100000 && price > 0; step += 1) {
                        const availableQty = maxAdditionalQty === null
                          ? sellQty
                          : Math.min(sellQty, Math.max(0, maxAdditionalQty - requiredQty));
                        if (availableQty <= 0) {
                          return {price, requiredQty, targetRatio, reachable: false, reason: 'inventory-cap'};
                        }
                        const equityBeforeFill = nonBtcValue + projectedNetQty * price;
                        const tradingFee = availableQty * price * projectedFeeRate;
                        projectedNetQty -= availableQty;
                        nonBtcValue += availableQty * price - tradingFee;
                        requiredQty += availableQty;
                        const equityAfterFill = equityBeforeFill - tradingFee;
                        const projectedRatio = equityAfterFill > 0 ? projectedNetQty * price / equityAfterFill : NaN;
                        if (Number.isFinite(projectedRatio) && projectedRatio <= targetRatio) {
                          return {price, requiredQty, targetRatio, reachable: true};
                        }
                        if (availableQty + 1e-12 < sellQty || projectedNetQty <= 0) {
                          return {price, requiredQty, targetRatio, reachable: false, reason: 'inventory-cap'};
                        }
                        price *= (1 + spacing);
                        sellQty = baseSellQty;
                      }
                      return {price: null, requiredQty, targetRatio, reachable: false, reason: 'price-ceiling'};
                    }
                    function estimatePlannedCycleQty(cycle) {
                      const startPrice = Number(cycle && cycle.startPrice);
                      const marketPrice = Number(cycle && cycle.marketPrice);
                      const spacing = Number(latestRebalanceConfig && latestRebalanceConfig.baseGridSpacing);
                      const baseQty = Number(latestRebalanceConfig && (latestRebalanceConfig.autoBaseGridQty || latestRebalanceConfig.baseGridQty));
                      const initialSteps = Number(latestRebalanceConfig && latestRebalanceConfig.autoInitialSteps);
                      const initialQty = latestRebalanceConfig && latestRebalanceConfig.mode === 'auto_cycle'
                        && Number.isFinite(initialSteps) && initialSteps > 0
                        ? baseQty * initialSteps
                        : baseQty;
                      if (!Number.isFinite(startPrice) || !Number.isFinite(marketPrice)
                          || !Number.isFinite(spacing) || !Number.isFinite(baseQty)
                          || !Number.isFinite(initialQty) || startPrice <= 0 || marketPrice <= 0
                          || spacing <= 0 || spacing >= 1 || baseQty <= 0 || initialQty <= 0) return NaN;
                      const movedLevels = Math.floor(
                        Math.log(marketPrice / startPrice) / Math.log(1 - spacing) + 1e-9
                      );
                      return Math.max(0, initialQty + movedLevels * baseQty);
                    }
                    function simulateInitialFullInventoryPrice(nonBtcValue, startPrice, initialOrderQty, baseBuyQty, spacing, maxAdditionalQty, projectedFeeRate) {
                      const initialCost = initialOrderQty * startPrice * (1 + projectedFeeRate);
                      if (initialCost >= nonBtcValue) {
                        return {
                          price: startPrice,
                          requiredQty: initialOrderQty,
                          maxAdditionalQty,
                          reachable: maxAdditionalQty === null || initialOrderQty <= maxAdditionalQty + 1e-12,
                          reason: maxAdditionalQty !== null && initialOrderQty > maxAdditionalQty + 1e-12 ? 'inventory-cap' : null
                        };
                      }
                      const remainingCap = maxAdditionalQty === null
                        ? null
                        : Math.max(0, maxAdditionalQty - initialOrderQty);
                      const continuation = simulateFixedFullInventoryPrice(
                        nonBtcValue - initialCost,
                        startPrice * (1 - spacing),
                        baseBuyQty,
                        baseBuyQty,
                        spacing,
                        remainingCap,
                        projectedFeeRate
                      );
                      return {
                        ...continuation,
                        requiredQty: initialOrderQty + Number(continuation.requiredQty || 0),
                        maxAdditionalQty
                      };
                    }
                    function simulateFixedFullInventoryPrice(nonBtcValue, firstPrice, firstQty, baseBuyQty, spacing, maxAdditionalQty, projectedFeeRate) {
                      let remainingValue = nonBtcValue;
                      let price = firstPrice;
                      let buyQty = firstQty;
                      let requiredQty = 0;
                      for (let step = 0; step < 100000 && price > 0; step += 1) {
                        const availableQty = maxAdditionalQty === null
                          ? buyQty
                          : Math.min(buyQty, Math.max(0, maxAdditionalQty - requiredQty));
                        if (availableQty <= 0) {
                          return {price, requiredQty, maxAdditionalQty, reachable: false, reason: 'inventory-cap'};
                        }
                        remainingValue -= availableQty * price * (1 + projectedFeeRate);
                        requiredQty += availableQty;
                        if (remainingValue <= 0) {
                          return {price, requiredQty, maxAdditionalQty, reachable: true};
                        }
                        if (availableQty + 1e-12 < buyQty) {
                          return {price, requiredQty, maxAdditionalQty, reachable: false, reason: 'inventory-cap'};
                        }
                        price *= (1 - spacing);
                        buyQty = baseBuyQty;
                      }
                      return {price: null, requiredQty, maxAdditionalQty, reachable: false, reason: 'price-floor'};
                    }
                    function formatFullInventoryEstimate(estimate) {
                      if (!estimate || !estimate.current) return '-';
                      const scenarioText = (scenario, label) => {
                        if (!scenario) return `${label} -`;
                        const required = formatNumber(scenario.requiredQty);
                        if (scenario.reachable && Number.isFinite(Number(scenario.price))) {
                          return `${label} ${formatPrice2(scenario.price)}（再增加 ${required} BTC）`;
                        }
                        if (scenario.reason === 'inventory-cap') {
                          return `${label} 不可达（库存上限内最多增加 ${required} BTC）`;
                        }
                        return `${label} 不可达（价格归零前约增加 ${required} BTC）`;
                      };
                      return `<span>${esc(scenarioText(estimate.initial, '周期初始口径：'))}</span>`
                        + `<span>${esc(scenarioText(estimate.current, '当前状态口径：'))}</span>`;
                    }
                    function formatTargetRatioEstimate(estimate) {
                      if (!estimate) return '-';
                      if (estimate.reachable && Number.isFinite(Number(estimate.price))) {
                        return `${formatPrice2(estimate.price)} USDT`;
                      }
                      if (estimate.reason === 'inventory-cap') return '库存上限内不可达';
                      return '不可达';
                    }
                    function formatPercent(value) {
                      if (value === null || value === undefined || value === '') return '-';
                      const n = Number(value);
                      if (!Number.isFinite(n)) return String(value);
                      return `${formatNumber(n * 100)}%`;
                    }
                    function costPrice(fill) {
                      const qty = Number(fill && (fill.cumulativeQty ?? fill.netQty ?? 0));
                      if (!Number.isFinite(qty) || Math.abs(qty) < 1e-12) return null;
                      const value = Number(fill.cumulativeValue || 0);
                      const fee = Number(fill.cumulativeFee || 0);
                      if (!Number.isFinite(value) || !Number.isFinite(fee)) return null;
                      return (value + fee) / qty;
                    }
                    function esc(value) {
                      return String(value ?? '').replace(/[&<>"']/g, c => ({
                        '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
                      }[c]));
                    }
                    function setText(id, value) {
                      document.getElementById(id).textContent = value;
                    }
                    function setHtml(id, value) {
                      document.getElementById(id).innerHTML = value;
                    }
                    applyPageMode();
                    refresh(); setInterval(() => refresh(), 5000);
                  </script>
                </body>
                </html>
                """)
                .replace("__GRID_UI_URL__", gridUiUrl.isBlank() ? "/grid" : gridUiUrl)
                .replace("__REBALANCE_UI_URL__", rebalanceUiUrl.isBlank() ? "/rebalance" : rebalanceUiUrl);
    }
}
