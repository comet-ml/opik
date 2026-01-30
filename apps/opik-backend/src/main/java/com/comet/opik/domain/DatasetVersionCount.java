package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Record to hold dataset version count information.
 * Used for counting versions per dataset in batch operations.
 */
@Builder(toBuilder = true)
public record DatasetVersionCount(@NonNull UUID datasetId, long versionCount) {
}
