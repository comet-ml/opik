package com.comet.opik.api.resources.utils;

import com.comet.opik.utils.ValidationUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;
import java.util.stream.Collector;

public class BigDecimalCollectors {

    public static Collector<BigDecimal, ?, BigDecimal> averagingBigDecimal() {
        return Collector.of(
                // Supplier: Create an array with two elements to hold total and count
                () -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO},
                // Accumulator: Update total and count
                (result, value) -> {
                    result[0] = result[0].add(value); // Accumulate total
                    result[1] = result[1].add(BigDecimal.ONE); // Increment count
                },
                // Combiner: Merge two arrays (used for parallel streams)
                (result1, result2) -> {
                    result1[0] = result1[0].add(result2[0]); // Combine totals
                    result1[1] = result1[1].add(result2[1]); // Combine counts
                    return result1;
                },
                // Finisher: Compute the average (total / count) with rounding
                result -> result[1].compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO // Avoid division by zero
                        : result[0].divide(result[1], ValidationUtils.SCALE, RoundingMode.HALF_UP));
    }

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
