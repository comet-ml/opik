package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum TraceThreadStatus {
    ACTIVE("active"),
    INACTIVE("inactive"),
    ;

    @JsonValue
    private final String value;

    public static Optional<TraceThreadStatus> fromValue(String value) {
        return Arrays.stream(values()).filter(v -> v.value.equals(value)).findFirst();
    }
}
