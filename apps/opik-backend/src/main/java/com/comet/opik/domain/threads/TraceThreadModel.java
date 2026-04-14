package com.comet.opik.domain.threads;

import com.comet.opik.api.Source;
import com.comet.opik.api.TraceThreadStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a model for trace thread.
 * This model is used to make threads first-class citizens in the system, allow us to track their status, and manage their lifecycle.
 *
 * @param id The unique identifier for the trace thread it will also be referenced as trace thread id.
 * @param threadId The unique identifier for the thread, this is an external id provided by the user.
 * @param source The origin source of the triggering traces. Persisted to ClickHouse; defaults to
 *               'unknown' for pre-existing rows. {@link Source#isLoggingSource(Source)} treats null
 *               (mapped from 'unknown') as SDK for backward compatibility.
 */
@Builder(toBuilder = true)
public record TraceThreadModel(
        UUID projectId,
        String threadId,
        UUID id,
        TraceThreadStatus status,
        String createdBy,
        String lastUpdatedBy,
        Instant createdAt,
        Instant lastUpdatedAt,
        Set<String> tags,
        Map<UUID, Boolean> sampling,
        Instant scoredAt,
        Instant startTime,
        Instant endTime,
        Double duration,
        Map<String, Integer> feedbackScores,
        String firstMessage,
        String lastMessage,
        Long numberOfMessages,
        Source source) {

    public boolean isInactive() {
        return status == TraceThreadStatus.INACTIVE;
    }
}
