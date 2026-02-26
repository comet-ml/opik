package com.comet.opik.api.events;

import lombok.Builder;

import java.util.UUID;

@Builder
public record PromptVersionCreatedEvent(
        String workspaceId,
        UUID promptId,
        String commit,
        String userName) {
}
