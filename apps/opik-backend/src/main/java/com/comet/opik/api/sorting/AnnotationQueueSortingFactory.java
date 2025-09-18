package com.comet.opik.api.sorting;

import jakarta.inject.Singleton;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.INSTRUCTIONS;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.PROJECT_ID;

@Singleton
public class AnnotationQueueSortingFactory extends SortingFactory {

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                PROJECT_ID,
                NAME,
                DESCRIPTION,
                INSTRUCTIONS,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY);
    }
}
