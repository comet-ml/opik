package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record EligibleWorkspace(@NonNull String workspaceId, long experimentsCount) {
}
