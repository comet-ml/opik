package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.END_TIME;
import static com.comet.opik.api.sorting.SortableFields.ERROR_INFO;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INPUT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.METADATA;
import static com.comet.opik.api.sorting.SortableFields.MODEL;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT;
import static com.comet.opik.api.sorting.SortableFields.PARENT_SPAN_ID;
import static com.comet.opik.api.sorting.SortableFields.PROVIDER;
import static com.comet.opik.api.sorting.SortableFields.START_TIME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.TRACE_ID;
import static com.comet.opik.api.sorting.SortableFields.TTFT;
import static com.comet.opik.api.sorting.SortableFields.TYPE;
import static com.comet.opik.api.sorting.SortableFields.USAGE;

public class SpanSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                TYPE,
                TRACE_ID,
                PARENT_SPAN_ID,
                INPUT,
                OUTPUT,
                METADATA,
                START_TIME,
                END_TIME,
                DURATION,
                TTFT,
                USAGE,
                METADATA,
                TAGS,
                CREATED_AT,
                LAST_UPDATED_AT,
                MODEL,
                PROVIDER,
                TOTAL_ESTIMATED_COST,
                ERROR_INFO,
                CREATED_BY,
                FEEDBACK_SCORES);
    }
}