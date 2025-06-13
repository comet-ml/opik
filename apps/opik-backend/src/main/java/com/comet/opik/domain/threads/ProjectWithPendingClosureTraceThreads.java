package com.comet.opik.domain.threads;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record ProjectWithPendingClosureTraceThreads(String workspaceId, UUID projectId) {
}
