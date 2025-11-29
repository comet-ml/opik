package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.SpanFilter;
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
import java.util.UUID;

@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE),
})
@Schema(name = "AutomationRuleEvaluatorUpdateSpan", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class),
})
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract sealed class AutomationRuleEvaluatorUpdateSpan<T>
        permits AutomationRuleEvaluatorUpdateSpanLlmAsJudge {

    @NotBlank private final String name;

    private final float samplingRate;

    @Builder.Default
    private final boolean enabled = true;

    @Builder.Default
    private final List<SpanFilter> filters = List.of();

    @JsonIgnore
    @NotNull private final T code;

    @NotNull private final UUID projectId;

    public abstract AutomationRuleEvaluatorType getType();

    public abstract <C extends AutomationRuleEvaluatorUpdateSpan<T>, B extends AutomationRuleEvaluatorUpdateSpan.AutomationRuleEvaluatorUpdateSpanBuilder<T, C, B>> AutomationRuleEvaluatorUpdateSpan.AutomationRuleEvaluatorUpdateSpanBuilder<T, C, B> toBuilder();

    @NotNull public AutomationRule.AutomationRuleAction getAction() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }
}
