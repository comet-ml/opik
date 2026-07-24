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
record EvaluationContext(
        UUID traceId,
        String evaluatedIdKey,
        String evaluatedId,
        UUID evaluatedProjectId,
        String projectName,
        String evaluatedName,
        JsonNode evaluatedInput,
        JsonNode evaluatedOutput,
        UUID ruleId,
        String ruleName,
        String modelName,
        String actualModel,
        String provider,
        Instant startTime,
        ObservabilityContext observabilityContext) {
}
