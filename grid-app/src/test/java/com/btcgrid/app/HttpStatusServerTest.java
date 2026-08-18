package com.btcgrid.app;

import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import com.btcgrid.strategy.service.ClientOrderIdFactory;
import com.btcgrid.strategy.service.GridOrderPlanner;
import com.btcgrid.strategy.service.GridStrategyEngine;
import com.btcgrid.strategy.service.GridTriggerEvaluator;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.ProductCategory;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpStatusServerTest {

    @Test
    void exchangesShortLivedSsoTokenForGridSession() throws Exception {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String secretBase64 = Base64.getEncoder().encodeToString(secret);
        long now = Instant.now().getEpochSecond();
        String token = handoffToken(secret, now, now + 60, "grid-direct");
        RuntimeStatusSnapshot snapshot = new RuntimeStatusSnapshot(
                Instant.now(), "BTCUSDT", null, null, null, 0, List.of()
        );

        try (HttpStatusServer server = new HttpStatusServer(
                "127.0.0.1", 0, () -> snapshot, null, new StructuredEventLogger(),
                "operator", "correct-password", "", "", secretBase64
        )) {
            server.start();
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<String> exchange = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.port() + "/grid?token=" + token
                    )).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            String setCookie = exchange.headers().firstValue("Set-Cookie").orElse("");
            String cookie = setCookie.split(";", 2)[0];
            assertEquals(302, exchange.statusCode());
            assertEquals("/grid", exchange.headers().firstValue("Location").orElse(""));
            assertTrue(cookie.startsWith("btc_grid_session="));
            assertTrue(setCookie.contains("SameSite=Lax"));

            HttpResponse<String> grid = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/grid"))
                            .header("Cookie", cookie)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, grid.statusCode());
            assertTrue(grid.body().contains("BTC Grid 控制台"));

            String rebalanceToken = handoffToken(secret, now, now + 60, "rebalance-direct");
            HttpResponse<String> rebalanceExchange = client.send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.port() + "/rebalance?token=" + rebalanceToken
                    )).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            String rebalanceSetCookie = rebalanceExchange.headers().firstValue("Set-Cookie").orElse("");
            assertEquals(302, rebalanceExchange.statusCode());
            assertEquals("/rebalance", rebalanceExchange.headers().firstValue("Location").orElse(""));
            assertTrue(rebalanceSetCookie.startsWith("btc_grid_session="));
            assertTrue(rebalanceSetCookie.contains("SameSite=Lax"));
            assertTrue(HttpStatusServer.verifySsoHandoff(
                    rebalanceToken, secretBase64, now, "rebalance-direct"
            ));
            assertFalse(HttpStatusServer.verifySsoHandoff(
                    rebalanceToken, secretBase64, now, "grid-direct"
            ));
        }

        assertFalse(HttpStatusServer.verifySsoHandoff(
                handoffToken(secret, now, now + 60, "wrong-audience"), secretBase64, now
        ));
        assertFalse(HttpStatusServer.verifySsoHandoff(token + "x", secretBase64, now));
        assertFalse(HttpStatusServer.verifySsoHandoff(token, secretBase64, now + 61));
    }

    @Test
    void servesHealthAndOrdersEndpoints() throws Exception {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("BTCUSDT");
        GridStrategyEngine engine = new GridStrategyEngine(
                config(),
                new InstrumentSpec("BTCUSDT", new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.1"), new BigDecimal("0.1")),
                ledger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory())
        );
        WorkingOrderRegistry workingOrderRegistry = new WorkingOrderRegistry();
        RuntimeStatusProvider provider = new RuntimeStatusProvider(
                "BTCUSDT",
                engine,
                ledger,
                workingOrderRegistry,
                new AtomicReference<MarketSnapshot>(),
                Set.of()
        );

        try (HttpStatusServer server = new HttpStatusServer(
                "127.0.0.1",
                0,
                provider::snapshot,
                null,
                new StructuredEventLogger(),
                "operator",
                "correct-password",
                "",
                ""
        )) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            int port = server.port();
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    "operator:correct-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> browserRedirect = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/grid")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> login = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "username=operator&password=correct-password&next=%2Fgrid"
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            String sessionCookie = login.headers().firstValue("Set-Cookie").orElse("").split(";", 2)[0];
            HttpResponse<String> cookieGrid = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/grid"))
                            .header("Cookie", sessionCookie)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> orders = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders"))
                            .header("Authorization", authorization)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> grid = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/grid"))
                            .header("Authorization", authorization)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<byte[]> compressedGrid = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/grid"))
                            .header("Authorization", authorization)
                            .header("Accept-Encoding", "gzip")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"ok\":true"));
            assertEquals(401, unauthorized.statusCode());
            assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").orElse("").contains("Basic"));
            assertTrue(unauthorized.headers().firstValue("WWW-Authenticate").orElse("")
                    .contains("realm=\"BTC Grid " + port + "\""));
            assertEquals(302, browserRedirect.statusCode());
            assertTrue(browserRedirect.headers().firstValue("Location").orElse("").startsWith("/login?next="));
            assertEquals(302, login.statusCode());
            assertTrue(sessionCookie.startsWith("btc_grid_session="));
            assertEquals(200, cookieGrid.statusCode());
            assertTrue(cookieGrid.body().contains("BTC Grid 控制台"));
            assertEquals(200, orders.statusCode());
            assertEquals("[]", orders.body());
            assertEquals(200, grid.statusCode());
            assertTrue(grid.body().contains("<th>数量</th><th>加减仓标记</th><th>价格</th>"));
            assertTrue(grid.body().contains("盈利额外减仓"));
            assertTrue(grid.body().contains("亏损额外加仓"));
            assertTrue(!grid.body().contains("<th>基础数量</th>"));
            assertTrue(grid.body().contains("function profitReduceBoost(fill)"));
            assertTrue(grid.body().contains("function lossAddBoost(fill)"));
            assertTrue(grid.body().contains("平均额外减仓价格"));
            assertTrue(grid.body().contains("平均额外加仓价格"));
            assertTrue(grid.body().contains("启动 / 当前 / 成本价格"));
            assertTrue(grid.body().contains("id=\"cyclePriceSummary\""));
            assertTrue(grid.body().contains("id=\"rebalanceCyclePriceSummary\""));
            assertTrue(grid.body().contains("costNumber / current - 1"));
            assertTrue(grid.body().contains("待释放额外减仓"));
            assertTrue(grid.body().contains("id=\"rebalanceCycleExtraReduce\""));
            assertTrue(grid.body().contains("id=\"rebalanceCyclePermanentReduce\""));
            assertTrue(grid.body().contains("固定 0.005 网格理论 / 实际净增"));
            assertTrue(grid.body().contains("function estimatePlannedCycleQty(cycle)"));
            assertTrue(grid.body().contains("id=\"rebalanceCycleExtraAdd\""));
            assertTrue(grid.body().contains("id=\"rebalanceCycleReleaseReserve\""));
            assertTrue(grid.body().contains("id=\"rebalanceCycleReduceCap\""));
            assertTrue(grid.body().contains("extraReduceReserve / extraReduceCap"));
            assertTrue(grid.body().contains("id=\"cPRCap\""));
            assertTrue(grid.body().contains("id=\"rebalancePnlFormula\""));
            assertTrue(grid.body().contains("当前再平衡 Cycle'} 盈亏 = ${pnlFormulaText"));
            assertTrue(grid.body().contains("成交价格（按十位）"));
            assertTrue(grid.body().contains("Math.floor(Number(value) / 10) * 10"));
            assertTrue(grid.body().contains("（不含上限）"));
            assertTrue(grid.body().contains("成交价格（忽略小数点后第二位）"));
            assertTrue(grid.body().contains("id=\"fillPrice\""));
            assertFalse(grid.body().contains("id=\"fillPriceMin\""));
            assertFalse(grid.body().contains("id=\"fillPriceMax\""));
            assertTrue(grid.body().contains("Math.trunc(Number(value) * 10) / 10"));
            assertTrue(grid.body().contains("query.set('minPrice', price)"));
            assertTrue(grid.body().contains("Number(price) + 0.09999999"));
            assertTrue(grid.body().contains("const rebalanceFillsPageCache = new Map()"));
            assertTrue(grid.body().contains("function prefetchNextRebalanceFillsPage"));
            assertTrue(grid.body().contains("async function loadRebalanceFillsPage(force = false)"));
            assertTrue(grid.body().contains("const gridFillsPageCache = new Map()"));
            assertTrue(grid.body().contains("function prefetchNextGridFillsPage"));
            assertTrue(grid.body().contains("async function loadGridFillsPage(force = false)"));
            assertTrue(grid.body().contains("单边下跌至 100% BTC 比例（情景推演）"));
            assertTrue(grid.body().contains("id=\"rebalanceFundingFeesDetails\""));
            assertTrue(grid.body().contains("（点击显示）"));
            assertTrue(grid.body().contains("/api/rebalance-funding-fees?limit=${rebalancePageSize}&offset=${rebalanceFundingFeesOffset}"));
            assertTrue(grid.body().contains("data-action=\"${action}\""));
            assertTrue(grid.body().contains("function simulateInitialFullInventoryPrice"));
            assertTrue(grid.body().contains("baseBuyQty * initialSteps"));
            assertTrue(grid.body().contains("const activeBuy = (activeOrders || []).find"));
            assertTrue(grid.body().contains("fetch('/api/rebalance-fills?limit=20&offset=0')"));
            assertTrue(grid.body().contains("周期初始口径："));
            assertTrue(grid.body().contains("当前状态口径："));
            assertFalse(grid.body().contains("相对周期初始"));
            assertFalse(grid.body().contains("当前口径会随正常网格净买卖变化"));
            assertFalse(grid.body().contains("预估交易费率"));
            assertTrue(grid.body().contains("let refreshInFlight = false"));
            assertTrue(grid.body().contains("fetch('/api/active-status')"));
            assertTrue(grid.body().contains("/api/fills-page?"));
            assertTrue(grid.body().contains("page && page.total"));
            assertFalse(grid.body().contains("fetch('/api/fills')"));
            assertFalse(grid.body().contains("fetch('/api/recent-fills?limit=200')"));
            assertEquals("gzip", compressedGrid.headers().firstValue("Content-Encoding").orElse(""));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressedGrid.body()))) {
                assertTrue(new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("BTC Grid 控制台"));
            }
        }
    }

    @Test
    void redirectsRebalancePageToDedicatedRuntime() throws Exception {
        RuntimeStatusSnapshot snapshot = new RuntimeStatusSnapshot(
                Instant.now(),
                "BTCUSDT",
                null,
                null,
                null,
                0,
                List.of()
        );
        try (HttpStatusServer server = new HttpStatusServer(
                "127.0.0.1",
                0,
                () -> snapshot,
                null,
                new StructuredEventLogger(),
                "",
                "",
                "http://152.67.114.172:8787/grid",
                "http://152.67.114.172:8789/rebalance"
        )) {
            server.start();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/rebalance"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    );

            assertEquals(302, response.statusCode());
            assertEquals(
                    "http://152.67.114.172:8789/rebalance",
                    response.headers().firstValue("Location").orElse("")
            );

            HttpResponse<String> gridResponse = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/grid"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
            assertEquals(302, gridResponse.statusCode());
            assertEquals(
                    "http://152.67.114.172:8787/grid",
                    gridResponse.headers().firstValue("Location").orElse("")
            );
        }
    }

    private GridStrategyConfig config() {
        return new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("100"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("0.20"),
                new BigDecimal("2.00"),
                new BigDecimal("2.00"),
                4,
                55,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                false,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
    }

    private static String handoffToken(byte[] secret, long issuedAt, long expiresAt, String audience) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"QH-HANDOFF\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String payload = encoder.encodeToString((
                "{\"aud\":\"" + audience + "\",\"exp\":" + expiresAt
                        + ",\"iat\":" + issuedAt + ",\"iss\":\"quant-home\",\"sub\":\"user\"}"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        );
        return header + "." + payload + "." + signature;
    }
}
