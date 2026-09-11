package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ParamPresence {

    REQUIRED("required"),
    OPTIONAL("optional");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ParamPresence fromValue(String value) {
        for (ParamPresence p : values()) {
            if (p.value.equals(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown param presence: " + value);
    }
}
