package com.comet.opik.domain.cost;

import lombok.NonNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;

public record ModelPriceNew(
        @NonNull BigDecimal inputPrice,
        @NonNull BigDecimal outputPrice,
        @NonNull BiFunction<ModelPriceNew, Map<String, Integer>, BigDecimal> calculator) {
}
