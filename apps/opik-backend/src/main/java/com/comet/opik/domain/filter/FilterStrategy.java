package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;

public enum FilterStrategy {
    TRACE,
    TRACE_AGGREGATION,
    ANNOTATION_AGGREGATION,
    EXPERIMENT_AGGREGATION,
    SPAN,
    EXPERIMENT_ITEM,
    DATASET_ITEM,
    FEEDBACK_SCORES,
    FEEDBACK_SCORES_AGGREGATED,
    TRACE_SPAN_FEEDBACK_SCORES,
    SPAN_FEEDBACK_SCORES,
    TRACE_THREAD,
    FEEDBACK_SCORES_IS_EMPTY,
    FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
    TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY,
    SPAN_FEEDBACK_SCORES_IS_EMPTY,
    EXPERIMENT,
    EXPERIMENT_SCORES,
    EXPERIMENT_SCORES_IS_EMPTY,
    PROMPT,
    PROMPT_VERSION,
    DATASET,
    ANNOTATION_QUEUE,
    ALERT,
    AUTOMATION_RULE_EVALUATOR,
    OPTIMIZATION;

    public static final String DYNAMIC_FIELD = ":dynamicField%1$d";

    public String dbFormattedField(Field field) {

        if (!field.isDynamic(this)) {
            return field.getQueryParamField();
        }

        String fieldName = field.getQueryParamField();
        int firstDot = fieldName.indexOf('.');

        // Handle dynamic fields like "metadata.environment" in MySQL (state DB)
        if (this == PROMPT_VERSION && firstDot > 0) {
            // For DICTIONARY_STATE_DB fields (like METADATA), return just the column name
            // e.g: "metadata.environment" - "metadata" is the JSON column name
            var columnName = fieldName.substring(0, firstDot);
            if (field.getType() == FieldType.DICTIONARY_STATE_DB) {
                return columnName;
            }
            throw new IllegalArgumentException("Invalid field type: '%s'".formatted(field.getType()));
        }

        // For EXPERIMENT_ITEM, handle fields like "output.some_field" where "output" is a column name
        if (this == EXPERIMENT_ITEM && firstDot > 0) {
            // Field like "output.some_field" - "output" is the column name, "some_field" is JSON path
            // Column names cannot be bind parameters in ClickHouse, so include them in the SQL template
            // Extract the column name and create the template with it embedded
            String columnName = fieldName.substring(0, firstDot);
            // Return a template with %1$d placeholder that will be formatted with the filter index
            return switch (field.getType()) {
                case STRING -> "JSON_VALUE(%s, :dynamicJsonPath%%1$d)".formatted(columnName);
                case NUMBER -> "toFloat64OrNull(JSON_VALUE(%s, :dynamicJsonPath%%1$d))".formatted(columnName);
                case DICTIONARY -> columnName;
                case LIST -> "JSONExtractArrayRaw(%s, :dynamicJsonPath%%1$d)".formatted(columnName);
                default -> throw new IllegalArgumentException("Invalid field type: " + field.getType());
            };
        }

        // For DATASET_ITEM, handle fields like "data.expected_answer"
        // Note: dataset_items.data is a Map(String, String), so we access keys directly, not via JSON_VALUE
        if (this == DATASET_ITEM && firstDot > 0) {
            // Field like "data.expected_answer" where "data" is the map column and "expected_answer" is the key
            // Just bind the key name and access the map directly
            return switch (field.getType()) {
                case STRING -> "data[:dynamicField%1$d]";
                case NUMBER -> "toFloat64OrNull(data[:dynamicField%1$d])";
                case DICTIONARY -> "data[:dynamicField%1$d]";
                case LIST -> "JSONExtractArrayRaw(data[:dynamicField%1$d])";
                default -> throw new IllegalArgumentException("Invalid field type: " + field.getType());
            };
        }

        // Default dynamic field handling for other strategies
        // Return template with %1$d placeholder that will be formatted with the filter index
        return switch (field.getType()) {
            case STRING -> "JSON_VALUE(data[:dynamicField%1$d], '$')";
            case DICTIONARY -> "data[:dynamicField%1$d]";
            case NUMBER -> "toFloat64OrNull(JSON_VALUE(data[:dynamicField%1$d], '$'))";
            case LIST -> "JSONExtractArrayRaw(data[:dynamicField%1$d])";
            default -> throw new IllegalArgumentException("Invalid field type: " + field.getType());
        };
    }
}
