package com.btcgrid.backtest.engine;

import com.btcgrid.common.model.FillEvent;
import com.btcgrid.common.model.ManagedOrder;
import com.btcgrid.common.model.MarketSnapshot;
import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.TradeSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryExecutionSimulator implements ExecutionSimulator {

    private final Map<String, ManagedOrder> workingOrders = new LinkedHashMap<>();
    private final Map<String, BigDecimal> remainingQty = new LinkedHashMap<>();
    private final BacktestExecutionConfig executionConfig;

    public InMemoryExecutionSimulator() {
        this(BacktestExecutionConfig.defaults());
    }

    public InMemoryExecutionSimulator(BacktestExecutionConfig executionConfig) {
        this.executionConfig = executionConfig;
    }

    public void apply(OrderIntent orderIntent, Instant createdAt) {
        workingOrders.put(orderIntent.clientOrderId(), new ManagedOrder(orderIntent, createdAt, OrderStatus.NEW));
        remainingQty.put(orderIntent.clientOrderId(), orderIntent.qty());
    }

    public void cancel(String clientOrderId) {
        ManagedOrder order = workingOrders.get(clientOrderId);
        if (order != null) {
            workingOrders.put(clientOrderId, order.withStatus(OrderStatus.CANCELED));
            remainingQty.put(clientOrderId, BigDecimal.ZERO);
        }
    }

    public List<OrderIntent> openOrders() {
        return workingOrders.values().stream()
                .filter(order -> order.status() == OrderStatus.NEW)
                .map(order -> remainingIntent(order.intent()))
                .toList();
    }

    @Override
    public List<FillEvent> simulate(BacktestBar bar, List<OrderIntent> ignored) {
        List<FillEvent> fills = new ArrayList<>();
        BigDecimal remainingVolumeCapacity = bar.candle().volume()
                .multiply(executionConfig.maxVolumeParticipationRate())
                .max(BigDecimal.ZERO);
        for (ManagedOrder managedOrder : new ArrayList<>(workingOrders.values())) {
            if (managedOrder.status() != OrderStatus.NEW) {
                continue;
            }
            OrderIntent intent = managedOrder.intent();
            if (intent.isMarket() || isFillable(intent, bar)) {
                BigDecimal fillQty = nextFillQty(intent, bar, remainingVolumeCapacity);
                if (fillQty.signum() <= 0) {
                    continue;
                }
                BigDecimal fillPrice = simulatedFillPrice(intent, bar.snapshot());
                fills.add(new FillEvent(
                        intent.clientOrderId() + "-" + bar.snapshot().timestamp().toEpochMilli(),
                        intent.symbol(),
                        intent.clientOrderId(),
                        intent.side(),
                        fillQty,
                        fillPrice,
                        bar.snapshot().timestamp()
                ));
                remainingVolumeCapacity = remainingVolumeCapacity.subtract(fillQty).max(BigDecimal.ZERO);
                BigDecimal nextRemaining = remaining(intent.clientOrderId()).subtract(fillQty);
                if (nextRemaining.compareTo(executionConfig.minFillQty()) <= 0) {
                    workingOrders.put(intent.clientOrderId(), managedOrder.withStatus(OrderStatus.FILLED));
                    remainingQty.put(intent.clientOrderId(), BigDecimal.ZERO);
                } else {
                    remainingQty.put(intent.clientOrderId(), nextRemaining);
                }
            }
        }
        return fills;
    }

    public BigDecimal feeFor(FillEvent fillEvent) {
        ManagedOrder managedOrder = workingOrders.get(fillEvent.clientOrderId());
        if (managedOrder == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal feeRate = managedOrder.intent().isMarket()
                ? executionConfig.takerFeeRate()
                : executionConfig.makerFeeRate();
        return fillEvent.fillPrice()
                .multiply(fillEvent.fillQty())
                .multiply(feeRate)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal simulatedFillPrice(OrderIntent intent, MarketSnapshot marketSnapshot) {
        if (!intent.isMarket()) {
            return intent.price();
        }
        BigDecimal slippageRate = executionConfig.marketSlippageBps()
                .divide(new BigDecimal("10000"), 8, RoundingMode.HALF_UP);
        BigDecimal multiplier = intent.side() == TradeSide.BUY
                ? BigDecimal.ONE.add(slippageRate)
                : BigDecimal.ONE.subtract(slippageRate);
        return marketSnapshot.lastPrice().multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal nextFillQty(OrderIntent intent, BacktestBar bar, BigDecimal remainingVolumeCapacity) {
        BigDecimal remaining = remaining(intent.clientOrderId());
        BigDecimal ratio = intent.isMarket()
                ? executionConfig.marketFillRatioPerTick()
                : executionConfig.limitFillRatioPerTick();
        BigDecimal touchRatio = intent.isMarket()
                ? BigDecimal.ONE
                : touchFillRatio(intent, bar);
        BigDecimal effectiveRatio = ratio.multiply(touchRatio);
        if (effectiveRatio.compareTo(BigDecimal.ONE) > 0) {
            effectiveRatio = BigDecimal.ONE;
        }
        BigDecimal candidate = intent.qty().multiply(effectiveRatio).setScale(8, RoundingMode.DOWN);
        if (candidate.compareTo(executionConfig.minFillQty()) < 0) {
            candidate = executionConfig.minFillQty();
        }
        return candidate.min(remaining).min(remainingVolumeCapacity.max(BigDecimal.ZERO));
    }

    private OrderIntent remainingIntent(OrderIntent original) {
        BigDecimal remaining = remaining(original.clientOrderId());
        return new OrderIntent(
                original.symbol(),
                original.clientOrderId(),
                original.side(),
                original.type(),
                remaining,
                original.price(),
                original.reduceOnly(),
                original.reason()
        );
    }

    private BigDecimal remaining(String clientOrderId) {
        return remainingQty.getOrDefault(clientOrderId, BigDecimal.ZERO);
    }

    private BigDecimal touchFillRatio(OrderIntent intent, BacktestBar bar) {
        BigDecimal range = bar.candle().high().subtract(bar.candle().low()).abs();
        BigDecimal penetration;
        boolean closeThrough;
        if (intent.side() == TradeSide.BUY) {
            penetration = intent.price().subtract(bar.candle().low()).max(BigDecimal.ZERO);
            closeThrough = bar.candle().close().compareTo(intent.price()) <= 0;
        } else {
            penetration = bar.candle().high().subtract(intent.price()).max(BigDecimal.ZERO);
            closeThrough = bar.candle().close().compareTo(intent.price()) >= 0;
        }
        if (range.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal normalized = penetration.divide(range, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        BigDecimal touchRatio = executionConfig.minTouchFillRatio().add(
                BigDecimal.ONE.subtract(executionConfig.minTouchFillRatio()).multiply(normalized)
        );
        if (closeThrough) {
            touchRatio = touchRatio.max(new BigDecimal("0.75"));
        }
        return touchRatio.min(BigDecimal.ONE);
    }

    private boolean isFillable(OrderIntent intent, BacktestBar bar) {
        if (intent.type() == null || intent.price() == null) {
            return false;
        }
        return (intent.side() == TradeSide.BUY && bar.candle().low().compareTo(intent.price()) <= 0)
                || (intent.side() == TradeSide.SELL && bar.candle().high().compareTo(intent.price()) >= 0);
    }
}
