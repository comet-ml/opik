package com.comet.opik.api;

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
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON)
})
@Schema(name = "AutomationRuleEvaluatorUpdate", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class)
})
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract sealed class AutomationRuleEvaluatorUpdate<T> implements AutomationRuleUpdate
        permits AutomationRuleEvaluatorUpdateLlmAsJudge, AutomationRuleEvaluatorUpdateUserDefinedMetricPython {

    @NotBlank private final String name;

    private final float samplingRate;

    @NotNull private final T code;

    // TODO: add @NotNull after deprecated endpoint is removed
    private final UUID projectId;

    public abstract AutomationRuleEvaluatorType getType();

    public abstract <C extends AutomationRuleEvaluatorUpdate<T>, B extends AutomationRuleEvaluatorUpdate.AutomationRuleEvaluatorUpdateBuilder<T, C, B>> AutomationRuleEvaluatorUpdate.AutomationRuleEvaluatorUpdateBuilder<T, C, B> toBuilder();

    @Override
    public AutomationRule.AutomationRuleAction getAction() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }
}
