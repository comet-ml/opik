package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.jdbi.v3.json.Json;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationRuleEvaluatorUpdate(
        @NotNull String name,
        @Json @NotNull AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode code,
        @NotNull Float samplingRate) {
}
