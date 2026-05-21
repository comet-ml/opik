package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

// distinctProjectCount == 1 → certain, > 1 → ambiguous. No-inference orphans are absent from
// the result; the service detects them by diffing against MySQL's orphan list.
@Builder(toBuilder = true)
public record DatasetProjectMapping(@NonNull UUID datasetId, @NonNull UUID projectId, long distinctProjectCount) {
}
