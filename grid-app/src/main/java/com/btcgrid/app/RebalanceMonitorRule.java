package com.btcgrid.app;

import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.util.UUID;

/** One ordered trigger for the single rebalance Cycle executor. */
public record RebalanceMonitorRule(
        String id,
        String name,
        boolean enabled,
        TradeSide side,
        Trigger trigger,
        BigDecimal triggerPrice,
        BigDecimal initialSteps,
        BigDecimal gridQty,
        BigDecimal targetRatio
) {
    public enum Trigger { BELOW, ABOVE }

    public RebalanceMonitorRule validated() {
        String key = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("监控 ID 无效");
        if (side == null || trigger == null || triggerPrice == null || initialSteps == null
                || gridQty == null || targetRatio == null) throw new IllegalArgumentException("监控条件不能为空");
        if (triggerPrice.signum() <= 0 || initialSteps.signum() <= 0 || gridQty.signum() <= 0)
            throw new IllegalArgumentException("触发价、起始倍数和单格数量必须大于 0");
        if (targetRatio.compareTo(BigDecimal.ZERO) < 0 || targetRatio.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("目标 BTC 比例必须在 0% 到 100% 之间");
        return new RebalanceMonitorRule(key, name == null ? "" : name.trim(), enabled, side, trigger,
                triggerPrice, initialSteps, gridQty, targetRatio);
    }

    public boolean matches(BigDecimal price, BigDecimal ratio) {
        if (!enabled || price == null || ratio == null) return false;
        boolean priceMatches = trigger == Trigger.BELOW
                ? price.compareTo(triggerPrice) < 0 : price.compareTo(triggerPrice) > 0;
        boolean targetPending = side == TradeSide.BUY
                ? ratio.compareTo(targetRatio) < 0 : ratio.compareTo(targetRatio) > 0;
        return priceMatches && targetPending;
    }
}
