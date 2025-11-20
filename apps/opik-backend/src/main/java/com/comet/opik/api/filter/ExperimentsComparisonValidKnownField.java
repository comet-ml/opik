package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@RequiredArgsConstructor
@Getter
public enum ExperimentsComparisonValidKnownField implements Field {

    ID(ID_QUERY_PARAM, FieldType.STRING),
    SOURCE(SOURCE_QUERY_PARAM, FieldType.STRING),
    TRACE_ID(TRACE_ID_QUERY_PARAM, FieldType.STRING),
    SPAN_ID(SPAN_ID_QUERY_PARAM, FieldType.STRING),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    LAST_UPDATED_AT(LAST_UPDATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    CREATED_BY(CREATED_BY_QUERY_PARAM, FieldType.STRING),
    LAST_UPDATED_BY(LAST_UPDATED_BY_QUERY_PARAM, FieldType.STRING),
    DURATION(DURATION_QUERY_PARAM, FieldType.NUMBER),
    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    TOTAL_ESTIMATED_COST(TOTAL_ESTIMATED_COST_QUERY_PARAM, FieldType.NUMBER),
    USAGE_TOTAL_TOKENS(USAGE_TOTAL_TOKEN_QUERY_PARAMS, FieldType.NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;

    public static Optional<ExperimentsComparisonValidKnownField> from(String name) {
        return Arrays.stream(values())
                .filter(field -> field.queryParamField.equals(name))
                .findFirst();
    }
}
