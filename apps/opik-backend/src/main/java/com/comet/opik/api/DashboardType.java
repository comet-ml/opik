package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DashboardType {
    PREBUILT("prebuilt"),
    CUSTOM("custom");

    private final String value;

    DashboardType(String value) {
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
