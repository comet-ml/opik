package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;

public class SpendUserSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(TOTAL_ESTIMATED_COST, "requests", "skills", "mcps", "mcp_calls");
    }
}
