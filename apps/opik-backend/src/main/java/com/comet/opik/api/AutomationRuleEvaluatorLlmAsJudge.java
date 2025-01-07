package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder(toBuilder = true)
@ToString(callSuper = true)
public final class AutomationRuleEvaluatorLlmAsJudge
        extends AutomationRuleEvaluator<AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode> {

    @NotNull
    @JsonView({View.Public.class, View.Write.class})
    @Schema(accessMode = Schema.AccessMode.READ_WRITE)
    LlmAsJudgeCode code;

    @Data
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmAsJudgeCode {

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final LlmAsJudgeModelParameters model;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final List<LlmAsJudgeMessage> messages;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final Map<String, String> variableMapping;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final List<LlmAsJudgeOutputSchema> schema;

        @ConstructorProperties({"model", "messages", "variableMapping", "schema"})
        public LlmAsJudgeCode(@NotNull LlmAsJudgeModelParameters model, @NotNull List<LlmAsJudgeMessage> messages,
                              @NotNull Map<String, String> variableMapping, @NotNull List<LlmAsJudgeOutputSchema> schema) {
            this.model = model;
            this.messages = messages;
            this.variableMapping = variableMapping;
            this.schema = schema;
        }
    }

    @Data
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmAsJudgeMessage {

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final String role;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final String content;

        @ConstructorProperties({"role", "content"})
        public LlmAsJudgeMessage(@NotNull String role, @NotNull String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Data
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmAsJudgeOutputSchema {

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final String name;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final LlmAsJudgeOutputSchemaType type;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final String description;

        @ConstructorProperties({"name", "type", "description"})
        public LlmAsJudgeOutputSchema(@NotNull String name, @NotNull LlmAsJudgeOutputSchemaType type, @NotNull String description) {
            this.name = name;
            this.description = description;
            this.type = type;
        }
    }

    @Data
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmAsJudgeModelParameters {

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final String name;

        @JsonView({View.Public.class, View.Write.class})
        @NotNull
        private final Float temperature;

        @ConstructorProperties({"name", "temperature"})
        public LlmAsJudgeModelParameters(@NotNull String name, @NotNull Float temperature) {
            this.name = name;
            this.temperature = temperature;
        }
    }

    @ConstructorProperties({"id", "projectId", "name", "samplingRate", "code", "createdAt", "createdBy", "lastUpdatedAt", "lastUpdatedBy"})
    public AutomationRuleEvaluatorLlmAsJudge(UUID id, UUID projectId, @NotBlank String name, Float samplingRate, @NotNull LlmAsJudgeCode code,
                                             Instant createdAt, String createdBy, Instant lastUpdatedAt, String lastUpdatedBy) {
        super(id, projectId, name, samplingRate, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
        this.code = code;
    }

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
    }
}
