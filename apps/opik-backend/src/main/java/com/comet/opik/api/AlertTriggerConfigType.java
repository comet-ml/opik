package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum AlertTriggerConfigType {
    SCOPE_PROJECT("scope:project"),
    THRESHOLD_FEEDBACK_SCORE("threshold:feedback_score"),
    THRESHOLD_COST("threshold:cost"),
    THRESHOLD_LATENCY("threshold:latency"),
    THRESHOLD_ERRORS("threshold:errors");

    @JsonValue
    private final String value;

    @JsonCreator
    public static AlertTriggerConfigType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown Alert Trigger Config Type '%s'".formatted(value)));
    }
}
