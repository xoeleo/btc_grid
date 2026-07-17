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
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.model.StrategyLedgerSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCommandExecutorTest {

    @Test
    void capsSpotSellOrderToTradingWalletBaseBalance() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("1.5")));

        executor.execute(List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("2.0")))), ledger());

        assertEquals(1, tradeGateway.submitted.size());
        assertEquals(new BigDecimal("1.5"), tradeGateway.submitted.get(0).qty());
    }

    @Test
    void roundsCappedSpotSellOrderDownToRequestedQuantityScale() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("1.71409376")));

        executor.execute(List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("1.717")))), ledger());

        assertEquals(1, tradeGateway.submitted.size());
        assertEquals(new BigDecimal("1.714"), tradeGateway.submitted.get(0).qty());
    }

    @Test
    void notifiesCycleEndWhenTradingWalletBaseBalanceIsZero() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        List<String> depleted = new ArrayList<>();
        LiveCommandExecutor executor = executor(
                tradeGateway,
                new FixedAccountGateway(BigDecimal.ZERO),
                (symbol, clientOrderId, ledgerSnapshot) -> depleted.add(symbol + ":" + clientOrderId)
        );

        executor.execute(List.of(StrategyCommand.place(order("SELL-1", TradeSide.SELL, new BigDecimal("2.0")))), ledger());

        assertEquals(0, tradeGateway.submitted.size());
        assertEquals(List.of("SOLUSDT:SELL-1"), depleted);
    }

    @Test
    void notifiesInventoryDepletedWhenSpotSellIsRejectedForInsufficientBalance() {
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
        assertEquals(List.of("SOLUSDT:SELL-1"), depleted);
        assertTrue(failures.get(0).contains("170131"));
    }

    @Test
    void manualFlattenBypassesGridMaxOrderQtyAndUsesRealWalletBalance() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("45.216015900")));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("45.216015900"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertTrue(failures.isEmpty());
        assertEquals(1, tradeGateway.submitted.size());
        assertEquals(new BigDecimal("45.216015900"), tradeGateway.submitted.get(0).qty());
    }

    @Test
    void manualFlattenReturnsSubmissionFailure() {
        CapturingTradeGateway tradeGateway = new CapturingTradeGateway();
        tradeGateway.submitFailure = new RuntimeException("exchange rejected flatten");
        LiveCommandExecutor executor = executor(tradeGateway, new FixedAccountGateway(new BigDecimal("45.216")));
        OrderIntent flatten = new OrderIntent(
                "SOLUSDT", "MANUAL_FLATTEN_TEST", TradeSide.SELL, OrderType.MARKET,
                new BigDecimal("45.216"), null, false, "manual-flatten-and-stop"
        );

        List<String> failures = executor.executeManualFlatten(flatten, ledger());

        assertEquals(List.of("exchange rejected flatten"), failures);
    }

    private LiveCommandExecutor executor(CapturingTradeGateway tradeGateway, BybitAccountGateway accountGateway) {
        return executor(tradeGateway, accountGateway, null);
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
        return new StrategyLedgerSnapshot(
                "SOLUSDT",
                new BigDecimal("2"),
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
        }
    }

    private static class FixedAccountGateway implements BybitAccountGateway {
        private final BigDecimal baseBalance;

        private FixedAccountGateway(BigDecimal baseBalance) {
            this.baseBalance = baseBalance;
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
                    Instant.now(),
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
