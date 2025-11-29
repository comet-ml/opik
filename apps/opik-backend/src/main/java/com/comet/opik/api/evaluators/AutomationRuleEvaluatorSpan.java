package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.SpanFilter;
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
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorSpanLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE),
})
@Schema(name = "AutomationRuleEvaluatorSpan", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorSpanLlmAsJudge.class),
})
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract sealed class AutomationRuleEvaluatorSpan<T>
        permits AutomationRuleEvaluatorSpanLlmAsJudge {

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

    @JsonView({View.Public.class, View.Write.class})
    @Builder.Default
    private final List<SpanFilter> filters = List.of();

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

    @NotNull public AutomationRule.AutomationRuleAction getAction() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }

    public abstract <C extends AutomationRuleEvaluatorSpan<T>, B extends AutomationRuleEvaluatorSpanBuilder<T, C, B>> AutomationRuleEvaluatorSpanBuilder<T, C, B> toBuilder();

    @UtilityClass
    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
