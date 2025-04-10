package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GuardrailResult {
    PASSED("passed"),
    FAILED("failed"),
    ;

    @JsonValue
    private final String result;

    public static GuardrailResult fromString(String result) {
        for (GuardrailResult value : GuardrailResult.values()) {
            if (value.getResult().equalsIgnoreCase(result)) {
                return value;
            }
        }
        return null;
    }
}
