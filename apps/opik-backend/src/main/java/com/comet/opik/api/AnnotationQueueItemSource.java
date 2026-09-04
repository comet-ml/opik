package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * How an item got into an annotation queue. Distinct from {@link Source}, which describes where a trace
 * itself came from — a trace knows nothing about annotation queues, so this lives on the queue item.
 */
@Getter
@RequiredArgsConstructor
public enum AnnotationQueueItemSource {

    MANUAL("manual"),
    AUTOMATED("automated");

    @JsonValue
    private final String value;

    @JsonCreator
    public static AnnotationQueueItemSource fromString(String value) {
        return Arrays.stream(values())
                .filter(source -> source.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown annotation queue item source '%s'".formatted(value)));
    }
}
