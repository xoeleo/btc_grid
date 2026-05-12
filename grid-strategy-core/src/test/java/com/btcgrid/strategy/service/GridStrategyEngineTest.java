package com.btcgrid.strategy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.IndicatorSnapshot;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderType;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.StrategyCommand;
import com.btcgrid.common.model.StrategyCommandType;
import com.btcgrid.common.model.TradeSide;
import com.btcgrid.ledger.service.InMemoryStrategyLedger;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GridStrategyEngineTest {

    @Test
    void manualStartPlacesGridImmediatelyAfterInitialFill() {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("BTCUSDT");
        GridStrategyEngine engine = new GridStrategyEngine(
                config(),
                instrument(),
                ledger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory()),
                true
        );
        MarketSnapshot market = market();

        List<StrategyCommand> initialCommands = engine.onMarket(market);
        assertEquals(1, initialCommands.size());
        assertEquals(StrategyCommandType.PLACE, initialCommands.get(0).type());
        assertEquals(OrderType.MARKET, initialCommands.get(0).orderIntent().type());
        assertEquals(GridState.OPENING_FIRST_POSITION, engine.runtimeState().state());

        FillEvent initialFill = new FillEvent(
                "exec-1",
                "BTCUSDT",
                initialCommands.get(0).clientOrderId(),
                TradeSide.BUY,
                new BigDecimal("0.050"),
                new BigDecimal("100"),
                market.timestamp()
        );

        List<StrategyCommand> gridCommands = engine.onFill(initialFill, market);

        assertEquals(2, gridCommands.size());
        assertEquals(OrderType.LIMIT, gridCommands.get(0).orderIntent().type());
        assertEquals(OrderType.LIMIT, gridCommands.get(1).orderIntent().type());
        assertEquals("manual-grid|buy", gridCommands.get(0).reason());
        assertEquals("manual-grid|sell", gridCommands.get(1).reason());
        assertEquals(GridState.GRID_ACTIVE, engine.runtimeState().state());
    }

    @Test
    void replanActivePairCancelsResidualOrderAndPlacesNewPair() {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("BTCUSDT");
        GridStrategyEngine engine = new GridStrategyEngine(
                config(),
                instrument(),
                ledger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory()),
                true
        );
        MarketSnapshot market = market();
        List<StrategyCommand> initialCommands = engine.onMarket(market);
        engine.onFill(new FillEvent(
                "exec-1",
                "BTCUSDT",
                initialCommands.get(0).clientOrderId(),
                TradeSide.BUY,
                new BigDecimal("0.050"),
                new BigDecimal("100"),
                market.timestamp()
        ), market);
        String activeBuyId = engine.runtimeState().activeOrderPair().buyOrder().clientOrderId();

        List<StrategyCommand> commands = engine.replanActivePair(market, Set.of(activeBuyId), "manual-requote");

        assertEquals(3, commands.size());
        assertEquals(StrategyCommandType.CANCEL, commands.get(0).type());
        assertEquals(activeBuyId, commands.get(0).clientOrderId());
        assertEquals(StrategyCommandType.PLACE, commands.get(1).type());
        assertEquals(StrategyCommandType.PLACE, commands.get(2).type());
        assertEquals("manual-requote|buy", commands.get(1).reason());
        assertEquals("manual-requote|sell", commands.get(2).reason());
        assertEquals(GridState.GRID_ACTIVE, engine.runtimeState().state());
    }

    @Test
    void outOfPairFillThatFlattensInventoryEndsCycleAndCancelsActivePair() {
        InMemoryStrategyLedger ledger = new InMemoryStrategyLedger("BTCUSDT");
        GridStrategyEngine engine = new GridStrategyEngine(
                config(),
                instrument(),
                ledger,
                new GridTriggerEvaluator(),
                new GridOrderPlanner(new ClientOrderIdFactory()),
                true
        );
        MarketSnapshot market = market();
        List<StrategyCommand> initialCommands = engine.onMarket(market);
        engine.onFill(new FillEvent(
                "exec-1",
                "BTCUSDT",
                initialCommands.get(0).clientOrderId(),
                TradeSide.BUY,
                new BigDecimal("0.010"),
                new BigDecimal("100"),
                market.timestamp()
        ), market);
        String activeBuyId = engine.runtimeState().activeOrderPair().buyOrder().clientOrderId();
        String activeSellId = engine.runtimeState().activeOrderPair().sellOrder().clientOrderId();

        List<StrategyCommand> commands = engine.onFill(new FillEvent(
                "exec-old",
                "BTCUSDT",
                "GRID_BTCUSDT_PS_old_filled",
                TradeSide.SELL,
                new BigDecimal("0.010"),
                new BigDecimal("101"),
                market.timestamp()
        ), market);

        assertEquals(2, commands.size());
        assertEquals(StrategyCommandType.CANCEL, commands.get(0).type());
        assertEquals(activeBuyId, commands.get(0).clientOrderId());
        assertEquals("cycle-end", commands.get(0).reason());
        assertEquals(StrategyCommandType.CANCEL, commands.get(1).type());
        assertEquals(activeSellId, commands.get(1).clientOrderId());
        assertEquals("cycle-end", commands.get(1).reason());
        assertEquals(GridState.INIT, engine.runtimeState().state());
    }

    private GridStrategyConfig config() {
        return new GridStrategyConfig(
                ProductCategory.LINEAR,
                "BTCUSDT",
                EntryDirection.BOTH,
                new BigDecimal("0.01"),
                new BigDecimal("0.85"),
                new BigDecimal("0.01"),
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                4,
                20,
                20,
                new BigDecimal("2.0"),
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                new BigDecimal("0.30"),
                true,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5)
        );
    }

    private InstrumentSpec instrument() {
        return new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
    }

    private MarketSnapshot market() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        return new MarketSnapshot(
                "BTCUSDT",
                timestamp,
                new BigDecimal("100"),
                new IndicatorSnapshot(
                        timestamp,
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("100"),
                        new BigDecimal("90")
                )
        );
    }
}
