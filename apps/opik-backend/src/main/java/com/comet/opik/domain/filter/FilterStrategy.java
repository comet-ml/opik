package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Field;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.domain.filter.FilterQueryBuilder.JSONPATH_ROOT;

public enum FilterStrategy {
    TRACE,
    TRACE_AGGREGATION,
    SPAN,
    EXPERIMENT_ITEM,
    DATASET_ITEM,
    FEEDBACK_SCORES;

    public static final String DYNAMIC_FIELD = ":dynamicField%1$d";

    public String dbFormattedField(Field field) {

        if (!field.isDynamic(this)) {
            return field.getQueryParamField();
        }

        return switch (field.getType()) {
            case STRING -> "JSON_VALUE(JSONExtract(toJSONString(data), %s, 'String'), '%s')".formatted(DYNAMIC_FIELD,
                    StringUtils.chop(JSONPATH_ROOT));
            case DATE_TIME ->
                "parseDateTime64BestEffort(JSON_VALUE(JSONExtract(toJSONString(data), %s, 'String'), '%s'), 9)"
                        .formatted(DYNAMIC_FIELD, StringUtils.chop(JSONPATH_ROOT));
            case NUMBER -> "toFloat64OrNull(JSON_VALUE(JSONExtract(toJSONString(data), %s, 'String'), '%s'))"
                    .formatted(DYNAMIC_FIELD, StringUtils.chop(JSONPATH_ROOT));
            case DICTIONARY -> "data[%s]".formatted(DYNAMIC_FIELD);
            case LIST -> "JSONExtract(toJSONString(data), %s, 'Array(String)')".formatted(DYNAMIC_FIELD);
            default -> throw new IllegalArgumentException("Invalid field type: " + field.getType());
        };
    }
}
