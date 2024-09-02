package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum TraceField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING),
    NAME(NAME_QUERY_PARAM, FieldType.STRING),
    START_TIME(START_TIME_QUERY_PARAM, FieldType.DATE_TIME),
    END_TIME(END_TIME_QUERY_PARAM, FieldType.DATE_TIME),
    INPUT(INPUT_QUERY_PARAM, FieldType.STRING),
    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    TAGS(TAGS_QUERY_PARAM, FieldType.LIST),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
