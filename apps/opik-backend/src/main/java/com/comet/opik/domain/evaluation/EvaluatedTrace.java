package com.comet.opik.domain.evaluation;

import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/** An evaluated trace. */
@Builder
public record EvaluatedTrace(@NonNull String id, UUID projectId, String projectName, String name,
        JsonNode input, JsonNode output) implements EvaluatedSubject {

    public static EvaluatedTrace from(@NonNull Trace trace) {
        return EvaluatedTrace.builder()
                .id(trace.id().toString())
                .projectId(trace.projectId())
                .projectName(trace.projectName())
                .name(trace.name())
                .input(trace.input())
                .output(trace.output())
                .build();
    }

    @Override
    public String evaluatedIdKey() {
        return "evaluated_trace_id";
    }
}
