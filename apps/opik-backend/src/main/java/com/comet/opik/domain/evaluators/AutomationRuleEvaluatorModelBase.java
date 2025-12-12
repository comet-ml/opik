package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
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
 *
 * Uses Lombok's @SuperBuilder to enable child classes to inherit builder functionality
 * and add only their type-specific fields (e.g., code).
 *
 * Uses @Accessors(fluent = true) to generate Record-style getters (e.g., id() instead of getId())
 * that match the interface method signatures automatically.
 *
 * CRITICAL: @NoArgsConstructor is REQUIRED by @SuperBuilder - Lombok generates child class code
 * that implicitly calls super(), which requires a no-args constructor in the parent class.
 * Without it, compilation fails with "no suitable constructor found for AutomationRuleEvaluatorModelBase(no arguments)".
 *
 * Uses @AllArgsConstructor(access = AccessLevel.PROTECTED) to generate a protected constructor
 * for SuperBuilder, while child classes use @AllArgsConstructor(access = AccessLevel.PUBLIC)
 * to provide JDBI with public constructors for reflection-based instantiation.
 *
 * This eliminates duplication of common field assignments in factory methods.
 * Consistent with the API layer pattern used in AutomationRuleEvaluator.
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Accessors(fluent = true)
public abstract non-sealed class AutomationRuleEvaluatorModelBase<T> implements AutomationRuleModel {

    private UUID id;
    private UUID projectId; // Legacy single project field for backwards compatibility
    private String projectName; // Legacy project name field (resolved from projectId)
    private Set<UUID> projectIds; // New multi-project field
    private String name;
    private Float samplingRate;
    private boolean enabled;
    private String filters;
    private Instant createdAt;
    private String createdBy;
    private Instant lastUpdatedAt;
    private String lastUpdatedBy;

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
