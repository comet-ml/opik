package com.comet.opik.api.sorting;

import com.comet.opik.api.spend.SpendUserField;

import java.util.List;

public class SpendUserSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(SpendUserField.TOTAL_TOKENS, SpendUserField.REQUESTS, SpendUserField.SKILLS,
                SpendUserField.MCPS, SpendUserField.MCP_CALLS);
    }
}
