package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * Job status enum for RQ messages.
 * These statuses match Python RQ's job lifecycle.
 */
@Getter
@RequiredArgsConstructor
public enum JobStatus {
    /**
     * Job has been queued but not yet started
     */
    QUEUED("queued"),

    /**
     * Job is currently being executed
     */
    STARTED("started"),

    /**
     * Job finished successfully
     */
    FINISHED("finished"),

    /**
     * Job failed during execution
     */
    FAILED("failed"),

    ;

    @JsonValue
    private final String value;

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static JobStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown job status '%s'".formatted(value)));
    }
}
