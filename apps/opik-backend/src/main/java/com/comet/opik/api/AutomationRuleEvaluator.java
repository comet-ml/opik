package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

//@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
//@JsonSubTypes({
//        @JsonSubTypes.Type(value = AutomationRule.LlmAsJudgeEvaluator.class, name = "llm_as_judge"),
//        @JsonSubTypes.Type(value = AutomationRule.PythonAsJudgeEvaluator.class, name = "python")
//})
//@Schema(name = "Feedback", discriminatorProperty = "type", discriminatorMapping = {
//        @DiscriminatorMapping(value = "llm_as_judge", schema = AutomationRule.LlmAsJudgeEvaluator.class),
//        @DiscriminatorMapping(value = "python", schema = AutomationRule.PythonAsJudgeEvaluator.class)
//})
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationRuleEvaluator(
        // Fields and methods
        @JsonView({View.Public.class, View.Write.class}) UUID id,
        @JsonView({View.Public.class, View.Write.class}) UUID projectId,
        @JsonView({View.Public.class, View.Write.class}) AutomationRuleEvaluatorType evaluatorType,

        @JsonView({View.Public.class,
                View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String code,

        @JsonView({View.Public.class, View.Write.class}) float samplingRate,

        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

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
            @JsonView({View.Public.class}) List<AutomationRuleEvaluator> content)
            implements
                Page<AutomationRuleEvaluator>{

        public static AutomationRuleEvaluator.AutomationRuleEvaluatorPage empty(int page) {
            return new AutomationRuleEvaluator.AutomationRuleEvaluatorPage(page, 0, 0, List.of());
        }
    }
}