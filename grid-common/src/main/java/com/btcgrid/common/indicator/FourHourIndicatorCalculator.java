package com.btcgrid.common.indicator;

import com.btcgrid.common.model.Candle;
import com.btcgrid.common.model.IndicatorSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public class FourHourIndicatorCalculator implements IndicatorCalculator {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    @Override
    public Optional<IndicatorSnapshot> calculate(
            String symbol,
            List<Candle> candles,
            int emaLength,
            int bollLength,
            BigDecimal bollStdDevMultiplier
    ) {
        int required = bollLength;
        if (candles.size() < required) {
            return Optional.empty();
        }

        List<Candle> bollWindow = candles.subList(candles.size() - bollLength, candles.size());
        BigDecimal mean = bollWindow.stream()
                .map(Candle::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(bollLength), 8, RoundingMode.HALF_UP);
        BigDecimal variance = bollWindow.stream()
                .map(candle -> candle.close().subtract(mean))
                .map(delta -> delta.multiply(delta, MATH_CONTEXT))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(bollLength), 8, RoundingMode.HALF_UP);
        BigDecimal stdDev = sqrt(variance);
        BigDecimal offset = stdDev.multiply(bollStdDevMultiplier, MATH_CONTEXT);

        Candle last = candles.get(candles.size() - 1);
        return Optional.of(new IndicatorSnapshot(
                last.closeTime(),
                mean,
                mean.add(offset, MATH_CONTEXT),
                mean,
                mean.subtract(offset, MATH_CONTEXT)
        ));
    }

    private BigDecimal sqrt(BigDecimal value) {
        double sqrt = Math.sqrt(value.doubleValue());
        return BigDecimal.valueOf(sqrt);
    }
}
