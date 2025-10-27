package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;

public enum GroupByType {
    AUTOMATIC("automatic"),
    MANUAL("manual");

    private final String value;

    GroupByType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
