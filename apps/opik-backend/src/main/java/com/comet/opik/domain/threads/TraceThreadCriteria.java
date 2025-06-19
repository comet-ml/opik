package com.comet.opik.domain.threads;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

import com.comet.opik.api.TraceThreadStatus;

@Builder(toBuilder = true)
public record TraceThreadCriteria(
        List<UUID> ids,
        UUID projectId,
        List<String> threadIds,
        TraceThreadStatus status) {
}
