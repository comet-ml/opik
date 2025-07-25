package com.comet.opik.api.evaluators;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
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
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorTraceThreadLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON),
})
@Schema(name = "AutomationRuleEvaluator", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE, schema = AutomationRuleEvaluatorLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUserDefinedMetricPython.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorTraceThreadLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class),
})
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract sealed class AutomationRuleEvaluator<T> implements AutomationRule
        permits AutomationRuleEvaluatorLlmAsJudge, AutomationRuleEvaluatorUserDefinedMetricPython,
        AutomationRuleEvaluatorTraceThreadLlmAsJudge, AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython {

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final UUID id;

    @JsonView({View.Public.class, View.Write.class})
    @NotNull private final UUID projectId;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final String projectName;

    @JsonView({View.Public.class, View.Write.class})
    @NotBlank private final String name;

    @JsonView({View.Public.class, View.Write.class})
    private final float samplingRate;

    @JsonView({View.Public.class, View.Write.class})
    @Builder.Default
    private final boolean enabled = true;

    @JsonIgnore
    @NotNull private final T code;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final Instant createdAt;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final String createdBy;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final Instant lastUpdatedAt;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final String lastUpdatedBy;

    @NotNull @JsonView({View.Public.class, View.Write.class})
    public abstract AutomationRuleEvaluatorType getType();

    @Override
    @NotNull public AutomationRuleAction getAction() {
        return AutomationRuleAction.EVALUATOR;
    }

    public abstract <C extends AutomationRuleEvaluator<T>, B extends AutomationRuleEvaluatorBuilder<T, C, B>> AutomationRuleEvaluatorBuilder<T, C, B> toBuilder();

    @UtilityClass
    public static class View {
        public static class Write {
        }
        public static class Public {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AutomationRuleEvaluatorPage(
            @JsonView( {
                    View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total,
            @JsonView({View.Public.class}) List<AutomationRuleEvaluator<?>> content)
            implements
                Page<AutomationRuleEvaluator<?>>{

        public static AutomationRuleEvaluatorPage empty(int page) {
            return new AutomationRuleEvaluatorPage(page, 0, 0, List.of());
        }
    }
}
