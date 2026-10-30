package com.btcgrid.bybit.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bybit.api.client.domain.trade.request.TradeOrderRequest;
import com.bybit.api.client.restApi.BybitApiTradeRestClient;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.WorkingOrder;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BybitSdkOrderQueryGatewayTest {

    @Test
    void paginatesOpenOrdersAndPassesCursor() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getOpenOrders", List.of(
                response(List.of(order("open-1")), "page-2"),
                response(List.of(order("open-2")), "")
        ));

        List<WorkingOrder> orders = gateway(fake).fetchOpenOrders("SOLUSDT");

        assertEquals(List.of("open-1", "open-2"), orders.stream().map(WorkingOrder::clientOrderId).toList());
        assertEquals(2, fake.cursors("getOpenOrders").size());
        assertEquals(null, fake.cursors("getOpenOrders").get(0));
        assertEquals("page-2", fake.cursors("getOpenOrders").get(1));
    }

    @Test
    void targetedExecutionHistoryPaginatesAndDeduplicatesExecutionIds() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getTradeHistory", List.of(
                response(List.of(fill("exec-1", "GRID-1", 1_000L)), "page-2"),
                response(List.of(fill("exec-1", "GRID-1", 1_000L), fill("exec-2", "GRID-1", 2_000L)), "")
        ));
        Instant end = Instant.now();

        List<FillEvent> fills = gateway(fake).fetchExecutions(
                "SOLUSDT", Set.of("GRID-1"), end.minusSeconds(60), end
        );

        assertEquals(List.of("exec-1", "exec-2"), fills.stream().map(FillEvent::executionId).toList());
        assertEquals(List.of("GRID-1", "GRID-1"), fake.orderLinkIds("getTradeHistory"));
        assertEquals(2, fake.cursors("getTradeHistory").size());
        assertEquals(null, fake.cursors("getTradeHistory").get(0));
        assertEquals("page-2", fake.cursors("getTradeHistory").get(1));
    }

    @Test
    void orderHistoryFindsTargetOnSecondPageAfterMoreThanFiftyRows() {
        FakeTradeClient fake = new FakeTradeClient();
        List<Map<String, Object>> firstPage = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            firstPage.add(order("OTHER-" + index));
        }
        fake.responses("getOrderHistory", List.of(
                response(firstPage, "page-2"),
                response(List.of(order("GRID-target")), "")
        ));

        List<WorkingOrder> orders = gateway(fake).fetchRecentOrders("SOLUSDT", Set.of("GRID-target"));

        assertEquals(List.of("GRID-target"), orders.stream().map(WorkingOrder::clientOrderId).toList());
        assertEquals(2, fake.cursors("getOrderHistory").size());
        assertEquals("page-2", fake.cursors("getOrderHistory").get(1));
    }

    @Test
    void orderHistoryFailureOnSecondPageDoesNotReturnFirstPage() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getOrderHistory", List.of(
                response(List.of(order("GRID-target")), "page-2"),
                Map.of("retCode", 10006, "retMsg", "request expired", "result", Map.of("list", List.of()))
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gateway(fake).fetchRecentOrders("SOLUSDT", Set.of("GRID-target"))
        );

        assertTrue(error.getMessage().contains("retCode=10006"));
    }

    @Test
    void terminalOrderWithoutCumulativeExecutedQtyFailsClosed() {
        FakeTradeClient fake = new FakeTradeClient();
        Map<String, Object> terminal = new LinkedHashMap<>(order("GRID-filled"));
        terminal.put("orderStatus", "Filled");
        terminal.remove("cumExecQty");
        fake.responses("getOrderHistory", List.of(response(List.of(terminal), "")));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gateway(fake).fetchRecentOrders("SOLUSDT", Set.of("GRID-filled"))
        );

        assertTrue(error.getMessage().contains("cumExecQty"));
    }

    @Test
    void missingRetCodeFailsClosed() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getOpenOrders", List.of(Map.of(
                "retMsg", "missing code",
                "result", Map.of("list", List.of())
        )));

        assertThrows(IllegalStateException.class, () -> gateway(fake).fetchOpenOrders("SOLUSDT"));
    }

    @Test
    void paginationApiErrorFailsInsteadOfReturningEarlierPages() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getOpenOrders", List.of(
                response(List.of(order("open-1")), "page-2"),
                Map.of("retCode", 10001, "retMsg", "permission denied", "result", Map.of("list", List.of()))
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gateway(fake).fetchOpenOrders("SOLUSDT")
        );

        assertTrue(error.getMessage().contains("retCode=10001"));
    }

    @Test
    void repeatedCursorFailsClosed() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getOpenOrders", List.of(
                response(List.of(order("open-1")), "same"),
                response(List.of(order("open-2")), "same")
        ));

        assertThrows(IllegalStateException.class, () -> gateway(fake).fetchOpenOrders("SOLUSDT"));
    }

    @Test
    void executionRangeOlderThanConservativeSevenDayWindowIsRejected() {
        Instant end = Instant.now().minusSeconds(8 * 24 * 60 * 60L);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> gateway(new FakeTradeClient()).fetchExecutions(
                        "SOLUSDT", Set.of("GRID-1"), end.minusSeconds(1), end
                )
        );

        assertTrue(error.getMessage().contains("seven-day"));
    }

    @Test
    void recentExecutionQueryUsesTheFullSevenDayWindowWithoutSelfRejecting() {
        FakeTradeClient fake = new FakeTradeClient();
        fake.responses("getTradeHistory", List.of(response(List.of(), "")));

        assertTrue(gateway(fake).fetchRecentExecutions("SOLUSDT", Set.of("GRID-1")).isEmpty());
    }

    @Test
    void emptyTargetSetDoesNotBroadenIntoWholeSymbolQuery() {
        FakeTradeClient fake = new FakeTradeClient();
        Instant end = Instant.now();

        assertTrue(gateway(fake).fetchExecutions(
                "SOLUSDT", Set.of(), end.minusSeconds(60), end
        ).isEmpty());
        assertTrue(fake.requests.isEmpty());
    }

    @Test
    void missingExecutionFieldsFailClosed() {
        for (String field : List.of("execId", "execQty", "execPrice", "execTime", "execValue", "execFee")) {
            FakeTradeClient fake = new FakeTradeClient();
            Map<String, Object> malformed = new LinkedHashMap<>(fill("exec-missing", "GRID-1", 1_000L));
            malformed.remove(field);
            fake.responses("getTradeHistory", List.of(response(List.of(malformed), "")));
            Instant end = Instant.now();

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> gateway(fake).fetchExecutions("SOLUSDT", Set.of("GRID-1"), end.minusSeconds(60), end)
            );

            assertTrue(error.getMessage().contains(field), "missing field: " + field);
        }
    }

    @Test
    void contradictoryDuplicateExecutionIdFailsClosed() {
        FakeTradeClient fake = new FakeTradeClient();
        Map<String, Object> contradictory = new LinkedHashMap<>(fill("exec-duplicate", "GRID-1", 1_000L));
        contradictory.put("execQty", "2");
        fake.responses("getTradeHistory", List.of(
                response(List.of(fill("exec-duplicate", "GRID-1", 1_000L)), "page-2"),
                response(List.of(contradictory), "")
        ));
        Instant end = Instant.now();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gateway(fake).fetchExecutions("SOLUSDT", Set.of("GRID-1"), end.minusSeconds(60), end)
        );

        assertTrue(error.getMessage().contains("exec-duplicate"));
    }

    private BybitSdkOrderQueryGateway gateway(FakeTradeClient fake) {
        BybitSdkClientFactory factory = new BybitSdkClientFactory(
                new BybitSdkConfig("", "", false, false, false, false, 5_000L)
        ) {
            @Override
            public BybitApiTradeRestClient createTradeRestClient() {
                return fake.client;
            }
        };
        return new BybitSdkOrderQueryGateway(factory, ProductCategory.SPOT);
    }

    private static Map<String, Object> response(List<Map<String, Object>> rows, String cursor) {
        return Map.of(
                "retCode", 0,
                "retMsg", "OK",
                "result", Map.of("list", rows, "nextPageCursor", cursor)
        );
    }

    private static Map<String, Object> order(String id) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("symbol", "SOLUSDT");
        order.put("orderLinkId", id);
        order.put("orderId", "exchange-" + id);
        order.put("side", "Buy");
        order.put("orderType", "Limit");
        order.put("price", "80");
        order.put("qty", "1");
        order.put("orderStatus", "New");
        order.put("cumExecQty", "0");
        order.put("createdTime", "1000");
        order.put("updatedTime", "1000");
        return order;
    }

    private static Map<String, Object> fill(String executionId, String clientOrderId, long time) {
        return Map.of(
                "symbol", "SOLUSDT",
                "execId", executionId,
                "orderLinkId", clientOrderId,
                "side", "Buy",
                "execQty", "1",
                "execPrice", "80",
                "execTime", Long.toString(time),
                "execValue", "80",
                "execFee", "0.01",
                "feeCurrency", "USDT"
        );
    }

    private static final class FakeTradeClient {
        private final Map<String, List<Object>> responsesByMethod = new LinkedHashMap<>();
        private final List<RequestRecord> requests = new ArrayList<>();
        private int responseIndex;
        private final BybitApiTradeRestClient client = (BybitApiTradeRestClient) Proxy.newProxyInstance(
                BybitApiTradeRestClient.class.getClassLoader(),
                new Class<?>[]{BybitApiTradeRestClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (!name.equals("getOpenOrders") && !name.equals("getOrderHistory") && !name.equals("getTradeHistory")) {
                        return null;
                    }
                    TradeOrderRequest request = (TradeOrderRequest) args[0];
                    requests.add(new RequestRecord(name, request.getCursor(), request.getOrderLinkId()));
                    List<Object> responses = responsesByMethod.getOrDefault(name, List.of());
                    if (responseIndex >= responses.size()) {
                        throw new AssertionError("no fake response for " + name + " call " + responseIndex);
                    }
                    return responses.get(responseIndex++);
                }
        );

        private void responses(String method, List<?> responses) {
            responseIndex = 0;
            responsesByMethod.put(method, new ArrayList<>(responses));
        }

        private List<String> cursors(String method) {
            return requests.stream().filter(request -> request.method().equals(method)).map(RequestRecord::cursor).toList();
        }

        private List<String> orderLinkIds(String method) {
            return requests.stream().filter(request -> request.method().equals(method)).map(RequestRecord::orderLinkId).toList();
        }
    }

    private record RequestRecord(String method, String cursor, String orderLinkId) {
    }
}
