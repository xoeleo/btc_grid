package com.btcgrid.app;

import com.btcgrid.common.model.OrderIntent;
import com.btcgrid.common.model.OrderStatus;
import com.btcgrid.common.model.OrderUpdateEvent;
import com.btcgrid.common.model.WorkingOrder;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkingOrderRegistry {

    private final Map<String, WorkingOrder> ordersByClientId = new ConcurrentHashMap<>();

    public void trackSubmitted(OrderIntent intent, Instant now) {
        ordersByClientId.put(intent.clientOrderId(), new WorkingOrder(
                intent.symbol(),
                intent.clientOrderId(),
                null,
                intent.side(),
                intent.type(),
                intent.price(),
                intent.qty(),
                intent.reduceOnly(),
                OrderStatus.NEW,
                now,
                now,
                intent.reason()
        ));
    }

    public void trackRemote(WorkingOrder workingOrder) {
        ordersByClientId.merge(workingOrder.clientOrderId(), workingOrder, this::mergeEvidence);
    }

    public void replaceAll(Collection<WorkingOrder> workingOrders) {
        ordersByClientId.clear();
        if (workingOrders == null) {
            return;
        }
        for (WorkingOrder order : workingOrders) {
            ordersByClientId.put(order.clientOrderId(), order);
        }
    }

    public void markCanceled(String clientOrderId, Instant now) {
        ordersByClientId.computeIfPresent(clientOrderId, (key, existing) ->
                existing.withStatus(OrderStatus.CANCELED, now, existing.exchangeOrderId()));
    }

    public void markAccepted(String clientOrderId, String exchangeOrderId, Instant now) {
        ordersByClientId.computeIfPresent(clientOrderId, (key, existing) ->
                existing.withStatus(existing.status(), now, exchangeOrderId));
    }

    public void markExpired(String clientOrderId, Instant now) {
        ordersByClientId.computeIfPresent(clientOrderId, (key, existing) ->
                existing.withStatus(OrderStatus.EXPIRED, now, existing.exchangeOrderId()));
    }

    public void markFilled(String clientOrderId, Instant now) {
        ordersByClientId.computeIfPresent(clientOrderId, (key, existing) ->
                existing.withStatus(OrderStatus.FILLED, now, existing.exchangeOrderId()));
    }

    public void markCancelPending(String clientOrderId, Instant now) {
        ordersByClientId.computeIfPresent(clientOrderId, (key, order) -> new WorkingOrder(
                order.symbol(), order.clientOrderId(), order.exchangeOrderId(), order.side(), order.orderType(),
                order.price(), order.qty(), order.reduceOnly(), order.status(), order.createdAt(), now,
                order.reason(), order.cumulativeExecutedQty(), order.status() == OrderStatus.NEW));
    }

    public boolean hasPendingCancellation() {
        return ordersByClientId.values().stream().anyMatch(o -> o.status() == OrderStatus.NEW && o.cancellationRequested());
    }

    private WorkingOrder mergeEvidence(WorkingOrder existing, WorkingOrder incoming) {
        OrderStatus status = existing.status() != OrderStatus.NEW && incoming.status() == OrderStatus.NEW
                ? existing.status() : incoming.status();
        java.math.BigDecimal cumulative = incoming.cumulativeExecutedQty();
        if (existing.cumulativeExecutedQty() != null) cumulative = cumulative == null
                ? existing.cumulativeExecutedQty() : cumulative.max(existing.cumulativeExecutedQty());
        return new WorkingOrder(incoming.symbol(), incoming.clientOrderId(),
                incoming.exchangeOrderId() == null ? existing.exchangeOrderId() : incoming.exchangeOrderId(),
                incoming.side() == null ? existing.side() : incoming.side(),
                incoming.orderType() == null ? existing.orderType() : incoming.orderType(),
                incoming.price() == null ? existing.price() : incoming.price(),
                incoming.qty() == null ? existing.qty() : incoming.qty(), incoming.reduceOnly(), status,
                existing.createdAt() == null ? incoming.createdAt() : existing.createdAt(), incoming.updatedAt(), existing.reason(), cumulative,
                status == OrderStatus.NEW && (existing.cancellationRequested() || incoming.cancellationRequested()));
    }

    public void applyUpdate(OrderUpdateEvent event) {
        ordersByClientId.compute(event.clientOrderId(), (key, existing) -> {
            WorkingOrder incoming = new WorkingOrder(event.symbol(), event.clientOrderId(), event.exchangeOrderId(),
                    event.side(), null, event.price(), event.qty(), false, event.status(),
                    event.updatedAt(), event.updatedAt(), null, event.cumExecQty());
            return existing == null ? incoming : mergeEvidence(existing, incoming);
        });
    }

    public Collection<WorkingOrder> snapshot() {
        return java.util.List.copyOf(ordersByClientId.values());
    }
}
