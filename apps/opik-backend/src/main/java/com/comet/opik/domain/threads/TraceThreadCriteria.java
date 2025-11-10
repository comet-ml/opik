package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThreadStatus;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record TraceThreadCriteria(
        List<UUID> ids,
        UUID projectId,
        Set<String> threadIds,
        TraceThreadStatus status,
        boolean scoredAtEmpty,
        UUID uuidFromTime,
        UUID uuidToTime) {
}
