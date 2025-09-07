package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AnnotationQueueScope {
    TRACE("trace"),
    THREAD("thread");

    private final String value;

    AnnotationQueueScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AnnotationQueueScope fromValue(String value) {
        for (AnnotationQueueScope scope : AnnotationQueueScope.values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Invalid annotation queue scope: " + value);
    }
}
