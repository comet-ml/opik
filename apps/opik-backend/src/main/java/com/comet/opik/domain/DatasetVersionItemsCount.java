package com.comet.opik.domain;

import java.util.UUID;

/**
 * Record to hold item count results for a specific dataset version.
 * Used when calculating items_total for dataset versions.
 */
public record DatasetVersionItemsCount(UUID versionId, long count) {
}
