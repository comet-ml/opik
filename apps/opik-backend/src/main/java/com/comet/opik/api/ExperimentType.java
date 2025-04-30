package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ExperimentType {
    REGULAR("regular"),
    TRIAL("trial"),
    MINI_BATCH("mini-batch");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ExperimentType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Experiment Type '%s'".formatted(value)));
    }
}
