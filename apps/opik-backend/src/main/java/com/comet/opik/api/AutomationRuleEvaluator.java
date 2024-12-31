package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluator.AutomationRuleEvaluatorLlmAsJudge.class, names = {"llm_as_judge", "LLM_AS_JUDGE"})
})
@Schema(name = "AutomationRuleEvaluator", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = "llm_as_judge", schema = AutomationRuleEvaluator.AutomationRuleEvaluatorLlmAsJudge.class),
        @DiscriminatorMapping(value = "LLM_AS_JUDGE", schema = AutomationRuleEvaluator.AutomationRuleEvaluatorLlmAsJudge.class)
})
@AllArgsConstructor
public abstract sealed class AutomationRuleEvaluator<T> implements AutomationRule {

    @EqualsAndHashCode(callSuper = true)
    @Data
    @SuperBuilder(toBuilder = true)
    @ToString(callSuper = true)
    public static final class AutomationRuleEvaluatorLlmAsJudge extends AutomationRuleEvaluator<JsonNode> {

        @NotNull @JsonView({View.Public.class, View.Write.class})
        @Schema(accessMode = Schema.AccessMode.READ_WRITE)
        JsonNode code;

        @ConstructorProperties({"id", "projectId", "samplingRate", "code", "createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy"})
        public AutomationRuleEvaluatorLlmAsJudge(UUID id, UUID projectId, float samplingRate, @NotBlank JsonNode code,
                                           Instant createdAt, String createdBy, Instant lastUpdatedAt, String lastUpdatedBy) {
            super(id, projectId, samplingRate, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
            this.code = code;
        }

        @Override
        public AutomationRuleEvaluatorType type() {
            return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
        }
    }

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    UUID id;

    @JsonView({View.Public.class, View.Write.class})
    @NotNull
    UUID projectId;

    @JsonView({View.Public.class, View.Write.class})
    @Schema(accessMode = Schema.AccessMode.READ_WRITE)
    float samplingRate;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    Instant createdAt;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    String createdBy;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    Instant lastUpdatedAt;

    @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    String lastUpdatedBy;

    @JsonView({View.Public.class})
    public abstract AutomationRuleEvaluatorType type();

    @JsonView({View.Public.class, View.Write.class})
    public abstract T getCode();

    @Override
    public AutomationRuleAction getAction() {
        return AutomationRuleAction.EVALUATOR;
    }

    public static class View {
        public static class Write {}
        public static class Public {}
    }

    @Builder(toBuilder = true)
    public record AutomationRuleEvaluatorPage(
            @JsonView({View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total,
            @JsonView({View.Public.class}) List<AutomationRuleEvaluatorLlmAsJudge> content)
            implements Page<AutomationRuleEvaluatorLlmAsJudge>{

        public static AutomationRuleEvaluator.AutomationRuleEvaluatorPage empty(int page) {
            return new AutomationRuleEvaluator.AutomationRuleEvaluatorPage(page, 0, 0, List.of());
        }
    }
}