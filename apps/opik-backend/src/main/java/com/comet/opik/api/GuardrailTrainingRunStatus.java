package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** Spelled in lowercase in the API, like the other guardrail enums; MySQL stores the constant's name. */
@Getter
@RequiredArgsConstructor
public enum GuardrailTrainingRunStatus {

    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    @JsonValue
    private final String value;

    @JsonCreator
    public static GuardrailTrainingRunStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown guardrail training run status '%s'".formatted(value)));
    }
}
