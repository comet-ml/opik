package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record DatasetCriteria(String name, boolean withExperimentsOnly, UUID promptId, boolean withOptimizationsOnly) {
}
