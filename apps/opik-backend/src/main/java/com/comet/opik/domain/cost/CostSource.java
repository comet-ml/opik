package com.comet.opik.domain.cost;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum CostSource implements HasValue {
    ORGANIZATION("organization"),
    BUILT_IN("built_in"),
    METADATA("metadata"),
    NONE("none");

    @JsonValue
    private final String value;

    @JsonCreator
    public static CostSource fromString(String value) {
        return Arrays.stream(values())
                .filter(source -> source.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown cost source '%s'".formatted(value)));
    }
}
