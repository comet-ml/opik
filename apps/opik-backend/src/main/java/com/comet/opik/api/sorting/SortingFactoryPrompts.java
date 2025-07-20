package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.api.sorting.SortableFields.VERSION_COUNT;

public class SortingFactoryPrompts extends SortingFactory {
    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                DESCRIPTION,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                VERSION_COUNT,
                TAGS);
    }
}
