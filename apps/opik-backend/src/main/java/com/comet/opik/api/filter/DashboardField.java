package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum DashboardField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING_STATE_DB),
    NAME(NAME_QUERY_PARAM, FieldType.STRING_STATE_DB),
    TYPE(TYPE_QUERY_PARAM, FieldType.ENUM),
    SCOPE(SCOPE_QUERY_PARAM, FieldType.ENUM),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME_STATE_DB),
    LAST_UPDATED_AT(LAST_UPDATED_AT_QUERY_PARAM, FieldType.DATE_TIME_STATE_DB),
    CREATED_BY(CREATED_BY_QUERY_PARAM, FieldType.STRING_STATE_DB),
    LAST_UPDATED_BY(LAST_UPDATED_BY_QUERY_PARAM, FieldType.STRING_STATE_DB),
    ;

    private final String queryParamField;
    private final FieldType type;
}
