package com.comet.opik.api.spend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

/**
 * Cache-tier token sums for a single {@code cc.billing.model}. The FE prices
 * each entry at that model's rates and sums across models, so a workspace
 * mixing models is costed exactly instead of at one representative rate.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelTiers(
        String model,
        Long inputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Long outputTokens) {
}
