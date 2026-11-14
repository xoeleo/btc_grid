package com.btcgrid.app;

import java.math.BigDecimal;

public record CurrentCycleFillItem(
        FillRecord fill,
        BigDecimal cumulativeTurnover,
        BigDecimal previousTradingPrice
) {
}
