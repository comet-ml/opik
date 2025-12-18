package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.TraceFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorUpdateLlmAsJudge
        extends
            AutomationRuleEvaluatorUpdate<LlmAsJudgeCode, TraceFilter> {

    @ConstructorProperties({"name", "samplingRate", "enabled", "filters", "code", "projectId", "projectIds"})
    public AutomationRuleEvaluatorUpdateLlmAsJudge(
            @NotBlank String name, float samplingRate, boolean enabled, List<TraceFilter> filters,
            @NotNull LlmAsJudgeCode code,
            UUID projectId,
            Set<UUID> projectIds) {
        super(name, samplingRate, enabled, filters, code, projectId, projectIds);
    }

    /**
     * Two purposes:
     * - Makes the polymorphic T code available for serialization.
     * - Provides the specific type T for Open API and Fern.
     */
    @JsonProperty
    @Override
    public List<TraceFilter> getFilters() {
        return super.getFilters();
    }

    /**
     * Two purposes:
     * - Makes the polymorphic T code available for serialization.
     * - Provides the specific type T for Open API and Fern.
     */
    @JsonProperty
    @Override
    public LlmAsJudgeCode getCode() {
        return super.getCode();
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
    }
}
