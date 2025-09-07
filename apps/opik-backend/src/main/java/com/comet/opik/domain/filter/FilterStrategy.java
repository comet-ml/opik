package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Field;

import static com.comet.opik.domain.filter.FilterQueryBuilder.JSONPATH_ROOT;

public enum FilterStrategy {
    TRACE,
    TRACE_AGGREGATION,
    SPAN,
    EXPERIMENT_ITEM,
    DATASET_ITEM,
    FEEDBACK_SCORES,
    TRACE_THREAD,
    FEEDBACK_SCORES_IS_EMPTY,
    EXPERIMENT,
    PROMPT,
    DATASET,
    ANNOTATION_QUEUE;

    public static final String DYNAMIC_FIELD = ":dynamicField%1$d";

    public String dbFormattedField(Field field) {

        if (!field.isDynamic(this)) {
            return field.getQueryParamField();
        }

        return switch (field.getType()) {
            case STRING -> "JSON_VALUE(data[%s], '%s')".formatted(DYNAMIC_FIELD, JSONPATH_ROOT);
            case DICTIONARY -> "data[%s]".formatted(DYNAMIC_FIELD);
            case NUMBER ->
                "toFloat64OrNull(JSON_VALUE(data[%s], '%s'))".formatted(DYNAMIC_FIELD, JSONPATH_ROOT);
            default -> throw new IllegalArgumentException("Invalid field type: " + field.getType());
        };
    }
}
