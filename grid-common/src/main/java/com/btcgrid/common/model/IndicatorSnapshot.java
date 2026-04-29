package com.btcgrid.common.model;

import java.math.BigDecimal;
import java.time.Instant;

public record IndicatorSnapshot(
        Instant timestamp,
        BigDecimal baseline4h,
        BigDecimal bollUpper4h,
        BigDecimal bollMiddle4h,
        BigDecimal bollLower4h
) {
    public BandZone zoneOf(BigDecimal price) {
        if (price.compareTo(bollUpper4h) > 0) {
            return BandZone.ABOVE_UPPER;
        }
        if (price.compareTo(bollLower4h) < 0) {
            return BandZone.BELOW_LOWER;
        }
        return BandZone.INSIDE_BAND;
    }
}
