package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;

import java.util.UUID;

public sealed interface AutomationRuleModel<T>
        permits AutomationRuleEvaluatorModel {

    UUID id();
    UUID projectId();
    float samplingRate();

    String createdBy();
    String lastUpdatedBy();

    AutomationRule.AutomationRuleAction action();
}
