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
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorLlmAsJudge extends AutomationRuleEvaluator<LlmAsJudgeCode> {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LlmAsJudgeCode(
            @JsonView( {
                    View.Public.class, View.Write.class}) @NotNull LlmAsJudgeModelParameters model,
            @JsonView({View.Public.class, View.Write.class}) @NotNull List<LlmAsJudgeMessage> messages,
            @JsonView({View.Public.class, View.Write.class}) @NotNull Map<String, String> variables,
            @JsonView({View.Public.class, View.Write.class}) @NotNull List<LlmAsJudgeOutputSchema> schema){
    }

    @ConstructorProperties({"id", "projectId", "projectName", "name", "samplingRate", "code", "createdAt", "createdBy",
            "lastUpdatedAt", "lastUpdatedBy"})
    public AutomationRuleEvaluatorLlmAsJudge(UUID id, @NotNull UUID projectId, String projectName,
            @NotBlank String name,
            float samplingRate,
            @NotNull LlmAsJudgeCode code, Instant createdAt, String createdBy, Instant lastUpdatedAt,
            String lastUpdatedBy) {
        super(id, projectId, projectName, name, samplingRate, code, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
    }

    /**
     * Two purposes:
     * - Makes the polymorphic T code available for serialization.
     * - Provides the specific type T for Open API and Fern.
     */
    @JsonView({View.Public.class, View.Write.class})
    @Override
    public LlmAsJudgeCode getCode() {
        return super.getCode();
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
    }
}
