package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueueAutomation;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Decides whether an entity's feedback scores satisfy an automation's conditions.
 *
 * <p>Pure and stateless on purpose: this is the seam where the decision logic changes. Swapping the score
 * DSL for the full filter language, or for sampling, replaces this class and touches nothing else.
 */
@Singleton
public class AnnotationQueueConditionEvaluator {

    /**
     * A disjunction of conjunctions: any group matching is enough, and a group matches only when all of
     * its conditions do.
     */
    public boolean matches(@NonNull AnnotationQueueAutomation.Conditions conditions,
            @NonNull Map<String, BigDecimal> scores) {

        if (CollectionUtils.isEmpty(conditions.groups())) {
            return false;
        }

        return conditions.groups().stream().anyMatch(group -> matchesGroup(group, scores));
    }

    private boolean matchesGroup(AnnotationQueueAutomation.ConditionGroup group, Map<String, BigDecimal> scores) {
        if (CollectionUtils.isEmpty(group.conditions())) {
            return false;
        }

        return group.conditions().stream().allMatch(condition -> matchesCondition(condition, scores));
    }

    /**
     * A condition on a score the entity does not have yet does <strong>not</strong> match — an absent
     * score is not evidence a threshold was crossed. This is what makes multi-score conjunctions behave:
     * scores in an AND arrive at different times, sometimes hours apart, and the entity only becomes
     * eligible once the last one lands. Mirrors {@code MetricsAlertJob.evaluateGroup}, which skips a group
     * outright when any of its metrics has no data.
     */
    private boolean matchesCondition(AnnotationQueueAutomation.ScoreCondition condition,
            Map<String, BigDecimal> scores) {

        BigDecimal actual = scores.get(condition.scoreName());
        if (actual == null) {
            return false;
        }

        // compareTo, not equals: the effective score is a Decimal64(9), so 1 arrives as 1.000000000 and
        // BigDecimal.equals would reject it on scale alone.
        int comparison = actual.compareTo(BigDecimal.valueOf(condition.value()));

        return switch (condition.operator()) {
            case GREATER_THAN -> comparison > 0;
            case LESS_THAN -> comparison < 0;
            case EQUAL -> comparison == 0;
        };
    }
}
