package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/** At most 32 questions, keyed by ids matching {@link Question#ID_PATTERN}. */
@Builder(toBuilder = true)
public record BatchDecideItem(@NonNull String itemId, @NonNull JsonNode state,
        @NonNull Map<String, Question> questions) implements BatchItem {

    public static final int MAX_QUESTIONS = 32;
}
