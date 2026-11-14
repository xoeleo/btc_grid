package com.btcgrid.app;

import com.btcgrid.bybit.adapter.BybitAccountGateway;
import com.btcgrid.bybit.adapter.BybitOrderSubmission;
import com.btcgrid.bybit.adapter.BybitTradeGateway;
import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.BandZone;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.common.model.WorkingOrder;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCommandExecutorTest {

    @Test
    void rejectsSpotSellWhenWalletHasLessThanStrategyOrder() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("1.5")));

        List<String> failures = executor.execute(List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("2.0")))), ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(failures.get(0).contains("inventory"));
    }

    @Test
    void doesNotRewriteSpotSellQuantityUsingBigDecimalScale() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("1.71409376")));

        List<String> failures = executor.execute(List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("1.717")))), ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(failures.get(0).contains("inventory"));
    }

    @Test
    void doesNotDeclareCycleEndWhenTradingWalletBaseBalanceIsZero() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        List<String> depleted = new ArrayList<>();
        LiveCommandExecutor executor = executor(
                tradeGateway,
                new FixedAccountGateway(BigDecimal.ZERO),
                (symbol, clientOrderId, ledgerSnapshot) -> depleted.add(symbol + ":" + clientOrderId)
        );

        executor.execute(List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("2.0")))), ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(depleted.isEmpty());
    }

    @Test
    void doesNotDeclareCycleEndWhenSpotSellIsRejectedForInsufficientBalance() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        tradeGateway.submitFailure = new RuntimeException("createOrder rejected by Bybit: retCode=170131, retMsg=Insufficient balance.");
        List<String> depleted = new ArrayList<>();
        LiveCommandExecutor executor = executor(
                tradeGateway,
                new FixedAccountGateway(new BigDecimal("2.0")),
                (symbol, clientOrderId, ledgerSnapshot) -> depleted.add(symbol + ":" + clientOrderId)
        );

        List<String> failures = executor.execute(
                List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("1.7")))),
                ledger()
        );

        assertEquals(1, tradeGateway.submitted.size());
        assertTrue(depleted.isEmpty());
        assertTrue(failures.get(0).contains("170131"));
    }

    @Test
    void manualFlattenUsesStrategyLedgerBoundaryInsteadOfWholeWallet() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("45.216015900")));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("2"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertTrue(failures.isEmpty());
        assertEquals(1, tradeGateway.submitted.size());
        assertEquals(new BigDecimal("2"), tradeGateway.submitted.get(0).qty());
    }

    @Test
    void manualFlattenReturnsSubmissionFailure() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        tradeGateway.submitFailure = new RuntimeException("exchange rejected flatten");
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("45.216")));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("2"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertEquals(List.of("exchange rejected flatten"), failures);
    }

    @Test
    void manualFlattenRejectsWalletOnlyInventoryWhenStrategyLedgerIsZero() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("45.216")));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("45.216"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger(BigDecimal.ZERO));

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(failures.get(0).contains("rejected"));
    }

    @Test
    void manualFlattenRejectsSpotBuy() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("2")));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.BUY, OrderType.MARKET,
                new BigDecimal("2"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(failures.get(0).contains("spot flatten"));
    }

    @Test
    void manualFlattenRejectsMissingAccountSnapshotSource() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, (BybitAccountGateway) null);
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("2"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(failures.get(0).contains("rejected"));
    }

    @Test
    void manualFlattenRejectsStaleAccountSnapshot() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway,
                new FixedAccountGateway(new BigDecimal("2"), Instant.now().minus(Duration.ofMinutes(10))));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("2"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(failures.get(0).contains("rejected"));
    }

    @Test
    void cancelDoesNotMarkLocalOrderCanceledOrSubmitReplacement() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackSubmitted(order("OLD", TradeSide.BUY, new BigDecimal("1")), Instant.now());
        LiveCommandExecutor executor = executor(tradeGateway, registry, new FixedAccountGateway(new BigDecimal("2")));

        List<String> failures = executor.execute(List.of(
                StrategyCommand.cancel("SOLUSDT", "OLD", "manual-requote"),
                StrategyCommand.place(order("NEW", TradeSide.SELL, new BigDecimal("1")))
        ), ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertEquals(OrderStatus.NEW, registry.snapshot().stream().filter(o -> o.clientOrderId().equals("OLD")).findFirst().orElseThrow().status());
        assertTrue(failures.stream().anyMatch(f -> f.contains("waiting for cancel reconciliation")));
    }

    @Test
    void unconfirmedCancelBlocksPlaceAcrossExecuteCallsUntilTerminalUpdate() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackSubmitted(order("OLD", TradeSide.BUY, new BigDecimal("1")), Instant.now());
        LiveCommandExecutor executor = executor(tradeGateway, registry, new FixedAccountGateway(new BigDecimal("2")));

        assertTrue(executor.execute(
                List.of(StrategyCommand.cancel("SOLUSDT", "OLD", "manual-requote")), ledger()).isEmpty());
        assertEquals(OrderStatus.NEW, only(registry, "OLD").status());
        assertTrue(only(registry, "OLD").cancellationRequested());

        List<String> blocked = executor.execute(
                List.of(StrategyCommand.place(order("NEW", TradeSide.SELL, new BigDecimal("1")))), ledger());
        assertEquals(0, tradeGateway.submitted.size());
        assertTrue(blocked.stream().anyMatch(message -> message.contains("cancel reconciliation")));

        registry.applyUpdate(new com.btcgrid.common.model.OrderUpdateEvent(
                "SOLUSDT", "OLD", "exchange-old", OrderStatus.CANCELED, TradeSide.BUY,
                new BigDecimal("81"), new BigDecimal("1"), BigDecimal.ZERO, Instant.now()
        ));
        assertTrue(executor.execute(
                List.of(StrategyCommand.place(order("NEW", TradeSide.SELL, new BigDecimal("1")))), ledger()).isEmpty());
        assertEquals(1, tradeGateway.submitted.size());
        assertEquals("NEW", tradeGateway.submitted.get(0).clientOrderId());
    }

    @Test
    void beforeExchangeMutationFailurePreventsPlaceRequest() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        LiveCommandExecutor executor = executor(tradeGateway, registry, new FixedAccountGateway(new BigDecimal("2")));
        executor.setBeforeExchangeMutation(() -> {
            throw new IllegalStateException("persist failed");
        });

        assertThrows(IllegalStateException.class, () -> executor.execute(
                List.of(StrategyCommand.place(order("PLACE-1", TradeSide.BUY, new BigDecimal("1")))), ledger()));
        assertEquals(0, tradeGateway.submitted.size());
        assertEquals(OrderStatus.NEW, only(registry, "PLACE-1").status());
    }

    @Test
    void beforeExchangeMutationFailurePreventsCancelRequest() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        registry.trackSubmitted(order("CANCEL-1", TradeSide.BUY, new BigDecimal("1")), Instant.now());
        LiveCommandExecutor executor = executor(tradeGateway, registry, new FixedAccountGateway(new BigDecimal("2")));
        executor.setBeforeExchangeMutation(() -> {
            throw new IllegalStateException("persist failed");
        });

        assertThrows(IllegalStateException.class, () -> executor.execute(
                List.of(StrategyCommand.cancel("SOLUSDT", "CANCEL-1", "manual-requote")), ledger()));
        assertEquals(0, tradeGateway.cancelled.size());
        assertTrue(only(registry, "CANCEL-1").cancellationRequested());
    }

    @Test
    void beforeExchangeMutationFailurePreventsManualFlattenRequest() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        WorkingOrderRegistry registry = new WorkingOrderRegistry();
        LiveCommandExecutor executor = executor(tradeGateway, registry, new FixedAccountGateway(new BigDecimal("2")));
        executor.setBeforeExchangeMutation(() -> {
            throw new IllegalStateException("persist failed");
        });
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_HOOK", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("2"), null, false, "manual-flatten-and-stop"
        );

        assertThrows(IllegalStateException.class, () -> executor.executeManualFlatten(flatten, ledger()));
        assertEquals(0, tradeGateway.submitted.size());
        assertEquals(OrderStatus.NEW, only(registry, "MANUAL_FLATTEN_HOOK").status());
    }

    private WorkingOrder only(WorkingOrderRegistry registry, String clientOrderId) {
        return registry.snapshot().stream()
                .filter(order -> clientOrderId.equals(order.clientOrderId()))
                .findFirst()
                .orElseThrow();
    }

    private LiveCommandExecutor executor(CapturingTradeGateway tradeGateway, BybitAccountGateway accountGateway) {
        return executor(tradeGateway, accountGateway, null);
    }

    private LiveCommandExecutor executor(
            CapturingTradeGateway tradeGateway,
            WorkingOrderRegistry registry,
            BybitAccountGateway accountGateway
    ) {
        GridStrategyConfig config = config();
        EventLogger logger = new NoopLogger();
        return new LiveCommandExecutor(
                tradeGateway,
                registry,
                new RiskGuardService(config),
                logger,
                new AlertService(logger),
                ProductCategory.SPOT,
                accountGateway,
                null
        );
    }

    private LiveCommandExecutor executor(
            CapturingTradeGateway tradeGateway,
            BybitAccountGateway accountGateway,
            LiveCommandExecutor.SpotInventoryDepletedHandler depletedHandler
    ) {
        GridStrategyConfig config = config();
        EventLogger logger = new NoopLogger();
        return new LiveCommandExecutor(
                tradeGateway,
                new WorkingOrderRegistry(),
                new RiskGuardService(config),
                logger,
                new AlertService(logger),
                ProductCategory.SPOT,
                accountGateway,
                depletedHandler
        );
    }

    private GridStrategyConfig config() {
        return new GridStrategyConfig(
                ProductCategory.SPOT,
                "SOLUSDT",
                EntryDirection.SPOT_LONG_ONLY,
                new BigDecimal("0.0016"),
                new BigDecimal("0.85"),
                BigDecimal.ONE,
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("300"),
                new BigDecimal("300"),
                4,
                20,
                20,
                new BigDecimal("2"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5)
        );
    }

    private StrategyLedgerSnapshot ledger() {
        return ledger(new BigDecimal("2"));
    }

    private StrategyLedgerSnapshot ledger(BigDecimal netQty) {
        return new StrategyLedgerSnapshot(
                "SOLUSDT",
                netQty,
                new BigDecimal("80"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("80"),
                TradeSide.BUY,
                BandZone.INSIDE_BAND,
                0,
                0,
                InventoryMode.NORMAL,
                Instant.now()
        );
    }

    private OrderIntent order(String clientOrderId, TradeSide side, BigDecimal qty) {
        return new OrderIntent("SOLUSDT", clientOrderId, side, OrderType.LIMIT, qty, new BigDecimal("81"), false, "test");
    }

    private static class CapturingTradeGateway implements BybitTradeGateway {
        private final List<OrderIntent> submitted = new ArrayList<>();
        private final List<String> cancelled = new ArrayList<>();
        private RuntimeException submitFailure;

        @Override
        public BybitOrderSubmission submit(OrderIntent orderIntent) {
            submitted.add(orderIntent);
            if (submitFailure != null) {
                throw submitFailure;
            }
            return new BybitOrderSubmission(orderIntent.clientOrderId() + "-exchange", orderIntent.clientOrderId());
        }

        @Override
        public void cancel(String symbol, String clientOrderId) {
            cancelled.add(clientOrderId);
        }
    }

    private static class FixedAccountGateway implements BybitAccountGateway {
        private final BigDecimal baseBalance;
        private final Instant snapshotTime;

        private FixedAccountGateway(BigDecimal baseBalance) {
            this(baseBalance, Instant.now());
        }

        private FixedAccountGateway(BigDecimal baseBalance, Instant snapshotTime) {
            this.baseBalance = baseBalance;
            this.snapshotTime = snapshotTime;
        }

        @Override
        public AccountAssetSnapshot walletSnapshot(
                String accountType,
                String baseCoin,
                String quoteCoin,
                ProductCategory positionCategory,
                String symbol
        ) {
            return snapshot(accountType, baseCoin, quoteCoin, positionCategory, symbol);
        }

        @Override
        public AccountAssetSnapshot tradingWalletSnapshot(
                String accountType,
                String baseCoin,
                String quoteCoin,
                ProductCategory positionCategory,
                String symbol
        ) {
            return snapshot(accountType, baseCoin, quoteCoin, positionCategory, symbol);
        }

        private AccountAssetSnapshot snapshot(
                String accountType,
                String baseCoin,
                String quoteCoin,
                ProductCategory positionCategory,
                String symbol
        ) {
            return new AccountAssetSnapshot(
                    snapshotTime,
                    accountType,
                    baseCoin,
                    quoteCoin,
                    baseBalance,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    baseBalance,
                    BigDecimal.ZERO
            );
        }
    }

    private static class NoopLogger implements EventLogger {
        @Override
        public void info(String eventType, java.util.Map<String, Object> payload) {
        }

        @Override
        public void warn(String eventType, java.util.Map<String, Object> payload) {
        }

        @Override
        public void error(String eventType, java.util.Map<String, Object> payload) {
        }
    }
}
