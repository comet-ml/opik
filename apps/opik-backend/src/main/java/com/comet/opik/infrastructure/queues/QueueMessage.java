package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.uuid.Generators;
import lombok.Builder;

import java.time.Instant;

/**
 * Represents a complete RQ message with metadata.
 *
 * This combines:
 * - The job data (func, args, kwargs) that will be serialized to plain JSON
 * - RQ metadata (id, timestamps, status, etc.) stored in the HASH
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder(toBuilder = true)
public record QueueMessage(
        // RQ metadata (stored directly in Redis HASH)
        String id,
        String createdAt,
        String enqueuedAt,
        JobStatus status,
        String origin,
        long timeoutInSec,
        String description,
        // Additional metadata
        String createdBy,
        String updatedBy,
        String updatedAt) {

    /**
     * Builder customization to inject defaults
     */
    public static class QueueMessageBuilder {
        QueueMessageBuilder() {
            Instant now = Instant.now();
            this.id = Generators.timeBasedEpoch().generate().toString();
            this.createdAt = now.toString();
            this.enqueuedAt = now.toString();
            this.updatedAt = this.createdAt;
            this.status = JobStatus.QUEUED;
        }
    }
}
