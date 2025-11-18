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
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT_WILDCARD;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.USAGE;
import static com.comet.opik.domain.filter.FilterQueryBuilder.INPUT_FIELD_PREFIX;
import static com.comet.opik.domain.filter.FilterQueryBuilder.METADATA_FIELD_PREFIX;
import static com.comet.opik.domain.filter.FilterQueryBuilder.OUTPUT_FIELD_PREFIX;

public class SortingFactoryDatasets extends SortingFactory {

    private static final List<String> SUPPORTED_FIELDS = List.of(ID, NAME, DESCRIPTION, TAGS, CREATED_AT,
            LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_OPTIMIZATION_AT,
            DURATION, FEEDBACK_SCORES, DATA, OUTPUT_WILDCARD, INPUT_WILDCARD, METADATA_WILDCARD, COMMENTS,
            TOTAL_ESTIMATED_COST, USAGE);

    @Override
    public List<String> getSortableFields() {
        return SUPPORTED_FIELDS;
    }

    @Override
    protected List<SortingField> processFields(List<SortingField> sorting) {
        // Ensure dynamic fields have bindKeyParam set (needed after JSON deserialization)
        return sorting.stream()
                .map(this::ensureBindKeyParam)
                .toList();
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
