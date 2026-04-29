package com.btcgrid.common.indicator;

import com.btcgrid.common.model.Candle;
import com.btcgrid.common.model.IndicatorSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface IndicatorCalculator {

    Optional<IndicatorSnapshot> calculate(String symbol, List<Candle> candles, int emaLength, int bollLength, BigDecimal bollStdDevMultiplier);
}
