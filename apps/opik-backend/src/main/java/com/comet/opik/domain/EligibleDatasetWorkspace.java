package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record EligibleDatasetWorkspace(@NonNull String workspaceId, long datasetsCount) {
}
