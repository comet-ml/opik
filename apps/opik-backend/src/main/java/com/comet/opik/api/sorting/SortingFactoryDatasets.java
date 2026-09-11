package com.comet.opik.api.sorting;

import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Set;

import static com.comet.opik.api.sorting.SortableFields.COMMENTS;
import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DATA;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INPUT_WILDCARD;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_EXPERIMENT_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_OPTIMIZATION_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.METADATA_WILDCARD;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT_WILDCARD;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.USAGE;

public class SortingFactoryDatasets extends SortingFactory {

    private static final List<String> SUPPORTED_FIELDS = List.of(ID, NAME, DESCRIPTION, TAGS, CREATED_AT,
            LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_OPTIMIZATION_AT,
            DURATION, FEEDBACK_SCORES, DATA, OUTPUT_WILDCARD, INPUT_WILDCARD, METADATA_WILDCARD, COMMENTS,
            TOTAL_ESTIMATED_COST, USAGE);

    // Fields that can be sorted in the push-top-limit CTE (available in experiment_item_aggregates or dataset_items)
    private static final Set<String> PUSH_TOP_SUPPORTED = Set.of(
            ID, DESCRIPTION, TAGS, CREATED_AT, LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY,
            DURATION, FEEDBACK_SCORES, DATA, OUTPUT_WILDCARD, INPUT_WILDCARD, METADATA_WILDCARD,
            TOTAL_ESTIMATED_COST, USAGE);

    // Fields that require JOIN with dataset_items_aggr_resolved in the CTE
    private static final Set<String> PUSH_TOP_NEEDS_DIV_JOIN = Set.of(
            DESCRIPTION, TAGS, CREATED_AT, LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, DATA);

    @Override
    public List<String> getSortableFields() {
        return SUPPORTED_FIELDS;
    }

    /**
     * Returns true if all sorting fields can be evaluated in the push-top-limit CTE.
     * When false, the optimization cannot be applied.
     */
    public boolean supportsPushTopLimit(List<SortingField> sorting) {
        if (CollectionUtils.isEmpty(sorting)) {
            return false;
        }
        return sorting.stream()
                .allMatch(sf -> matchesSupported(sf.field(), PUSH_TOP_SUPPORTED));
    }

    /**
     * Returns true if any sorting field requires the dataset_items_aggr_resolved JOIN in the CTE.
     */
    public boolean pushTopLimitNeedsDivJoin(List<SortingField> sorting) {
        if (CollectionUtils.isEmpty(sorting)) {
            return false;
        }
        return sorting.stream()
                .anyMatch(sf -> matchesSupported(sf.field(), PUSH_TOP_NEEDS_DIV_JOIN));
    }

    private boolean matchesSupported(String field, Set<String> supportedSet) {
        if (supportedSet.contains(field)) {
            return true;
        }
        // Check wildcard fields (e.g., "feedback_scores.accuracy" matches "feedback_scores.*")
        int dotIndex = field.indexOf('.');
        if (dotIndex > 0) {
            String wildcardField = field.substring(0, dotIndex) + ".*";
            return supportedSet.contains(wildcardField);
        }
        return false;
    }

    // Note: bindKeyParam is generated in SortingField's canonical constructor (a safe identifier for
    // dynamic fields), so no factory-side normalization is needed here.
}
