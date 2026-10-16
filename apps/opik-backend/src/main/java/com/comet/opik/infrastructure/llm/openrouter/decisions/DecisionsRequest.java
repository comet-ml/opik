package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/**
 * Body of an OpenRouter Decisions API call: the text the model reads ({@code state}) and the questions to
 * answer about it, keyed by a caller-chosen name that the response echoes back.
 */
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecisionsRequest(@NonNull String model, @NonNull String state,
        @NonNull Map<String, DecisionsQuestion> questions) {
}
