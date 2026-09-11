package com.comet.opik.api;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DashboardType implements HasValue {

    MULTI_PROJECT("multi_project"),
    EXPERIMENTS("experiments");

    @JsonValue
    private final String value;

    @JsonCreator
    public static DashboardType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dashboard type '%s'".formatted(value)));
    }
}
