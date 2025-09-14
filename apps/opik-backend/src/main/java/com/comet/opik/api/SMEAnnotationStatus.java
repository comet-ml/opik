package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SMEAnnotationStatus {
    PENDING("pending"),
    COMPLETED("completed"),
    SKIPPED("skipped");

    private final String value;

    SMEAnnotationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SMEAnnotationStatus fromValue(String value) {
        for (SMEAnnotationStatus status : SMEAnnotationStatus.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid SME annotation status: " + value);
    }
}
