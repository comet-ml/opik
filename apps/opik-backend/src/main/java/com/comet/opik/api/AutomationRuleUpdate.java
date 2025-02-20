package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "action", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdate.class, name = "evaluator")
})
@Schema(name = "AutomationRuleUpdate", discriminatorProperty = "action", discriminatorMapping = {
        @DiscriminatorMapping(value = "evaluator", schema = AutomationRuleEvaluatorUpdate.class)
})
public sealed interface AutomationRuleUpdate permits AutomationRuleEvaluatorUpdate {

    String getName();

    AutomationRule.AutomationRuleAction getAction();

    float getSamplingRate();

    UUID getProjectId();
}
