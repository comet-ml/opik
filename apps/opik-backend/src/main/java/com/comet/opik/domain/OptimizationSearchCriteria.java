package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import lombok.Builder;
import lombok.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record OptimizationSearchCriteria(
        String name,
        UUID datasetId,
        @NonNull EntityType entityType,
        Boolean datasetDeleted,
        Collection<UUID> datasetIds,
        Boolean studioOnly,
        List<? extends Filter> filters) {
}
