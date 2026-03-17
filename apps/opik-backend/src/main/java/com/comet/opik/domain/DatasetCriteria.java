package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetCriteria(String name, boolean withExperimentsOnly, UUID promptId, UUID projectId,
        boolean withOptimizationsOnly, List<? extends Filter> filters) {
}
