package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AutomationRuleEvaluatorCriteria(
        AutomationRuleEvaluatorType type,
        String name,
        Set<UUID> ids) {

    public AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }
}
