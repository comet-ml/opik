package com.comet.opik.api.events;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record ProjectWithPendingClosureTraceThreads(String workspaceId, UUID projectId) {
}
