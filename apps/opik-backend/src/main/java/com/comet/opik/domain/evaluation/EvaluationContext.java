package com.comet.opik.domain.evaluation;

import com.comet.opik.domain.observability.ObservabilityContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-evaluation handle. Holds the parent trace id, the evaluated entity references and the resolved
 * model/provider, and accumulates the LLM-/tool-round counts. Allocated once per evaluation by
 * {@link OnlineEvaluationRecorder#begin}; mutated only by the sequential reactive chain of a single
 * evaluation.
 */
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
    private volatile boolean agentic;

    EvaluationContext(UUID traceId, EvaluatedSubject subject, UUID ruleId, String ruleName, String modelName,
            String actualModel, String provider, JsonNode evaluatedInput, JsonNode evaluatedOutput,
            String workspaceId, String userName, Instant startTime) {
        this.traceId = traceId;
        this.evaluatedIdKey = subject.kind().idKey();
        this.evaluatedId = subject.id();
        this.evaluatedProjectId = subject.projectId();
        this.projectName = subject.projectName();
        this.evaluatedName = subject.name();
        this.evaluatedInput = evaluatedInput;
        this.evaluatedOutput = evaluatedOutput;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.modelName = modelName;
        this.actualModel = actualModel;
        this.provider = provider;
        this.observabilityContext = new ObservabilityContext(workspaceId, userName);
        this.startTime = startTime;
    }

    void markAgentic() {
        this.agentic = true;
    }

    boolean agentic() {
        return agentic;
    }

    ObservabilityContext observabilityContext() {
        return observabilityContext;
    }
}
