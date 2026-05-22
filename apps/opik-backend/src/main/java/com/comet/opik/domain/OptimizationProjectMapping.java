package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

// distinctProjectCount == 1 → certain via experiments (Path A), > 1 → ambiguous. No-inference
// orphans are absent from the result; the service detects them by diffing against the workspace's
// orphan optimization set and falls back to Path B (dataset.project_id lookup).
@Builder(toBuilder = true)
public record OptimizationProjectMapping(@NonNull UUID optimizationId, @NonNull UUID projectId,
        long distinctProjectCount) {
}
