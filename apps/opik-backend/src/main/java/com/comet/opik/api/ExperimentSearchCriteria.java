package com.comet.opik.api;

import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.EntityType;
import lombok.Builder;
import lombok.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record ExperimentSearchCriteria(String name, UUID datasetId, @NonNull EntityType entityType,
        boolean datasetDeleted, Collection<UUID> datasetIds, UUID promptId, List<SortingField> sortingFields,
        UUID optimizationId, Set<ExperimentType> types) {
}
