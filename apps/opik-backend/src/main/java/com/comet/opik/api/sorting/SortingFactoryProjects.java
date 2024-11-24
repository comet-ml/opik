package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_TRACE_AT;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static java.util.Arrays.asList;

public class SortingFactoryProjects extends SortingFactory {
    @Override
    public List<String> getSortableFields() {
        return asList(
                ID,
                NAME,
                LAST_UPDATED_AT,
                CREATED_AT,
                LAST_UPDATED_TRACE_AT);
    }
}
