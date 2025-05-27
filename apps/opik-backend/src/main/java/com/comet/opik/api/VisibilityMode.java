package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
* An enumeration representing different visibility modes.
* <p>
* This enum is intended to flag to the client that the particular trace was auto-generated and has little to no value.
* It will most likely be hidden or displayed without relevance in the UI.
* </p>
**/
@Getter
@RequiredArgsConstructor
public enum VisibilityMode {

    DEFAULT("default"),
    HIDDEN("hidden"),
    ;

    @JsonValue
    private final String value;

    public static Optional<VisibilityMode> fromString(String value) {
        return Arrays.stream(VisibilityMode.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst();
    }
}
