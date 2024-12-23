package com.comet.opik.api.resources.utils;

import com.comet.opik.utils.ValidationUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;
import java.util.stream.Collector;

public class BigDecimalCollectors {
    public static <T> Collector<T, ?, BigDecimal> averagingBigDecimal(Function<T, BigDecimal> mapper) {
        return Collector.of(
                () -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO},
                (result, value) -> {
                    BigDecimal mappedValue = mapper.apply(value);
                    if (mappedValue != null) {
                        result[0] = result[0].add(mappedValue);
                        result[1] = result[1].add(BigDecimal.ONE);
                    }
                },
                (result1, result2) -> {
                    result1[0] = result1[0].add(result2[0]);
                    result1[1] = result1[1].add(result2[1]);
                    return result1;
                },
                result -> result[1].compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : result[0].divide(result[1], ValidationUtils.SCALE, RoundingMode.HALF_UP));
    }
}
