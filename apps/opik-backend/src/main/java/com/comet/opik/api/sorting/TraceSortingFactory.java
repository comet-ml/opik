package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.END_TIME;
import static com.comet.opik.api.sorting.SortableFields.ERROR_INFO;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INPUT;
import static com.comet.opik.api.sorting.SortableFields.METADATA;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT;
import static com.comet.opik.api.sorting.SortableFields.SPAN_COUNT;
import static com.comet.opik.api.sorting.SortableFields.START_TIME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.THREAD_ID;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.USAGE_DYNAMIC;

public class TraceSortingFactory extends SortingFactory {

    @Override
    public List<SortingField> newSorting(String queryParam) {
        List<SortingField> sorting = super.newSorting(queryParam);

        return sorting.stream()
                .map(field -> {
                    if (field.field().startsWith("usage_")) {
                        return field.toBuilder()
                                .field(field.field().replaceFirst("usage_", "usage."))
                                .build();
                    }
                    return field;
                })
                .toList();
    }

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
                SPAN_COUNT,
                USAGE_DYNAMIC,
                TOTAL_ESTIMATED_COST,
                TAGS,
                ERROR_INFO,
                CREATED_BY,
                FEEDBACK_SCORES);
    }
}
