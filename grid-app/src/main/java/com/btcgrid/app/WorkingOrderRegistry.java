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
                now
        ));
    }

    public void trackRemote(WorkingOrder workingOrder) {
        ordersByClientId.put(workingOrder.clientOrderId(), workingOrder);
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

    public void markExpired(String clientOrderId, Instant now) {
        ordersByClientId.computeIfPresent(clientOrderId, (key, existing) ->
                existing.withStatus(OrderStatus.EXPIRED, now, existing.exchangeOrderId()));
    }

    public void applyUpdate(OrderUpdateEvent event) {
        ordersByClientId.compute(event.clientOrderId(), (key, existing) -> {
            if (existing == null) {
                return new WorkingOrder(
                        event.symbol(),
                        event.clientOrderId(),
                        event.exchangeOrderId(),
                        event.side(),
                        null,
                        event.price(),
                        event.qty(),
                        false,
                        event.status(),
                        event.updatedAt(),
                        event.updatedAt()
                );
            }
            return existing.withStatus(event.status(), event.updatedAt(), event.exchangeOrderId());
        });
    }

    public Collection<WorkingOrder> snapshot() {
        return ordersByClientId.values();
    }
}
