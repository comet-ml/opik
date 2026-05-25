package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record EligiblePromptWorkspace(@NonNull String workspaceId, long promptsCount) {
}
