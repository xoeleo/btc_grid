package com.btcgrid.app;

import com.btcgrid.common.model.FillEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Aggregates actual executions, never submitted order quantities. */
final class RebalanceFillNotifier {
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Shanghai"));
    private final Map<String, BigDecimal> quantities = new HashMap<>();
    private final Map<String, BigDecimal> thresholds = new HashMap<>();
    private final Set<String> executions = new HashSet<>();
    private final Set<String> notified = new HashSet<>();
    private final Consumer<String> sender;

    RebalanceFillNotifier(Consumer<String> sender) {
        this.sender = sender;
    }

    synchronized void accept(FillEvent fill, BigDecimal baseQty, boolean historical) {
        accept(fill, baseQty, historical, () -> null, "USDT");
    }

    synchronized void accept(FillEvent fill, BigDecimal baseQty, boolean historical,
            Supplier<CurrentCycleSummary> cycleSummary, String quoteCoin) {
        if (fill == null || !RebalanceService.isRebalanceClientOrder(fill.clientOrderId())
                || fill.fillQty() == null || fill.fillQty().signum() <= 0
                || baseQty == null || baseQty.signum() <= 0) {
            return;
        }
        // Without an execution identity we cannot safely aggregate stream/REST duplicates.
        if (fill.executionId() == null || fill.executionId().isBlank()
                || !executions.add(fill.executionId())) {
            return;
        }
        String order = fill.clientOrderId();
        BigDecimal threshold = thresholds.computeIfAbsent(order, ignored -> baseQty.multiply(new BigDecimal("3")));
        BigDecimal total = quantities.merge(order, fill.fillQty(), BigDecimal::add);
        if (total.compareTo(threshold) >= 0 && notified.add(order) && !historical) {
            CurrentCycleSummary cycle = cycleSummary.get();
            BigDecimal pnl = cycle == null ? null : cycle.markPricePnl();
            String pnlText = pnl == null ? "暂不可用"
                    : (pnl.signum() > 0 ? "+" : "") + pnl.setScale(2, RoundingMode.HALF_UP).toPlainString()
                            + " " + quoteCoin;
            String countText = cycle == null ? "暂不可用"
                    : "买入 " + cycle.buyCount() + " 次，卖出 " + cycle.sellCount() + " 次";
            sender.accept("再平衡大额成交\n交易对：" + fill.symbol()
                    + "\n方向：" + fill.side()
                    + "\n订单累计成交：" + total.toPlainString() + " " + FillEvent.baseCoin(fill.symbol())
                    + "\n通知阈值（单格 × 3）：" + threshold.toPlainString()
                    + "\n本笔成交价：" + fill.fillPrice()
                    + "\n当前 Cycle 市场价盈亏：" + pnlText
                    + "\n当前 Cycle 成交次数：" + countText
                    + "\n成交时间（北京时间）：" + (fill.fillTime() == null ? "未知" : LOCAL_TIME.format(fill.fillTime())));
        }
    }
}
