package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Response of an OpenRouter Decisions API call. {@code model} is the dated slug that answered (e.g.
 * {@code typesafe/jev-1.13-20260917}), also when the request used an alias. {@code answers} is keyed like the
 * request's {@code questions}.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecisionsResponse(String id, String model, String provider, Map<String, Answer> answers,
        Usage usage) {

    /** Only {@code noul} answers are read; other types carry fields ignored here. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Answer(String type, Double noul) {
    }

    /** {@code cost} is in USD, reported by OpenRouter. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(Integer inputTokens, Integer outputTokens, BigDecimal cost) {
    }
}
