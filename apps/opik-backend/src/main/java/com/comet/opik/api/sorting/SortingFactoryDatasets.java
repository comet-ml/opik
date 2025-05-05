package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_EXPERIMENT_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_CREATED_OPTIMIZATION_AT;
import static java.util.Arrays.asList;

public class SortingFactoryDatasets extends SortingFactory {
    private static final List<String> SORTABLE_FIELDS = asList(LAST_CREATED_EXPERIMENT_AT,
            LAST_CREATED_OPTIMIZATION_AT);

    @Override
    public List<String> getSortableFields() {
        return SORTABLE_FIELDS;
    }
}
