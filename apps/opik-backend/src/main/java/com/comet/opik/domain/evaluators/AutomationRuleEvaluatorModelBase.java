package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Set;
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
    private final Set<UUID> projectIds = null; // New multi-project field
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

    // Note: code() method is declared in AutomationRuleEvaluatorModel interface
    // and implemented by concrete classes via Lombok @Getter @Accessors(fluent = true) on their code field

    /**
     * Convenience method for SuperBuilder to set all common fields at once from CommonFields record.
     * This is the key to DRY - child builders can call this instead of setting 12 fields individually.
     *
     * Usage in factory methods:
     * <pre>
     * return builder()
     *     .commonFields(common)  // Sets all 12 common fields!
     *     .code(parseCode())      // Only set type-specific field
     *     .build();
     * </pre>
     */
    public abstract static class AutomationRuleEvaluatorModelBaseBuilder<T, C extends AutomationRuleEvaluatorModelBase<T>, B extends AutomationRuleEvaluatorModelBaseBuilder<T, C, B>> {

        /**
         * Sets all common fields from CommonFields record in one call.
         * Eliminates 12-line builder chains down to 1 line.
         */
        public B commonFields(AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common) {
            return this.id(common.id())
                    .projectId(common.projectId())
                    .projectName(common.projectName())
                    .projectIds(common.projectIds())
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
