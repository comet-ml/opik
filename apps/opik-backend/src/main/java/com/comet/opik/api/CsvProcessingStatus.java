package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CsvProcessingStatus {

    READY("ready"),
    PROCESSING("processing"),
    FAILED("failed");

    @JsonValue
    private final String value;
}
