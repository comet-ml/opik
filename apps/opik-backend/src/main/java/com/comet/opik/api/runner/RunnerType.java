package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RunnerType {

    CONNECT("connect"),
    ENDPOINT("endpoint");

    @JsonValue
    private final String value;

    @JsonCreator
    public static RunnerType fromValue(String value) {
        for (RunnerType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown runner type: " + value);
    }
}
