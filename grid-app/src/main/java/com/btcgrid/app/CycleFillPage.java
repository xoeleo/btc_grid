package com.btcgrid.app;

import java.math.BigDecimal;
import java.util.List;

public record CycleFillPage(
        List<FillRecord> items,
        int limit,
        int offset,
        boolean hasMore,
        long total,
        BigDecimal turnoverBefore
) {
    public static CycleFillPage empty(int limit, int offset) {
        return new CycleFillPage(List.of(), limit, offset, false, 0, BigDecimal.ZERO);
    }
}
