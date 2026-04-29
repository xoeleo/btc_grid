package com.btcgrid.backtest.engine;

import com.btcgrid.common.config.GridStrategyConfig;
import com.btcgrid.common.model.InstrumentSpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class BacktestReportWriter {

    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SHANGHAI_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(SHANGHAI_ZONE);

    public void write(Path outputDir, GridStrategyConfig config, InstrumentSpec instrumentSpec, BacktestResult result) {
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("summary.txt"), buildSummary(config, instrumentSpec, result), StandardCharsets.UTF_8);
            Files.write(outputDir.resolve("events.csv"), utf8BomLines(buildEventsCsv(result.events())));
            Files.write(outputDir.resolve("trades.csv"), utf8BomLines(buildTradesCsv(result.events())));
            Files.write(outputDir.resolve("equity.csv"), utf8BomLines(buildEquityCsv(result.equityCurve())));
            Files.write(outputDir.resolve("cycles.csv"), utf8BomLines(buildCyclesCsv(result.completedCycles(), result.openCycle())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write backtest report to " + outputDir, e);
        }
    }

    private byte[] utf8BomLines(List<String> lines) {
        String content = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[payload.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(payload, 0, withBom, 3, payload.length);
        return withBom;
    }

    private String buildSummary(GridStrategyConfig config, InstrumentSpec instrumentSpec, BacktestResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("Backtest Summary").append(System.lineSeparator());
        builder.append("symbol=").append(config.symbol()).append(System.lineSeparator());
        builder.append("category=").append(config.category().name()).append(System.lineSeparator());
        builder.append("csv-run-from=").append(result.startTime()).append(System.lineSeparator());
        builder.append("csv-run-to=").append(result.endTime()).append(System.lineSeparator());
        builder.append("snapshots=").append(result.snapshotsProcessed()).append(System.lineSeparator());
        builder.append("ordersPlaced=").append(result.ordersPlaced()).append(System.lineSeparator());
        builder.append("ordersCanceled=").append(result.ordersCanceled()).append(System.lineSeparator());
        builder.append("fills=").append(result.fillsProcessed()).append(System.lineSeparator());
        builder.append("finalState=").append(result.finalState()).append(System.lineSeparator());
        builder.append("inventoryMode=").append(result.inventoryMode()).append(System.lineSeparator());
        builder.append("openOrders=").append(result.openOrders()).append(System.lineSeparator());
        builder.append("finalNetQty=").append(result.finalNetQty()).append(System.lineSeparator());
        builder.append("avgEntryPrice=").append(result.avgEntryPrice()).append(System.lineSeparator());
        builder.append("realizedPnl=").append(result.realizedPnl()).append(System.lineSeparator());
        builder.append("floatingPnl=").append(result.floatingPnl()).append(System.lineSeparator());
        builder.append("totalFees=").append(result.totalFees()).append(System.lineSeparator());
        builder.append("grossPnl=").append(result.totalPnl()).append(System.lineSeparator());
        builder.append("netPnl=").append(result.netPnl()).append(System.lineSeparator());
        builder.append("maxDrawdown=").append(result.maxDrawdown()).append(System.lineSeparator());
        builder.append("grossRealizedProfit=").append(result.grossRealizedProfit()).append(System.lineSeparator());
        builder.append("grossRealizedLoss=").append(result.grossRealizedLoss()).append(System.lineSeparator());
        builder.append("winningCloseCount=").append(result.winningCloseCount()).append(System.lineSeparator());
        builder.append("losingCloseCount=").append(result.losingCloseCount()).append(System.lineSeparator());
        builder.append("breakevenCloseCount=").append(result.breakevenCloseCount()).append(System.lineSeparator());
        builder.append("winRate=").append(result.winRate()).append(System.lineSeparator());
        builder.append("profitFactor=").append(result.profitFactor()).append(System.lineSeparator());
        builder.append("peakInventoryQty=").append(result.peakInventoryQty()).append(System.lineSeparator());
        builder.append("feeToGrossPnlRatio=").append(result.feeToGrossPnlRatio()).append(System.lineSeparator());
        builder.append("completedCycles=").append(result.completedCycles().size()).append(System.lineSeparator());
        builder.append("qtyStep=").append(instrumentSpec.qtyStep()).append(System.lineSeparator());
        builder.append("priceTick=").append(instrumentSpec.priceTick()).append(System.lineSeparator());
        return builder.toString();
    }

    private List<String> buildEventsCsv(List<BacktestEvent> events) {
        List<String> lines = new ArrayList<>();
        lines.add("time_shanghai,cycle_id,event_type,client_order_id,side,qty,price,amount,reduce_only,reason,trade_category,order_context,qty_calc_detail,boll_upper,boll_middle,boll_lower,grid_state,inventory_mode,net_qty,position_after_fill,avg_entry_price,realized_pnl,realized_pnl_delta,floating_pnl,total_fees,gross_pnl,net_pnl,open_orders,reference_price,previous_leg_distance,distance_multiplier,base_spacing,placement_distance,derived_steps,effective_steps,order_steps,min_step_applied");
        for (BacktestEvent event : events) {
            lines.add(csv(
                    shanghaiTime(event.timestamp()),
                    event.cycleId(),
                    event.eventType(),
                    event.clientOrderId(),
                    event.side(),
                    event.qty(),
                    event.price(),
                    event.amount(),
                    event.reduceOnly(),
                    event.reason(),
                    event.tradeCategory(),
                    event.orderContext(),
                    event.qtyCalcDetail(),
                    event.bollUpper(),
                    event.bollMiddle(),
                    event.bollLower(),
                    event.gridState(),
                    event.inventoryMode(),
                    event.netQty(),
                    event.positionAfterFill(),
                    event.avgEntryPrice(),
                    event.realizedPnl(),
                    event.realizedPnlDelta(),
                    event.floatingPnl(),
                    event.totalFees(),
                    event.totalPnl(),
                    event.netPnl(),
                    event.openOrders(),
                    event.referencePrice(),
                    event.previousLegDistance(),
                    event.distanceMultiplier(),
                    event.baseSpacing(),
                    event.placementDistance(),
                    event.derivedSteps(),
                    event.effectiveSteps(),
                    event.effectiveSteps(),
                    event.minStepApplied()
            ));
        }
        return lines;
    }

    private List<String> buildTradesCsv(List<BacktestEvent> events) {
        List<String> lines = new ArrayList<>();
        lines.add("time_shanghai,cycle_id,side,qty,amount,price,current_pnl,realized_pnl_delta,position_after_fill,avg_entry_price_after,trade_category,order_context,qty_calc_detail,boll_upper,boll_middle,boll_lower,reference_price,previous_leg_distance,distance_multiplier,base_spacing,placement_distance,derived_steps,effective_steps,order_steps,min_step_applied");
        for (BacktestEvent event : events) {
            if (!"FILL".equals(event.eventType())) {
                continue;
            }
            BigDecimal currentPnl = event.referencePrice() == null
                    ? BigDecimal.ZERO
                    : event.price().subtract(event.referencePrice());
            lines.add(csv(
                    shanghaiTime(event.timestamp()),
                    event.cycleId(),
                    event.side(),
                    event.qty(),
                    event.amount(),
                    event.price(),
                    currentPnl,
                    event.realizedPnlDelta(),
                    event.positionAfterFill(),
                    event.avgEntryPrice(),
                    event.tradeCategory(),
                    event.orderContext(),
                    event.qtyCalcDetail(),
                    event.bollUpper(),
                    event.bollMiddle(),
                    event.bollLower(),
                    event.referencePrice(),
                    event.previousLegDistance(),
                    event.distanceMultiplier(),
                    event.baseSpacing(),
                    event.placementDistance(),
                    event.derivedSteps(),
                    event.effectiveSteps(),
                    event.effectiveSteps(),
                    event.minStepApplied()
            ));
        }
        return lines;
    }

    private List<String> buildCyclesCsv(List<CompletedCycle> cycles, CompletedCycle openCycle) {
        List<String> lines = new ArrayList<>();
        lines.add("cycle_id,start_time_shanghai,end_time_shanghai,duration_minutes,entry_side,entry_price,exit_side,exit_price,max_inventory_qty,max_inventory_usdt,max_loss_pnl,realized_pnl,fill_count");
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        int totalFills = 0;
        for (CompletedCycle cycle : cycles) {
            totalRealizedPnl = totalRealizedPnl.add(cycle.realizedPnl());
            totalFills += cycle.fillCount();
            lines.add(csv(
                    cycle.cycleId(),
                    shanghaiTime(cycle.startTime()),
                    shanghaiTime(cycle.endTime()),
                    cycle.duration().toMinutes(),
                    cycle.entrySide(),
                    cycle.entryPrice(),
                    cycle.exitSide(),
                    cycle.exitPrice(),
                    cycle.maxInventoryQty(),
                    cycle.maxInventoryUsdt(),
                    cycle.maxLossPnl(),
                    cycle.realizedPnl(),
                    cycle.fillCount()
            ));
        }
        if (openCycle != null) {
            totalRealizedPnl = totalRealizedPnl.add(openCycle.realizedPnl());
            totalFills += openCycle.fillCount();
            lines.add(csv(
                    "OPEN_CYCLE",
                    shanghaiTime(openCycle.startTime()),
                    shanghaiTime(openCycle.endTime()),
                    openCycle.duration().toMinutes(),
                    openCycle.entrySide(),
                    openCycle.entryPrice(),
                    openCycle.exitSide(),
                    openCycle.exitPrice(),
                    openCycle.maxInventoryQty(),
                    openCycle.maxInventoryUsdt(),
                    openCycle.maxLossPnl(),
                    openCycle.realizedPnl(),
                    openCycle.fillCount()
            ));
        }
        lines.add(csv("TOTAL", null, null, null, null, null, null, null, null, null, null, totalRealizedPnl, totalFills));
        return lines;
    }

    private List<String> buildEquityCsv(List<BacktestEquityPoint> equityCurve) {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,last_price,net_qty,avg_entry_price,realized_pnl,floating_pnl,total_fees,gross_pnl,net_pnl,grid_state,inventory_mode,open_orders");
        for (BacktestEquityPoint point : equityCurve) {
            lines.add(csv(
                    point.timestamp(),
                    point.lastPrice(),
                    point.netQty(),
                    point.avgEntryPrice(),
                    point.realizedPnl(),
                    point.floatingPnl(),
                    point.totalFees(),
                    point.totalPnl(),
                    point.netPnl(),
                    point.gridState(),
                    point.inventoryMode(),
                    point.openOrders()
            ));
        }
        return lines;
    }

    private String csv(Object... values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escape(values[i]));
        }
        return builder.toString();
    }

    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains(System.lineSeparator())) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private String shanghaiTime(Instant instant) {
        return instant == null ? "" : SHANGHAI_FORMATTER.format(instant);
    }
}
