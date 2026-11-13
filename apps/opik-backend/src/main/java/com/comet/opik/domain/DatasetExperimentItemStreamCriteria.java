package com.comet.opik.domain;

import com.comet.opik.api.filter.DatasetItemFilter;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetExperimentItemStreamCriteria(
        @NonNull UUID datasetId,
        @NonNull Set<UUID> experimentIds,
        // Version that experiments created before dataset versioning resolve to
        @NonNull String fallbackVersionId,
        @NonNull List<DatasetItemFilter> filters,
        int limit,
        UUID lastRetrievedExperimentId,
        UUID lastRetrievedId,
        boolean truncate) {
}
