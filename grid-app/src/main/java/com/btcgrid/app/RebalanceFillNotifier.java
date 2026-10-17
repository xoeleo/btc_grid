package com.btcgrid.app;

import com.btcgrid.common.model.FillEvent;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Aggregates actual executions, never submitted order quantities. */
final class RebalanceFillNotifier {
    private final Map<String, BigDecimal> quantities = new HashMap<>();
    private final Map<String, BigDecimal> thresholds = new HashMap<>();
    private final Set<String> executions = new HashSet<>();
    private final Set<String> notified = new HashSet<>();
    private final Consumer<String> sender;

    RebalanceFillNotifier(Consumer<String> sender) {
        this.sender = sender;
    }

    synchronized void accept(FillEvent fill, BigDecimal baseQty, boolean historical) {
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
            sender.accept("再平衡大额成交\n交易对：" + fill.symbol()
                    + "\n方向：" + fill.side()
                    + "\n订单累计成交：" + total.toPlainString() + " " + FillEvent.baseCoin(fill.symbol())
                    + "\n通知阈值（单格 × 3）：" + threshold.toPlainString()
                    + "\n本笔成交价：" + fill.fillPrice()
                    + "\n订单：" + order
                    + "\n成交时间：" + fill.fillTime());
        }
    }
}
