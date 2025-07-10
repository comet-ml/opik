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
    NUMBER("number"),
    FEEDBACK_SCORES_NUMBER("feedback_scores_number"),
    DICTIONARY("dictionary"),
    LIST("list"),
    ENUM("enum"),
    ERROR_CONTAINER("error_container");

    @JsonValue
    private final String queryParamType;
}
