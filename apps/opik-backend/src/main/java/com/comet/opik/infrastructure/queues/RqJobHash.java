package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Represents the Redis HASH structure for an RQ job.
 *
 * This is the final format stored in Redis with all metadata fields
 * and the compressed data field.
 */
@Builder
record RqJobHash(
        @JsonProperty("id") String id,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("enqueued_at") String enqueuedAt,
        @JsonProperty("status") JobStatus status,
        @JsonProperty("origin") String origin,
        @JsonProperty("timeout") long timeoutInSec,
        @JsonProperty("description") String description,
        @JsonProperty("data") String data) {
}
