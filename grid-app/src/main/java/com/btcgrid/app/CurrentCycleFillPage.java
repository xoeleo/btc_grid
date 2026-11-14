package com.btcgrid.app;

import java.util.List;

public record CurrentCycleFillPage(
        List<CurrentCycleFillItem> items,
        int limit,
        int offset,
        boolean hasMore,
        long total,
        long tradingCount,
        long fundingCount
) {
    public static CurrentCycleFillPage empty(int limit, int offset) {
        return new CurrentCycleFillPage(List.of(), limit, offset, false, 0, 0, 0);
    }
}
