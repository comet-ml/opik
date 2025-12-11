package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@RequiredArgsConstructor
@Getter
public enum DatasetItemField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING),
    DATA(DATA_QUERY_PARAM, FieldType.MAP),
    FULL_DATA(FULL_DATA_QUERY_PARAM, FieldType.STRING),
    SOURCE(SOURCE_QUERY_PARAM, FieldType.STRING),
    TRACE_ID(TRACE_ID_QUERY_PARAM, FieldType.STRING),
    SPAN_ID(SPAN_ID_QUERY_PARAM, FieldType.STRING),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    LAST_UPDATED_AT(LAST_UPDATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    CREATED_BY(CREATED_BY_QUERY_PARAM, FieldType.STRING),
    LAST_UPDATED_BY(LAST_UPDATED_BY_QUERY_PARAM, FieldType.STRING),
    TAGS(TAGS_QUERY_PARAM, FieldType.LIST),
    ;

    private final String queryParamField;
    private final FieldType type;

    @JsonCreator
    public static DatasetItemField fromString(String value) {
        return Arrays.stream(values())
                .filter(field -> field.queryParamField.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown DatasetItemField '%s'".formatted(value)));
    }
}
