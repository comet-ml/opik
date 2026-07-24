package com.comet.opik.domain.evaluation;

import com.comet.opik.api.Span;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/** An evaluated span. */
@Builder
public record EvaluatedSpan(@NonNull String id, UUID projectId, String projectName, String name,
        JsonNode input, JsonNode output) implements EvaluatedSubject {

    public static EvaluatedSpan from(@NonNull Span span) {
        return EvaluatedSpan.builder()
                .id(span.id().toString())
                .projectId(span.projectId())
                .projectName(span.projectName())
                .name(span.name())
                .input(span.input())
                .output(span.output())
                .build();
    }

    @Override
    public String evaluatedIdKey() {
        return "evaluated_span_id";
    }
}
