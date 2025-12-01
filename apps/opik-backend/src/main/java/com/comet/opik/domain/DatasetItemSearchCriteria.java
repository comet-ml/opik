package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetItemSearchCriteria(
        @NonNull UUID datasetId,
        @NonNull Set<UUID> experimentIds,
        @NonNull EntityType entityType,
        List<? extends Filter> filters,
        List<SortingField> sortingFields,
        String search,
        boolean truncate,
        String versionHashOrTag) {
}
