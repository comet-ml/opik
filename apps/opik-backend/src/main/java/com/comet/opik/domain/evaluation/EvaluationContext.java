package com.comet.opik.domain.evaluation;

import com.comet.opik.domain.observability.ObservabilityContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable per-evaluation handle. Holds the parent trace id, the evaluated entity references and the
 * resolved model/provider. Built once per evaluation by {@link OnlineEvaluationRecorder#begin} via the
 * Lombok builder, so no construction logic lives in this type.
 */
@Builder
final class EvaluationContext {

    final UUID traceId;
    final String evaluatedIdKey;
    final String evaluatedId;
    final UUID evaluatedProjectId;
    final String projectName;
    final String evaluatedName;
    final JsonNode evaluatedInput;
    final JsonNode evaluatedOutput;
    final UUID ruleId;
    final String ruleName;
    final String modelName;
    final String actualModel;
    final String provider;
    final Instant startTime;
    private final ObservabilityContext observabilityContext;

    ObservabilityContext observabilityContext() {
        return observabilityContext;
    }
}
