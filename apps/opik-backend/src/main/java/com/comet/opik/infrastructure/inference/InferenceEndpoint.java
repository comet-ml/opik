package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InferenceEndpoint {
    DECIDE("decide"),
    GENERATE("generate");

    @JsonValue
    private final String value;
}
