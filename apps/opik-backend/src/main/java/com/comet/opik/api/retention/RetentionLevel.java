package com.comet.opik.api.retention;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum RetentionLevel implements HasValue {

    ORGANIZATION("organization"),
    WORKSPACE("workspace"),
    PROJECT("project");

    @JsonValue
    private final String value;

    @JsonCreator
    public static RetentionLevel fromString(String value) {
        return Arrays.stream(values())
                .filter(level -> level.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown retention level '%s'".formatted(value)));
    }
}
