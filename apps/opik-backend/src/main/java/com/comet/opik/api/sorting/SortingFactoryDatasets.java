package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.COMMENTS;
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
            DURATION, FEEDBACK_SCORES, DATA, COMMENTS);

    @Override
    public List<String> getSortableFields() {
        return SUPPORTED_FIELDS;
    }
}
