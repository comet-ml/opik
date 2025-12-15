package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum FieldType {
    STRING("string"),
    STRING_STATE_DB("string_state_db"),
    DATE_TIME("date_time"),
    DATE_TIME_STATE_DB("date_time_state_db"),
    NUMBER("number"),
    DURATION("duration"), // Duration is treated as a NUMBER internally
    FEEDBACK_SCORES_NUMBER("feedback_scores_number"),
    DICTIONARY("dictionary"),
    DICTIONARY_STATE_DB("dictionary_state_db"),
    MAP("map"),
    LIST("list"),
    ENUM("enum"),
    ERROR_CONTAINER("error_container"),
    CUSTOM("custom"),
    ;

    @JsonValue
    private final String queryParamType;
}
