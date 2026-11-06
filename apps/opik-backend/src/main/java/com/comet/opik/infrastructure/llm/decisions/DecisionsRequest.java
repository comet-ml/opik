package com.comet.opik.infrastructure.llm.decisions;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/**
 * A Decisions API call: the text the model reads ({@code state}) and the questions to answer about it, keyed by a
 * caller-chosen name that the response echoes back. It is OpenRouter's wire format; other {@link DecisionsClient}s map
 * it to their provider's.
 */
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecisionsRequest(@NonNull String model, @NonNull String state,
        @NonNull Map<String, DecisionsQuestion> questions) {
}
