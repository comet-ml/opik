package com.comet.opik.domain.threads;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a model for trace thread IDs.
 * This model is used to store information about trace threads in the database, so that consistency and uniqueness are guaranteed.
 *
 * @param id The unique identifier for the trace thread it will also be referenced as trace thread id.
 * @param projectId The unique identifier for the project associated with the trace thread.
 * @param threadId The unique identifier for the thread, this is an external id provided by the user. This is different from the id field, which is the internal id used by the system.
 * @param createdAt The timestamp when the trace thread was
 * @param createdBy The username responsible by creating the trace thread.
 */
@Builder(toBuilder = true)
public record TraceThreadIdModel(UUID id, UUID projectId, String threadId, Instant createdAt, String createdBy) {
}
