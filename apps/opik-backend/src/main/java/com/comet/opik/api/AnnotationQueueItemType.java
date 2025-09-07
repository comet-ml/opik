package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AnnotationQueueItemType {
    TRACE("trace"),
    THREAD("thread");

    private final String value;

    AnnotationQueueItemType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AnnotationQueueItemType fromValue(String value) {
        for (AnnotationQueueItemType type : AnnotationQueueItemType.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid annotation queue item type: " + value);
    }

    public static AnnotationQueueItemType fromScope(AnnotationQueueScope scope) {
        return switch (scope) {
            case TRACE -> TRACE;
            case THREAD -> THREAD;
        };
    }
}
