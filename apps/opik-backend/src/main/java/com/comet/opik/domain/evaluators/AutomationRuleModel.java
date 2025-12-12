package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;

import java.util.Set;
import java.util.UUID;

public sealed interface AutomationRuleModel
        permits AutomationRuleEvaluatorModel {

    UUID id();
    UUID projectId(); // Legacy single project field for backward compatibility (derived from projectIds)
    Set<UUID> projectIds(); // New multi-project support
    String name();

    Float samplingRate();
    boolean enabled();
    String filters();

    String createdBy();
    String lastUpdatedBy();

    AutomationRule.AutomationRuleAction action();
}
