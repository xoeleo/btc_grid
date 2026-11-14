package com.btcgrid.app;

import java.util.List;

public record FundingFeePage(
        List<FundingFeeItem> items,
        int limit,
        int offset,
        boolean hasMore,
        long total
) {
    public static FundingFeePage empty(int limit, int offset) {
        return new FundingFeePage(List.of(), limit, offset, false, 0);
    }
}
