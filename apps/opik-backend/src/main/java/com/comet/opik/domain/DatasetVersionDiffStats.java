package com.comet.opik.domain;

import lombok.Builder;

/**
 * Statistics for dataset version comparison.
 *
 * @param itemsAdded Number of items added in the target version
 * @param itemsModified Number of items modified in the target version
 * @param itemsDeleted Number of items deleted in the target version
 * @param itemsUnchanged Number of items that remained unchanged
 */
@Builder(toBuilder = true)
public record DatasetVersionDiffStats(
        int itemsAdded,
        int itemsModified,
        int itemsDeleted,
        int itemsUnchanged) {
}
