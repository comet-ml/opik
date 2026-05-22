package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * {@code (dataset_id, project_id)} pair returned by
 * {@link DatasetDAO#findProjectIdsByDatasetIds}. The query only emits rows where
 * {@code project_id IS NOT NULL}, so both fields are non-null.
 */
@Builder(toBuilder = true)
public record DatasetProjectIdRow(@NonNull UUID id, @NonNull UUID projectId) {
}
