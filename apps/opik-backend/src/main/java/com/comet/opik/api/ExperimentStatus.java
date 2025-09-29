package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ExperimentStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ExperimentStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElse(COMPLETED);
    }
}
