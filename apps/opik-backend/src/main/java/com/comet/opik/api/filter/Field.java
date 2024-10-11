package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonValue;

public interface Field {

    String ID_QUERY_PARAM = "id";
    String NAME_QUERY_PARAM = "name";
    String START_TIME_QUERY_PARAM = "start_time";
    String END_TIME_QUERY_PARAM = "end_time";
    String INPUT_QUERY_PARAM = "input";
    String OUTPUT_QUERY_PARAM = "output";
    String METADATA_QUERY_PARAM = "metadata";
    String EXPECTED_OUTPUT_QUERY_PARAM = "expected_output";
    String TAGS_QUERY_PARAM = "tags";
    String USAGE_COMPLETION_TOKENS_QUERY_PARAM = "usage.completion_tokens";
    String USAGE_PROMPT_TOKENS_QUERY_PARAM = "usage.prompt_tokens";
    String USAGE_TOTAL_TOKEN_QUERY_PARAMS = "usage.total_tokens";
    String FEEDBACK_SCORES_QUERY_PARAM = "feedback_scores";

    @JsonValue
    String getQueryParamField();

    FieldType getType();
}
