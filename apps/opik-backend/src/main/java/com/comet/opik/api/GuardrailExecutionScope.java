package com.comet.opik.api;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum GuardrailExecutionScope implements HasValue {

    ON_REQUEST("on_request"),
    GLOBAL("global");

    @JsonValue
    private final String value;

    @JsonCreator
    public static GuardrailExecutionScope fromString(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown guardrail execution scope '%s'".formatted(value)));
    }
}
