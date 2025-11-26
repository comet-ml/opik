package com.comet.opik.domain;

/**
 * Statistics for dataset version comparison.
 *
 * @param itemsAdded Number of items added in the target version
 * @param itemsModified Number of items modified in the target version
 * @param itemsDeleted Number of items deleted in the target version
 * @param itemsUnchanged Number of items that remained unchanged
 */
public record DatasetVersionDiffStats(
        long itemsAdded,
        long itemsModified,
        long itemsDeleted,
        long itemsUnchanged) {
}
