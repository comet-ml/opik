package com.comet.opik.domain.evaluation;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * The entity an online evaluation is assessing — a trace, a span, or a thread. Lets the recorder be
 * created the same way from all three scorers; the {@link Kind} drives the evaluated-id key on the
 * monitoring trace (e.g. {@code evaluated_span_id}).
 */
@Builder(toBuilder = true)
public record EvaluatedSubject(@NonNull Kind kind, @NonNull String id, UUID projectId, String projectName, String name,
        JsonNode input, JsonNode output) {

    @Getter
    @RequiredArgsConstructor
    public enum Kind {
        TRACE("evaluated_trace_id"),
        SPAN("evaluated_span_id"),
        THREAD("evaluated_thread_id");

        private final String idKey;
    }

    public static EvaluatedSubject ofTrace(@NonNull Trace trace) {
        return EvaluatedSubject.builder()
                .kind(Kind.TRACE)
                .id(trace.id().toString())
                .projectId(trace.projectId())
                .projectName(trace.projectName())
                .name(trace.name())
                .input(trace.input())
                .output(trace.output())
                .build();
    }

    public static EvaluatedSubject ofSpan(@NonNull Span span) {
        return EvaluatedSubject.builder()
                .kind(Kind.SPAN)
                .id(span.id().toString())
                .projectId(span.projectId())
                .projectName(span.projectName())
                .name(span.name())
                .input(span.input())
                .output(span.output())
                .build();
    }

    public static EvaluatedSubject ofThread(@NonNull String threadId, UUID projectId, String projectName) {
        // Threads have no single input/output; the prepare span carries the thread id only.
        return EvaluatedSubject.builder()
                .kind(Kind.THREAD)
                .id(threadId)
                .projectId(projectId)
                .projectName(projectName)
                .build();
    }
}
