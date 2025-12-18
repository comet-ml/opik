package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.Filter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.SPAN_USER_DEFINED_METRIC_PYTHON)
})
@Schema(name = "AutomationRuleEvaluatorUpdate", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.SPAN_USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython.class)
})
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract sealed class AutomationRuleEvaluatorUpdate<T, E extends Filter> implements AutomationRuleUpdate
        permits AutomationRuleEvaluatorUpdateLlmAsJudge, AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge,
        AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython,
        AutomationRuleEvaluatorUpdateUserDefinedMetricPython,
        AutomationRuleEvaluatorUpdateSpanLlmAsJudge, AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython {

    @NotBlank private final String name;

    private final float samplingRate;

    @Builder.Default
    private final boolean enabled = true;

    @JsonIgnore
    @Builder.Default
    private final List<E> filters = List.of();

    @JsonIgnore
    @NotNull private final T code;

    // Dual-field backwards compatible architecture:
    // - project_id: Legacy single project field (nullable for backwards compatibility)
    // - project_ids: New multi-project field (required for new rules)
    // Service layer keeps both fields in sync for seamless migration

    @Schema(description = "Primary project ID (legacy field, maintained for backwards compatibility)")
    private final UUID projectId;

    @Schema(description = "Multiple project IDs (new field for multi-project support)")
    private final Set<UUID> projectIds;

    public abstract AutomationRuleEvaluatorType getType();

    public abstract <C extends AutomationRuleEvaluatorUpdate<T, E>, B extends AutomationRuleEvaluatorUpdate.AutomationRuleEvaluatorUpdateBuilder<T, E, C, B>> AutomationRuleEvaluatorUpdate.AutomationRuleEvaluatorUpdateBuilder<T, E, C, B> toBuilder();

    @Override
    @NotNull public AutomationRule.AutomationRuleAction getAction() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }

}
