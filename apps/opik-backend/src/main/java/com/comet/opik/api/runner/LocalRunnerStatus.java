package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LocalRunnerStatus {

    PAIRING("pairing"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected");

    @JsonValue
    private final String value;
}
