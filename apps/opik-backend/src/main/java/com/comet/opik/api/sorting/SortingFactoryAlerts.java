package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.WEBHOOK_URL;

public class SortingFactoryAlerts extends SortingFactory {
    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                WEBHOOK_URL);
    }
}
