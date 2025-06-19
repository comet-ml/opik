package com.comet.opik.domain.threads;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Represents a model for trace thread.
 * This model is used to make threads first-class citizens in the system, allow us to track their status, and manage their lifecycle.
 *
 * @param id The unique identifier for the trace thread it will also be referenced as trace thread id.
 * @param threadId The unique identifier for the thread, this is an external id provided by the user.
 */
@Builder(toBuilder = true)
public record TraceThreadModel(
        UUID projectId,
        String threadId,
        UUID id,
        Status status,
        String createdBy,
        String lastUpdatedBy,
        Instant createdAt,
        Instant lastUpdatedAt) {

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        ACTIVE("active"),
        INACTIVE("inactive"),
        ;

        @JsonValue
        private final String value;

        public static Status fromValue(String value) {
            return Arrays.stream(values()).filter(v -> v.value.equals(value)).findFirst()
                    .orElse(null);
        }
    }
}
