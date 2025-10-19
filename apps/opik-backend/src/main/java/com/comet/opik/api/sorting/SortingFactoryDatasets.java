package com.comet.opik.api.sorting;

import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.COMMENTS;
import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DATA;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INPUT_DYNAMIC;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_EXPERIMENT_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_OPTIMIZATION_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.METADATA_DYNAMIC;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT_DYNAMIC;
import static com.comet.opik.api.sorting.SortableFields.TAGS;

public class SortingFactoryDatasets extends SortingFactory {

    private static final List<String> SUPPORTED_FIELDS = List.of(ID, NAME, DESCRIPTION, TAGS, CREATED_AT,
            LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_OPTIMIZATION_AT,
            DURATION, FEEDBACK_SCORES, DATA, OUTPUT_DYNAMIC, INPUT_DYNAMIC, METADATA_DYNAMIC, COMMENTS);

    @Override
    public List<String> getSortableFields() {
        return SUPPORTED_FIELDS;
    }

    @Override
    public List<SortingField> newSorting(String queryParam) {
        List<SortingField> sorting = new ArrayList<>();

        if (StringUtils.isBlank(queryParam)) {
            return sorting;
        }

        try {
            sorting = JsonUtils.readCollectionValue(queryParam, List.class, SortingField.class);
        } catch (UncheckedIOException exception) {
            throw new BadRequestException(ERR_INVALID_SORTING_PARAM_TEMPLATE.formatted(queryParam), exception);
        }

        // Ensure dynamic fields have bindKeyParam set (needed after JSON deserialization)
        sorting = sorting.stream()
                .map(this::ensureBindKeyParam)
                .toList();

        // Use parent's validation
        return super.validateAndReturn(sorting);
    }

    private SortingField ensureBindKeyParam(SortingField sortingField) {
        String field = sortingField.field();

        // JSON fields (output.*, input.*, metadata.*) should NOT be treated as dynamic
        // because they use JSONExtractRaw with literal keys in DatasetItemDAO
        if (field.startsWith("output.") || field.startsWith("input.") || field.startsWith("metadata.")) {
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
        if (bindKeyParam != null && !bindKeyParam.isBlank()) {
            return sortingField;
        }

        // Generate UUID for dynamic field
        bindKeyParam = java.util.UUID.randomUUID().toString().replace("-", "");
        return sortingField.toBuilder()
                .bindKeyParam(bindKeyParam)
                .build();
    }
}
