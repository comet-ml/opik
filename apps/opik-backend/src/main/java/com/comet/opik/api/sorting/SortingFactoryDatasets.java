package com.comet.opik.api.sorting;

import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DATA;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_EXPERIMENT_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_OPTIMIZATION_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;

public class SortingFactoryDatasets extends SortingFactory {

    private static final List<String> SUPPORTED_FIELDS = List.of(ID, NAME, DESCRIPTION, TAGS, CREATED_AT,
            LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_OPTIMIZATION_AT,
            DURATION, FEEDBACK_SCORES, DATA);

    private static final List<String> STATIC_FIELDS = List.of(ID, NAME, DESCRIPTION, TAGS, CREATED_AT,
            LAST_UPDATED_AT, CREATED_BY, LAST_UPDATED_BY, LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_OPTIMIZATION_AT,
            DURATION);

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

        // Validate BEFORE transforming to catch invalid field names
        super.validateAndReturn(sorting);

        // Transform field names for dataset columns after validation
        sorting = sorting.stream()
                .map(this::transformFieldName)
                .toList();

        return sorting;
    }

    private SortingField transformFieldName(SortingField sortingField) {
        String field = sortingField.field();

        // Regenerate bindKeyParam if it's null and field is dynamic
        String bindKeyParam = sortingField.bindKeyParam();
        if (bindKeyParam == null && field.contains(".")) {
            bindKeyParam = java.util.UUID.randomUUID().toString().replace("-", "");
        }

        // If already contains a dot, ensure bindKeyParam is set
        if (field.contains(".")) {
            return sortingField.toBuilder()
                    .bindKeyParam(bindKeyParam)
                    .build();
        }

        // If it's a known static field, no transformation needed
        if (STATIC_FIELDS.contains(field)) {
            return sortingField;
        }

        // Otherwise, it's a dataset column - prepend "data." and set bindKeyParam
        return sortingField.toBuilder()
                .field("data." + field)
                .bindKeyParam(java.util.UUID.randomUUID().toString().replace("-", ""))
                .build();
    }
}
