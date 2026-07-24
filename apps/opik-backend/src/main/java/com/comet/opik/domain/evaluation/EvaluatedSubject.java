package com.comet.opik.domain.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The entity an online evaluation is assessing. A sealed hierarchy rather than a single
 * kind-discriminated record, so each kind only carries the fields it actually has — e.g.
 * {@link EvaluatedThread} has no input/output/name, which is unrepresentable here instead of
 * nullable-by-convention. {@link #evaluatedIdKey()} drives the evaluated-id key on the monitoring
 * trace/span (e.g. {@code evaluated_span_id}).
 */
public sealed interface EvaluatedSubject permits EvaluatedTrace, EvaluatedSpan, EvaluatedThread {

    String id();

    UUID projectId();

    String projectName();

    /** Metadata key under which the evaluated entity's id is recorded (e.g. {@code evaluated_trace_id}). */
    String evaluatedIdKey();

    /** Display name of the evaluated entity, or {@code null} for kinds that have none (threads). */
    default String name() {
        return null;
    }

    /** Input of the evaluated entity, or {@code null} for kinds that have none (threads). */
    default JsonNode input() {
        return null;
    }

    /** Output of the evaluated entity, or {@code null} for kinds that have none (threads). */
    default JsonNode output() {
        return null;
    }
}
