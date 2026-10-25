package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmAsJudgeModelParameters(
        @JsonView({
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull String name,
        @JsonView({AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) Double temperature,
        @JsonView({AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) Integer seed,
        // The Python and TypeScript SDKs and the frontend's test-suite editor save a suite judge's model block with
        // this key in camelCase, and ignoreUnknown would otherwise drop it without a word.
        @JsonAlias("customParameters") @JsonView({AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) JsonNode customParameters) {
}
