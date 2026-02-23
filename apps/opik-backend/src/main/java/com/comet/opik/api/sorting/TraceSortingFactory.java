package com.comet.opik.api.sorting;

import java.util.List;
import java.util.Map;

import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION;
import static com.comet.opik.api.sorting.SortableFields.END_TIME;
import static com.comet.opik.api.sorting.SortableFields.ERROR_INFO;
import static com.comet.opik.api.sorting.SortableFields.EXPERIMENT_ID;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INPUT;
import static com.comet.opik.api.sorting.SortableFields.LLM_SPAN_COUNT;
import static com.comet.opik.api.sorting.SortableFields.METADATA;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.OUTPUT;
import static com.comet.opik.api.sorting.SortableFields.SPAN_COUNT;
import static com.comet.opik.api.sorting.SortableFields.START_TIME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.THREAD_ID;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.TTFT;
import static com.comet.opik.api.sorting.SortableFields.USAGE;

public class TraceSortingFactory extends SortingFactory {

    /**
     * Field mapping for experiment sorting - maps API field names to database column names.
     * experiment_id sorts by experiment name for user-friendly alphabetical ordering.
     */
    public static final Map<String, String> EXPERIMENT_FIELD_MAPPING = Map.of("experiment_id", "eaag.experiment_name");

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
                TTFT,
                METADATA,
                THREAD_ID,
                SPAN_COUNT,
                LLM_SPAN_COUNT,
                USAGE,
                TOTAL_ESTIMATED_COST,
                TAGS,
                ERROR_INFO,
                CREATED_BY,
                FEEDBACK_SCORES,
                EXPERIMENT_ID);
    }
}
