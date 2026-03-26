package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum OpikVersion {
    VERSION_1("version_1"),
    VERSION_2("version_2");

    @JsonValue
    private final String value;

    @JsonCreator
    public static OpikVersion fromValue(String value) {
        return findByValue(value)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OpikVersion value: '%s'".formatted(value)));
    }

    public static Optional<OpikVersion> findByValue(String value) {
        return Arrays.stream(values())
                .filter(opikVersion -> opikVersion.value.equals(value))
                .findFirst();
    }
}
