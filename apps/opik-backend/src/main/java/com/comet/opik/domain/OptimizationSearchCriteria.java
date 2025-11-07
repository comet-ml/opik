package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.Collection;
import java.util.UUID;

@Builder(toBuilder = true)
public record OptimizationSearchCriteria(
        String name,
        UUID datasetId,
        @NonNull EntityType entityType,
        Boolean datasetDeleted,
        Collection<UUID> datasetIds,
        Boolean studioOnly) {
}
