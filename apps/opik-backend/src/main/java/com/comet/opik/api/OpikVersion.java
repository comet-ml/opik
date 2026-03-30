package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
@Slf4j
public enum OpikVersion {
    VERSION_1("version_1"),
    VERSION_2("version_2");

    @JsonValue
    private final String value;

    /**
     * Handles unknown Authentication response values gracefully by logging a warning and returning null.
     * This allows us to avoid deployment coordination when changing values.
     * @param value the string value to deserialize, e.g. from JSON
     * @return the corresponding OpikVersion enum constant, or null if the value is unknown
     */
    @JsonCreator
    public static OpikVersion fromValue(String value) {
        return findByValue(value).orElseGet(() -> {
            log.warn("Unknown OpikVersion value: '{}'", value);
            return null;
        });
    }

    public static Optional<OpikVersion> findByValue(String value) {
        return Arrays.stream(values())
                .filter(opikVersion -> opikVersion.value.equalsIgnoreCase(value))
                .findFirst();
    }
}
