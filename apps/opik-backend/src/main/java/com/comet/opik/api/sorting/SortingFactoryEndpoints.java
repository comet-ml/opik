package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.NAME;

public class SortingFactoryEndpoints extends SortingFactory {
    @Override
    public List<String> getSortableFields() {
        return List.of(NAME, CREATED_AT, LAST_UPDATED_AT);
    }
}
