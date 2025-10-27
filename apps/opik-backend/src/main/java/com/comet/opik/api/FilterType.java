package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;

public enum FilterType {
    TRACE("trace"),
    THREAD("thread");

    private final String value;

    FilterType(String value) {
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
