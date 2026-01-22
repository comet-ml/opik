package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

/**
 * Record to hold item count results for a specific dataset version.
 * Used when calculating items_total for dataset versions.
 */
@Builder(toBuilder = true)
public record DatasetVersionItemsCount(UUID versionId, long count) {
}
