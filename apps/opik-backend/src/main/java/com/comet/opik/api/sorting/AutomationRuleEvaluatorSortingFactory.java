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
import static com.comet.opik.api.sorting.SortableFields.SAMPLING_RATE;
import static com.comet.opik.api.sorting.SortableFields.TYPE;

@Singleton
public class AutomationRuleEvaluatorSortingFactory extends SortingFactory {

    // Table alias prefixes for AutomationRuleEvaluator queries
    private static final String AUTOMATION_RULE_TABLE_ALIAS = "rule.%s";
    private static final String AUTOMATION_EVALUATOR_TABLE_ALIAS = "evaluator.%s";

    private static final Map<String, String> FIELD_TO_DB_COLUMN_MAP = ImmutableMap.<String, String>builder()
            .put(ID, String.format(AUTOMATION_RULE_TABLE_ALIAS, "id"))
            .put(NAME, String.format(AUTOMATION_RULE_TABLE_ALIAS, "name"))
            .put(TYPE, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, "type"))
            .put(ENABLED, String.format(AUTOMATION_RULE_TABLE_ALIAS, "enabled"))
            .put(SAMPLING_RATE, String.format(AUTOMATION_RULE_TABLE_ALIAS, "sampling_rate"))
            .put(CREATED_AT, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, "created_at"))
            .put(LAST_UPDATED_AT, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, "last_updated_at"))
            .put(CREATED_BY, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, "created_by"))
            .put(LAST_UPDATED_BY, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, "last_updated_by"))
            .build();

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                TYPE,
                ENABLED,
                SAMPLING_RATE,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY);
    }

    public Map<String, String> getFieldMapping() {
        return FIELD_TO_DB_COLUMN_MAP;
    }
}
