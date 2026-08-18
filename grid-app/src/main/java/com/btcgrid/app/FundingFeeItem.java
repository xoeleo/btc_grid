package com.btcgrid.app;

import java.math.BigDecimal;

public record FundingFeeItem(
        FillRecord fill,
        BigDecimal cumulativeFundingFee
) {
}
