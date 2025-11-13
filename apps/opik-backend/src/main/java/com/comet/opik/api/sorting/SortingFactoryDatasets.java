package com.comet.opik.api.sorting;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.UUID;

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
import static com.comet.opik.api.sorting.SortableFields.MOST_RECENT_EXPERIMENT_AT;
import static com.comet.opik.api.sorting.SortableFields.MOST_RECENT_OPTIMIZATION_AT;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT_WILDCARD;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.domain.filter.FilterQueryBuilder.INPUT_FIELD_PREFIX;
import static com.comet.opik.domain.filter.FilterQueryBuilder.METADATA_FIELD_PREFIX;
import static com.comet.opik.domain.filter.FilterQueryBuilder.OUTPUT_FIELD_PREFIX;

public class SortingFactoryDatasets extends SortingFactory {

    private static final List<String> SUPPORTED_FIELDS = List.of(ID, NAME, DESCRIPTION, TAGS, CREATED_AT,
            LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_OPTIMIZATION_AT,
            MOST_RECENT_EXPERIMENT_AT, MOST_RECENT_OPTIMIZATION_AT,
            DURATION, FEEDBACK_SCORES, DATA, OUTPUT_WILDCARD, INPUT_WILDCARD, METADATA_WILDCARD, COMMENTS);

    @Override
    public List<String> getSortableFields() {
        return SUPPORTED_FIELDS;
    }

    @Override
    protected List<SortingField> processFields(List<SortingField> sorting) {
        // Ensure dynamic fields have bindKeyParam set (needed after JSON deserialization)
        return sorting.stream()
                .map(this::mapComputedFields)
                .map(this::ensureBindKeyParam)
                .toList();
    }

    /**
     * Map computed fields (not in database) to their underlying database columns.
     * This allows API consumers to sort by fields they see in responses,
     * even if those fields are computed post-query.
     */
    private SortingField mapComputedFields(SortingField sortingField) {
        String field = sortingField.field();

        // Map most_recent_experiment_at (computed from experiment_items) to database column
        if (MOST_RECENT_EXPERIMENT_AT.equals(field)) {
            return sortingField.toBuilder()
                    .field(LAST_CREATED_EXPERIMENT_AT)
                    .build();
        }

        // Map most_recent_optimization_at (computed from optimizations) to database column
        if (MOST_RECENT_OPTIMIZATION_AT.equals(field)) {
            return sortingField.toBuilder()
                    .field(LAST_CREATED_OPTIMIZATION_AT)
                    .build();
        }

        return sortingField;
    }

    private SortingField ensureBindKeyParam(SortingField sortingField) {
        String field = sortingField.field();

        // JSON fields (output.*, input.*, metadata.*) should NOT be treated as dynamic
        // because they use JSONExtractRaw with literal keys in DatasetItemDAO
        if (field.startsWith(OUTPUT_FIELD_PREFIX) || field.startsWith(INPUT_FIELD_PREFIX)
                || field.startsWith(METADATA_FIELD_PREFIX)) {
            return sortingField.toBuilder()
                    .bindKeyParam(null)
                    .build();
        }

        // Only dynamic fields need bindKeyParam
        if (!sortingField.isDynamic()) {
            return sortingField;
        }

        // If bindKeyParam is already set, return as-is
        String bindKeyParam = sortingField.bindKeyParam();
        if (StringUtils.isNotBlank(bindKeyParam)) {
            return sortingField;
        }

        // Generate UUID for dynamic field
        bindKeyParam = UUID.randomUUID().toString().replace("-", "");
        return sortingField.toBuilder()
                .bindKeyParam(bindKeyParam)
                .build();
    }
}
