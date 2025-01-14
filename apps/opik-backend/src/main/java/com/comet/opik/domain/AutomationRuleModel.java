package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;

import java.util.UUID;

public sealed interface AutomationRuleModel
        permits AutomationRuleEvaluatorModel {

    UUID id();
    UUID projectId();
    String name();

    Float samplingRate();

    String createdBy();
    String lastUpdatedBy();

    AutomationRule.AutomationRuleAction action();
}
