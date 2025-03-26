package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.END_TIME;
import static com.comet.opik.api.sorting.SortableFields.ERROR_INFO;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INPUT;
import static com.comet.opik.api.sorting.SortableFields.METADATA;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT;
import static com.comet.opik.api.sorting.SortableFields.START_TIME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.THREAD_ID;

public class TraceSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                INPUT,
                OUTPUT,
                START_TIME,
                END_TIME,
                DURATION,
                METADATA,
                THREAD_ID,
                TAGS,
                ERROR_INFO,
                CREATED_BY);
    }
}
