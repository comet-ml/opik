package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.ProjectReference;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

/**
 * Abstract base class for all automation rule evaluator models.
 * Contains common fields shared across all model types.
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Accessors(fluent = true)
public abstract non-sealed class AutomationRuleEvaluatorModelBase<T> implements AutomationRuleModel {

    @Builder.Default
    private final UUID id = null;
    @Builder.Default
    private final UUID projectId = null; // Legacy single project field for backwards compatibility
    @Builder.Default
    private final String projectName = null; // Legacy project name field (resolved from projectId)
    @Builder.Default
    private final Set<UUID> projectIds = null; // Multi-project IDs (database field)
    @Builder.Default
    private final SortedSet<ProjectReference> projects = null; // Projects assigned to this rule (unique, sorted alphabetically by name, enriched from projectIds)
    @Builder.Default
    private final String name = null;
    @Builder.Default
    private final Float samplingRate = null;
    @Builder.Default
    private final boolean enabled = false;
    @Builder.Default
    private final String filters = null;
    @Builder.Default
    private final Instant createdAt = null;
    @Builder.Default
    private final String createdBy = null;
    @Builder.Default
    private final Instant lastUpdatedAt = null;
    @Builder.Default
    private final String lastUpdatedBy = null;

    @Override
    public AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }

    public abstract static class AutomationRuleEvaluatorModelBaseBuilder<T, C extends AutomationRuleEvaluatorModelBase<T>, B extends AutomationRuleEvaluatorModelBaseBuilder<T, C, B>> {

        public B commonFields(AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common) {
            return this.id(common.id())
                    .projectId(common.projectId())
                    .projectName(common.projectName())
                    .projectIds(common.projectIds())
                    .projects(common.projects())
                    .name(common.name())
                    .samplingRate(common.samplingRate())
                    .enabled(common.enabled())
                    .filters(common.filters())
                    .createdAt(common.createdAt())
                    .createdBy(common.createdBy())
                    .lastUpdatedAt(common.lastUpdatedAt())
                    .lastUpdatedBy(common.lastUpdatedBy());
        }
    }
}
