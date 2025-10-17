package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

/**
 * Represents the Redis HASH structure for an RQ job.
 *
 * This is the final format stored in Redis with all metadata fields
 * and the plain JSON data field.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder
record RqJobHash(
        String id,
        String createdAt,
        String enqueuedAt,
        JobStatus status,
        String origin,
        long timeout,
        String description,
        String data) {
}
