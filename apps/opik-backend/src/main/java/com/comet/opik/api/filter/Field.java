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
    String TAGS_QUERY_PARAM = "tags";
    String FEEDBACK_SCORES_QUERY_PARAM = "feedback_scores";

    @JsonValue
    String getQueryParamField();

    FieldType getType();
}
