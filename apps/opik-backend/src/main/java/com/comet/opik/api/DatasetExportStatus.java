package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum DatasetExportStatus {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    @JsonValue
    private final String value;

    @Override
    public String toString() {
        return value;
    }
}
