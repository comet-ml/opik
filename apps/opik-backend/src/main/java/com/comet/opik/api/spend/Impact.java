package com.comet.opik.api.spend;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum Impact {

    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    @JsonValue
    private final String value;

    @JsonCreator
    public static Impact fromString(String value) {
        return Arrays.stream(values())
                .filter(impact -> impact.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown impact '%s'".formatted(value)));
    }
}
