package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum TraceThreadField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING),
    FIRST_MESSAGE(FIRST_MESSAGE_QUERY_PARAM, FieldType.STRING),
    LAST_MESSAGE(LAST_MESSAGE_QUERY_PARAM, FieldType.STRING),
    NUMBER_OF_MESSAGES(NUMBER_OF_MESSAGES_QUERY_PARAM, FieldType.NUMBER),
    DURATION(DURATION_QUERY_PARAM, FieldType.NUMBER),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    LAST_UPDATED_AT(LAST_UPDATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    ;

    private final String queryParamField;
    private final FieldType type;
}
