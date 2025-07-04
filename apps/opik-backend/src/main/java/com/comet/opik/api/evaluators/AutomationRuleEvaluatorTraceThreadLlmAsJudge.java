package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;

@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorTraceThreadLlmAsJudge
        extends
            AutomationRuleEvaluator<TraceThreadLlmAsJudgeCode> {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceThreadLlmAsJudgeCode(
            @JsonView( {
                    View.Public.class, View.Write.class}) @NotNull LlmAsJudgeModelParameters model,
            @JsonView({View.Public.class, View.Write.class}) @NotNull List<LlmAsJudgeMessage> messages,
            @JsonView({View.Public.class, View.Write.class}) @NotNull List<LlmAsJudgeOutputSchema> schema){

        public static final String CONTEXT_VARIABLE_NAME = "context";

    }

    @ConstructorProperties({"id", "projectId", "projectName", "name", "samplingRate", "code", "createdAt", "createdBy",
            "lastUpdatedAt", "lastUpdatedBy"})
    public AutomationRuleEvaluatorTraceThreadLlmAsJudge(UUID id, @NotNull UUID projectId, String projectName,
            @NotBlank String name,
            float samplingRate, @NotNull TraceThreadLlmAsJudgeCode code, Instant createdAt, String createdBy,
            Instant lastUpdatedAt, String lastUpdatedBy) {
        super(id, projectId, projectName, name, samplingRate, code, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
    }

    @JsonView({View.Public.class, View.Write.class})
    @Override
    public TraceThreadLlmAsJudgeCode getCode() {
        return super.getCode();
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;
    }
}
