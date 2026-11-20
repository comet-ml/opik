package com.comet.opik.domain.topics;

import lombok.NonNull;

import java.util.UUID;

public record TopicProjectKey(@NonNull String workspaceId, @NonNull UUID projectId) {
}
