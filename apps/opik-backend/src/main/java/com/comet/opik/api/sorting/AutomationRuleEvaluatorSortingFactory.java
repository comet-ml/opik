package com.comet.opik.api.sorting;

import com.google.common.collect.ImmutableMap;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.ENABLED;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.PROJECT_ID;
import static com.comet.opik.api.sorting.SortableFields.SAMPLING_RATE;
import static com.comet.opik.api.sorting.SortableFields.TYPE;

@Singleton
public class AutomationRuleEvaluatorSortingFactory extends SortingFactory {

    private static final Map<String, String> FIELD_TO_DB_COLUMN_MAP = ImmutableMap.<String, String>builder()
            .put(ID, "rule.id")
            .put(NAME, "rule.name")
            .put(TYPE, "evaluator.type")
            .put(ENABLED, "rule.enabled")
            .put(SAMPLING_RATE, "rule.sampling_rate")
            .put(PROJECT_ID, "rule.project_id")
            .put(CREATED_AT, "evaluator.created_at")
            .put(LAST_UPDATED_AT, "evaluator.last_updated_at")
            .put(CREATED_BY, "evaluator.created_by")
            .put(LAST_UPDATED_BY, "evaluator.last_updated_by")
            .build();

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                TYPE,
                ENABLED,
                SAMPLING_RATE,
                PROJECT_ID,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY);
    }

    public Map<String, String> getFieldMapping() {
        return FIELD_TO_DB_COLUMN_MAP;
    }
}
