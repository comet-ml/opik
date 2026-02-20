package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity data models for experiments.
 * These records represent the core experiment entities and their relationships.
 */
public class ExperimentEntityData {

    /**
     * Complete experiment data including metadata and scores.
     *
     * @param workspaceId       The workspace ID
     * @param id                The experiment ID
     * @param datasetId         The dataset ID
     * @param name              The experiment name
     * @param createdAt         Creation timestamp
     * @param lastUpdatedAt     Last update timestamp
     * @param createdBy         Creator user
     * @param lastUpdatedBy     Last updater user
     * @param metadata          JSON metadata
     * @param promptVersions    Map of prompt IDs to version IDs
     * @param optimizationId    Optimization ID
     * @param datasetVersionId  Dataset version ID
     * @param tags              List of tags
     * @param type              Experiment type
     * @param status            Experiment status
     * @param experimentScores  Map of experiment score names to values
     */
    @Builder
    public record ExperimentData(
            String workspaceId,
            UUID id,
            UUID datasetId,
            String name,
            String createdAt,
            String lastUpdatedAt,
            String createdBy,
            String lastUpdatedBy,
            String metadata,
            Map<UUID, List<UUID>> promptVersions,
            String optimizationId,
            String datasetVersionId,
            List<String> tags,
            String type,
            String status,
            Map<String, BigDecimal> experimentScores) {
    }

    /**
     * Experiment item linking a dataset item to a trace.
     *
     * @param id            The experiment item ID
     * @param experimentId  The experiment ID
     * @param traceId       The trace ID
     * @param datasetItemId The dataset item ID
     */
    @Builder
    public record ExperimentItemData(
            UUID id,
            UUID experimentId,
            UUID traceId,
            UUID datasetItemId) {
    }
}
