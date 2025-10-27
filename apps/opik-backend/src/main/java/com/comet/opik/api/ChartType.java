package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChartType {
    LINE("line"),
    BAR("bar"),
    AREA("area"),
    STACKED_BAR("stacked_bar"),
    STACKED_AREA("stacked_area"),
    PIE("pie"),
    DONUT("donut");

    private final String value;

    ChartType(String value) {
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
