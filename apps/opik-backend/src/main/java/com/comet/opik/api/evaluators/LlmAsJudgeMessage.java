package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import dev.langchain4j.data.message.ChatMessageType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmAsJudgeMessage(
        @JsonView( {
                AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull ChatMessageType role,
        @JsonView({AutomationRuleEvaluator.View.Public.class,
                AutomationRuleEvaluator.View.Write.class}) @NotNull String content){
}
