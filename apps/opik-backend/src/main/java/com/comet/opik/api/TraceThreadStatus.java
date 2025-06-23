package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum TraceThreadStatus {
    ACTIVE("active"),
    INACTIVE("inactive"),
    ;

    @JsonValue
    private final String value;

    public static TraceThreadStatus fromValue(String value) {
        return Arrays.stream(values()).filter(v -> v.value.equals(value)).findFirst()
                .orElse(null);
    }
}
