package com.btcgrid.app;

import com.btcgrid.common.model.WorkingOrder;
import java.util.List;
import java.util.Set;

public record RecoveryState(
        Set<String> processedExecutionIds,
        List<WorkingOrder> trackedWorkingOrders
) {
}
