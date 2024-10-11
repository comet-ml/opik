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
    USAGE_COMPLETION_TOKENS(USAGE_COMPLETION_TOKENS_QUERY_PARAM, FieldType.NUMBER),
    USAGE_PROMPT_TOKENS(USAGE_PROMPT_TOKENS_QUERY_PARAM, FieldType.NUMBER),
    USAGE_TOTAL_TOKENS(USAGE_TOTAL_TOKEN_QUERY_PARAMS, FieldType.NUMBER),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
