package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.Map;
import java.util.Optional;

/** Response of {@code POST /v1/decide}. {@code result.answers} is keyed like the request's {@code questions}. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecideResponse(String requestId, Boolean cacheHit, Long latencyMs, Result result) {

    @JsonIgnore
    public Map<String, Answer> answers() {
        return Optional.ofNullable(result).map(Result::answers).orElse(Map.of());
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(Map<String, Answer> answers) {
    }

    /**
     * A {@code noul} answer carries the probability of yes; a {@code choice} answer carries the picked option and a
     * probability per option.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Answer(String type, Double noul, String choice, Map<String, Double> probabilities,
            Double confidence) {
    }
}
