package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;

import java.util.Set;
import java.util.UUID;

public sealed interface AutomationRuleModel
        permits AutomationRuleEvaluatorModel {

    UUID id();
    Set<UUID> projectIds();
    String name();

    Float samplingRate();
    boolean enabled();
    String filters();

    String createdBy();
    String lastUpdatedBy();

    AutomationRule.AutomationRuleAction action();
}
