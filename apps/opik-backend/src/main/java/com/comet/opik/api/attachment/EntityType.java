package com.comet.opik.api.attachment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum EntityType {
    TRACE("trace"),
    SPAN("span");

    @JsonValue
    private final String value;

    @JsonCreator
    public static EntityType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entity type '%s'".formatted(value)));
    }
}
