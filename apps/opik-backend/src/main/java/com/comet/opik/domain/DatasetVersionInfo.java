package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

/**
 * Record to hold dataset version information for batch processing.
 * Includes workspace_id, dataset_id, and version_id to optimize ClickHouse queries
 * according to the dataset_item_versions table's ordering key:
 * (workspace_id, dataset_id, dataset_version_id, id).
 */
@Builder(toBuilder = true)
public record DatasetVersionInfo(String workspaceId, UUID datasetId, UUID versionId) {
}
