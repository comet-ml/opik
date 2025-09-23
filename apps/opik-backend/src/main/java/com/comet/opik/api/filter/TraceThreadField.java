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
    START_TIME(START_TIME_QUERY_PARAM, FieldType.DATE_TIME),
    END_TIME(END_TIME_QUERY_PARAM, FieldType.DATE_TIME),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    STATUS(STATUS_QUERY_PARAM, FieldType.ENUM),
    TAGS(TAGS_QUERY_PARAM, FieldType.LIST),
    ANNOTATION_QUEUE_IDS(ANNOTATION_QUEUE_IDS_QUERY_PARAM, FieldType.LIST),
    ;

    private final String queryParamField;
    private final FieldType type;
}
