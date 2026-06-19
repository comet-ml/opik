package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.LAST_SEEN;
import static com.comet.opik.api.sorting.SortableFields.SEVERITY;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_OCCURRENCES;

public class AgentInsightsIssueSortingFactory extends SortingFactory {
    @Override
    public List<String> getSortableFields() {
        return List.of(
                LAST_SEEN,
                TOTAL_OCCURRENCES,
                SEVERITY);
    }
}
