package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Identifier pair for a workspace-scoped orphan optimization. The {@code datasetId} is carried so
 * the service can run Path B (cross-DB dataset lookup) for optimizations that Path A (experiments)
 * does not classify, without an extra ClickHouse round-trip.
 */
@Builder(toBuilder = true)
public record OrphanOptimization(@NonNull UUID optimizationId, @NonNull UUID datasetId) {
}
