package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE)
})
@Schema(name = "AutomationRuleEvaluator", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE, schema = AutomationRuleEvaluatorLlmAsJudge.class)
})
@AllArgsConstructor
public abstract sealed class AutomationRuleEvaluator<T> implements AutomationRule<T>
        permits AutomationRuleEvaluatorLlmAsJudge {

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private UUID id;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private UUID projectId;

    @JsonView({View.Public.class, View.Write.class})
    @NotBlank
    private String name;

    @JsonView({View.Public.class, View.Write.class})
    private Float samplingRate;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Instant createdAt;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private String createdBy;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Instant lastUpdatedAt;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private String lastUpdatedBy;

    @NotNull @JsonView({View.Public.class, View.Write.class})
    public abstract AutomationRuleEvaluatorType getType();

    @JsonIgnore
    public abstract T getCode();

    @Override
    public AutomationRuleAction getAction() {
        return AutomationRuleAction.EVALUATOR;
    }

    public static class View {
        public static class Write {
        }
        public static class Public {
        }
    }

    @Builder(toBuilder = true)
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
