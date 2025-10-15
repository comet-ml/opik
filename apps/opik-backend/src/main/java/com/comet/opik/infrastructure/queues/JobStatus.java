package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Job status enum for RQ messages.
 * These statuses match Python RQ's job lifecycle.
 */
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

    JobStatus(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    @JsonCreator
    public static JobStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.toString().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown job status '%s'".formatted(value)));
    }
}
