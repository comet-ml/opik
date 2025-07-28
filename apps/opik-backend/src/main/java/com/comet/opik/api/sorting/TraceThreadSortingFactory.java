package com.comet.opik.api.sorting;

import jakarta.inject.Singleton;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.END_TIME;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.NUMBER_OF_MESSAGES;
import static com.comet.opik.api.sorting.SortableFields.START_TIME;
import static com.comet.opik.api.sorting.SortableFields.STATUS;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.USAGE;

@Singleton
public class TraceThreadSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                START_TIME,
                END_TIME,
                DURATION,
                NUMBER_OF_MESSAGES,
                LAST_UPDATED_AT,
                CREATED_BY,
                CREATED_AT,
                USAGE,
                TOTAL_ESTIMATED_COST,
                FEEDBACK_SCORES,
                STATUS,
                TAGS);
    }
}
