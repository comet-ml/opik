package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum EvalTriggerScope {

    PRODUCTION("production"),
    EXPERIMENT("experiment"),
    BOTH("both");

    @JsonValue
    private final String value;

    @JsonCreator
    public static EvalTriggerScope fromString(String value) {
        return Arrays.stream(values())
                .filter(v -> v.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown eval trigger scope: " + value));
    }
}
