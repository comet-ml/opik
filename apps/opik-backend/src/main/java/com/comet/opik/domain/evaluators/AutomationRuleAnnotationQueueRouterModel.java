package com.comet.opik.domain.evaluators;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import lombok.Builder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code annotation_queue} flavour of an automation rule: the parent row in {@code automation_rules}
 * plus the queue-specific columns from {@code automation_rule_annotation_queue_routers}.
 *
 * <p>Unlike the evaluator models this one has no API twin. A queue automation is configured through its
 * queue's own endpoints rather than the automation-rules API, so the rule is a storage arrangement — it
 * buys the shared columns and the family's conventions, not a new public resource.
 */
@Builder(toBuilder = true)
public record AutomationRuleAnnotationQueueRouterModel(
        UUID id,
        UUID projectId,
        String name,
        Float samplingRate,
        boolean enabled,
        EvalTriggerScope triggerScope,
        String filters,
        UUID queueId,
        AnnotationQueue.AnnotationScope scope,
        String conditions,
        Integer maxItemsInQueue,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) implements AutomationRuleModel {

    /**
     * Derived rather than stored: the interface is shaped for evaluators, which can span projects, while a
     * router reaches exactly one through its queue. Keeping it out of the components leaves every component
     * a real column, which is what lets JDBI map this row by constructor instead of by hand.
     */
    @Override
    public Set<UUID> projectIds() {
        return Set.of(projectId);
    }

    @Override
    public AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.ANNOTATION_QUEUE_ROUTER;
    }
}
