package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

/**
 * One typed question. Only the {@code noul} (yes/no) type is used: its answer is the probability that the
 * answer is yes.
 */
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecisionsQuestion(@NonNull String type, @NonNull String instructions) {

    public static final String NOUL_TYPE = "noul";

    public static DecisionsQuestion noul(@NonNull String instructions) {
        return DecisionsQuestion.builder()
                .type(NOUL_TYPE)
                .instructions(instructions)
                .build();
    }
}
