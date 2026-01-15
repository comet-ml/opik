package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION_AGG;
import static com.comet.opik.api.sorting.SortableFields.EXPERIMENT_METRICS;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST_AVG;
import static com.comet.opik.api.sorting.SortableFields.TRACE_COUNT;

public class ExperimentSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                TAGS,
                TRACE_COUNT,
                TOTAL_ESTIMATED_COST,
                TOTAL_ESTIMATED_COST_AVG,
                FEEDBACK_SCORES,
                EXPERIMENT_METRICS,
                DURATION_AGG);
    }
}
