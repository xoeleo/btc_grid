package com.btcgrid.backtest.engine;

import com.btcgrid.common.config.EntryDirection;
import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.GridState;
import com.btcgrid.common.model.InstrumentSpec;
import com.btcgrid.common.model.InventoryMode;
import com.btcgrid.common.model.ProductCategory;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesSummaryEventsAndEquityFiles() throws Exception {
        BacktestReportWriter writer = new BacktestReportWriter();
        writer.write(tempDir, config(), instrumentSpec(), result());

        assertTrue(Files.exists(tempDir.resolve("summary.txt")));
        assertTrue(Files.exists(tempDir.resolve("events.csv")));
        assertTrue(Files.exists(tempDir.resolve("trades.csv")));
        assertTrue(Files.exists(tempDir.resolve("equity.csv")));
        assertTrue(Files.exists(tempDir.resolve("cycles.csv")));
        assertTrue(Files.readString(tempDir.resolve("summary.txt")).contains("symbol=BTCUSDT"));
        assertTrue(Files.readString(tempDir.resolve("summary.txt")).contains("completedCycles=1"));
        assertTrue(Files.readString(tempDir.resolve("events.csv")).contains("boll_upper,boll_middle,boll_lower"));
        assertTrue(Files.readString(tempDir.resolve("trades.csv")).contains(
                "time_shanghai,cycle_id,side,qty,amount,price,current_pnl,realized_pnl_delta,position_after_fill,avg_entry_price_after,trade_category,order_context,qty_calc_detail,boll_upper,boll_middle,boll_lower,reference_price,previous_leg_distance,distance_multiplier,base_spacing,placement_distance,derived_steps,effective_steps,order_steps,min_step_applied"
        ));
        assertTrue(Files.readString(tempDir.resolve("equity.csv")).contains("last_price"));
        assertTrue(Files.readString(tempDir.resolve("cycles.csv")).contains(
                "cycle_id,start_time_shanghai,end_time_shanghai,duration_minutes,entry_side,entry_price,exit_side,exit_price,max_inventory_qty,max_inventory_usdt,max_loss_pnl,realized_pnl,fill_count"
        ));
        assertTrue(Files.readString(tempDir.resolve("cycles.csv")).contains("OPEN_CYCLE"));
        assertTrue(Files.readString(tempDir.resolve("cycles.csv")).contains("TOTAL"));
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

    private InstrumentSpec instrumentSpec() {
        return new InstrumentSpec(
                "BTCUSDT",
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1")
        );
    }

    private BacktestResult result() {
        Instant now = Instant.parse("2026-04-22T00:00:00Z");
        return new BacktestResult(
                now,
                now.plusSeconds(14400),
                2,
                3,
                1,
                1,
                new BigDecimal("0.010"),
                new BigDecimal("70000"),
                new BigDecimal("5.0"),
                new BigDecimal("2.5"),
                new BigDecimal("0.5"),
                new BigDecimal("7.5"),
                new BigDecimal("7.0"),
                new BigDecimal("1.2"),
                new BigDecimal("5.0"),
                new BigDecimal("1.0"),
                1,
                0,
                0,
                BigDecimal.ONE,
                new BigDecimal("5.0"),
                new BigDecimal("0.50"),
                new BigDecimal("0.06666667"),
                GridState.GRID_ACTIVE,
                InventoryMode.NORMAL,
                2,
                List.of(new CompletedCycle(
                        1,
                        now,
                        now.plusSeconds(14400),
                        Duration.ofMinutes(240),
                        TradeSide.BUY,
                        new BigDecimal("69900"),
                        TradeSide.SELL,
                        new BigDecimal("70100"),
                        new BigDecimal("0.010"),
                        new BigDecimal("701.0"),
                        new BigDecimal("-3.0"),
                        new BigDecimal("5.0"),
                        1
                )),
                new CompletedCycle(
                        2,
                        now.plusSeconds(14400),
                        now.plusSeconds(18000),
                        Duration.ofMinutes(60),
                        TradeSide.SELL,
                        new BigDecimal("70200"),
                        TradeSide.BUY,
                        new BigDecimal("70150"),
                        new BigDecimal("0.020"),
                        new BigDecimal("1404.0"),
                        new BigDecimal("-7.5"),
                        new BigDecimal("-2.0"),
                        3
                ),
                List.of(new BacktestEvent(
                        now,
                        1,
                        "PLACE",
                        "A1",
                        TradeSide.BUY,
                        new BigDecimal("0.01"),
                        new BigDecimal("69900"),
                        new BigDecimal("699"),
                        false,
                        "grid-buy",
                        "中线以下",
                        "网格续挂",
                        "distance=100 / spacing=50 => steps=2 * baseQty=0.01 * boost=1 => finalQty=0.02",
                        new BigDecimal("71000"),
                        new BigDecimal("70000"),
                        new BigDecimal("69000"),
                        GridState.GRID_ACTIVE,
                        InventoryMode.NORMAL,
                        new BigDecimal("0.01"),
                        new BigDecimal("0.01"),
                        new BigDecimal("70000"),
                        new BigDecimal("5.0"),
                        BigDecimal.ZERO,
                        new BigDecimal("2.5"),
                        new BigDecimal("0.5"),
                        new BigDecimal("7.5"),
                        new BigDecimal("7.0"),
                        2,
                        new BigDecimal("70000"),
                        new BigDecimal("50"),
                        new BigDecimal("1.2"),
                        new BigDecimal("50"),
                        new BigDecimal("50"),
                        new BigDecimal("1.2"),
                        new BigDecimal("2"),
                        false
                )),
                List.of(new BacktestEquityPoint(
                        now.plusSeconds(14400),
                        new BigDecimal("70100"),
                        new BigDecimal("0.01"),
                        new BigDecimal("70000"),
                        new BigDecimal("5.0"),
                        new BigDecimal("2.5"),
                        new BigDecimal("0.5"),
                        new BigDecimal("7.5"),
                        new BigDecimal("7.0"),
                        GridState.GRID_ACTIVE,
                        InventoryMode.NORMAL,
                        2
                ))
        );
    }
}
