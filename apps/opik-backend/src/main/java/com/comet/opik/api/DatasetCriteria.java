package com.comet.opik.api;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetCriteria(String name, boolean withExperimentsOnly, UUID promptId, boolean withOptimizationsOnly) {
}
