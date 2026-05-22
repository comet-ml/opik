package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record EligibleOptimizationWorkspace(@NonNull String workspaceId, long optimizationsCount) {
}
