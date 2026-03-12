package com.comet.opik.api;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DashboardScope implements HasValue {

    WORKSPACE("workspace"),
    INSIGHTS("insights");

    @JsonValue
    private final String value;

    @JsonCreator
    public static DashboardScope fromString(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dashboard scope '%s'".formatted(value)));
    }
}
