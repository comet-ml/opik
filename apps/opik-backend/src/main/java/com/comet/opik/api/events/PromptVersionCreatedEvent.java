package com.comet.opik.api.events;

import lombok.Builder;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record PromptVersionCreatedEvent(
        @NonNull String workspaceId,
        @NonNull UUID promptId,
        @NonNull String commit,
        @NonNull String userName,
        Set<UUID> excludeProjectIds) {
}
