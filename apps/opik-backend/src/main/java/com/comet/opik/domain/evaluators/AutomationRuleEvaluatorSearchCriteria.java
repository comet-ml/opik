package com.comet.opik.domain.evaluators;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Search criteria for AutomationRuleEvaluators.
 * Follows the same pattern as TraceSearchCriteria for consistency.
 *
 * Fields:
 * - projectId: The project to query evaluators from
 * - id: The evaluator's ID (partial match supported)
 * - name: The evaluator's name (partial match supported)
 * - filters: Additional filter criteria
 * - sortingFields: Sorting configuration
 */
@Builder(toBuilder = true)
public record AutomationRuleEvaluatorSearchCriteria(
        UUID projectId,
        String id,
        String name,
        List<? extends Filter> filters,
        List<SortingField> sortingFields) {
}
