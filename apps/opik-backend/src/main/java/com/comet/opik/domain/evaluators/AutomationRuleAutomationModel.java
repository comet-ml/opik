package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code automation} flavour of an automation rule: the parent row in {@code automation_rules} plus
 * the triggers, steps and limits from {@code automation_rule_automations}, kept as the JSON the API wrote.
 *
 * <p>Like the router, one project per rule: an automation reads and writes objects of one project.
 */
@Builder(toBuilder = true)
public record AutomationRuleAutomationModel(
        UUID id,
        UUID projectId,
        String name,
        Float samplingRate,
        boolean enabled,
        EvalTriggerScope triggerScope,
        String filters,
        String triggers,
        String steps,
        @Nullable String limits,
        @Nullable String recipeId,
        @Nullable Integer recipeVersion,
        String createdVia,
        @Nullable Instant pausedUntil,
        @Nullable String pausedReason,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) implements AutomationRuleModel {

    @Override
    public Set<UUID> projectIds() {
        return Set.of(projectId);
    }

    @Override
    public AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.AUTOMATION;
    }
}
