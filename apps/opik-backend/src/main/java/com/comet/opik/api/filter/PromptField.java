package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum PromptField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING_STATE_DB),
    NAME(NAME_QUERY_PARAM, FieldType.STRING_STATE_DB),
    DESCRIPTION(DESCRIPTION_QUERY_PARAM, FieldType.STRING_STATE_DB),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME_STATE_DB),
    LAST_UPDATED_AT(LAST_UPDATED_AT_QUERY_PARAM, FieldType.DATE_TIME_STATE_DB),
    CREATED_BY(CREATED_BY_QUERY_PARAM, FieldType.STRING_STATE_DB),
    LAST_UPDATED_BY(LAST_UPDATED_BY_QUERY_PARAM, FieldType.STRING_STATE_DB),
    TAGS(TAGS_QUERY_PARAM, FieldType.STRING_STATE_DB),
    VERSION_COUNT(VERSION_COUNT_QUERY_PARAM, FieldType.NUMBER),
    TEMPLATE_STRUCTURE(TEMPLATE_STRUCTURE_QUERY_PARAM, FieldType.STRING_STATE_DB),
    ;

    private final String queryParamField;
    private final FieldType type;
}
