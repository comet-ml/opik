package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum AnnotationQueueField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING),
    PROJECT_ID("project_id", FieldType.STRING),
    NAME(NAME_QUERY_PARAM, FieldType.STRING),
    DESCRIPTION(DESCRIPTION_QUERY_PARAM, FieldType.STRING),
    INSTRUCTIONS("instructions", FieldType.STRING),
    SCOPE("scope", FieldType.ENUM),
    FEEDBACK_DEFINITION_NAMES("feedback_definition_names", FieldType.LIST),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    CREATED_BY(CREATED_BY_QUERY_PARAM, FieldType.STRING),
    LAST_UPDATED_AT(LAST_UPDATED_AT_QUERY_PARAM, FieldType.DATE_TIME),
    LAST_UPDATED_BY(LAST_UPDATED_BY_QUERY_PARAM, FieldType.STRING),
    ;

    private final String queryParamField;
    private final FieldType type;
}
