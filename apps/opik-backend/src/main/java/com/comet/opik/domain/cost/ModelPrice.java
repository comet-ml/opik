package com.comet.opik.domain.cost;

import lombok.NonNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;

public record ModelPrice(
        @NonNull BigDecimal inputTextPrice,
        @NonNull BigDecimal inputImagePrice,
        @NonNull BigDecimal inputAudioPrice,
        @NonNull BigDecimal inputVideoPrice,
        @NonNull BigDecimal outputPrice,
        @NonNull BigDecimal cacheCreationInputTokenPrice,
        @NonNull BigDecimal cacheReadInputTokenPrice,
        @NonNull BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator) {
}
