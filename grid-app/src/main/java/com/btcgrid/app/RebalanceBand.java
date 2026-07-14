package com.btcgrid.app;

import java.math.BigDecimal;

public record RebalanceBand(BigDecimal priceFloor, BigDecimal targetRatio) {
}
