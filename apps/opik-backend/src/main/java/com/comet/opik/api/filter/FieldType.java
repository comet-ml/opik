package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum FieldType {
    STRING("string"),
    DATE_TIME("date_time"),
    NUMBER("number"),
    FEEDBACK_SCORES_NUMBER("feedback_scores_number"),
    DICTIONARY("dictionary"),
    LIST("list"),
    ENUM("enum"),
    ;

    @JsonValue
    private final String queryParamType;
}
