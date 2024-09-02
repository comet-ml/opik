package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum SpanField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING),
    NAME(NAME_QUERY_PARAM, FieldType.STRING),
    START_TIME(START_TIME_QUERY_PARAM, FieldType.DATE_TIME),
    END_TIME(END_TIME_QUERY_PARAM, FieldType.DATE_TIME),
    INPUT(INPUT_QUERY_PARAM, FieldType.STRING),
    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    TAGS(TAGS_QUERY_PARAM, FieldType.LIST),
    USAGE_COMPLETION_TOKENS("usage.completion_tokens", FieldType.NUMBER),
    USAGE_PROMPT_TOKENS("usage.prompt_tokens", FieldType.NUMBER),
    USAGE_TOTAL_TOKENS("usage.total_tokens", FieldType.NUMBER),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
