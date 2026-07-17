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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpStatusServerTest {

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

        try (HttpStatusServer server = new HttpStatusServer(0, provider, new StructuredEventLogger())) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            int port = server.port();

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> orders = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> grid = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/grid")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"ok\":true"));
            assertEquals(200, orders.statusCode());
            assertEquals("[]", orders.body());
            assertEquals(200, grid.statusCode());
            assertTrue(grid.body().contains("<th>数量</th><th>减仓标记</th><th>价格</th>"));
            assertTrue(grid.body().contains("盈利加减仓 +"));
            assertTrue(!grid.body().contains("<th>基础数量</th>"));
            assertTrue(grid.body().contains("function profitReduceBoost(fill)"));
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
}
