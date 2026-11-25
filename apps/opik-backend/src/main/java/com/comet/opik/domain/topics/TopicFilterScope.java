package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * What the SQL of a trace query filtered by topic needs: the project's live topic ids and the query guards of
 * {@code topics.filter}, {@code max_execution_time} in seconds and {@code max_memory_usage} in bytes.
 *
 * <p>{@link #toString()} prints the number of ids only: a trace search logs its criteria.
 */
@Builder(toBuilder = true)
public record TopicFilterScope(@NonNull List<UUID> liveIds, long maxExecutionSeconds, long maxMemoryUsage) {

    public TopicFilterScope {
        liveIds = List.copyOf(liveIds);
    }

    @Override
    public String toString() {
        return "TopicFilterScope[liveIds=%d]".formatted(liveIds.size());
    }
}
