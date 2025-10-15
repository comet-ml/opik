package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a complete RQ message with metadata.
 *
 * This combines:
 * - The job data (func, args, kwargs) that will be compressed
 * - RQ metadata (id, timestamps, status, etc.) stored in the HASH
 */
@Builder(toBuilder = true)
public record QueueMessage(
        // RQ metadata (stored directly in Redis HASH)
        @JsonProperty("id") String id,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("enqueued_at") String enqueuedAt,
        @JsonProperty("status") JobStatus status,
        @JsonProperty("origin") String origin,
        @JsonProperty("timeout") long timeoutInSec,
        @JsonProperty("description") String description) {

    /**
     * Builder customization to inject defaults
     */
    public static class QueueMessageBuilder {
        QueueMessageBuilder() {
            Instant now = Instant.now();
            this.id = UUID.randomUUID().toString();
            this.createdAt = now.toString();
            this.enqueuedAt = now.toString();
            this.status = JobStatus.QUEUED;
        }
    }
}
