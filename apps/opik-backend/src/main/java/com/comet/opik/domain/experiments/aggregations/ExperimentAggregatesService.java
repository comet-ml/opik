package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ExperimentAggregatesService {

    private final @NonNull ExperimentAggregatesDAO experimentAggregatesDAO;
    private final @NonNull OpikConfiguration config;

    /**
     * Populate experiment aggregations and experiment item aggregations in batches with default batch size from config.
     *
     * @param experimentId the experiment ID
     * @return Mono that completes when all aggregations are populated
     */
    public Mono<Void> populateAggregations(@NonNull UUID experimentId) {
        return populateAggregations(experimentId, config.getExperimentAggregates().getBatchSize());
    }

    /**
     * Populate experiment aggregations and experiment item aggregations in batches with configurable batch size.
     *
     * @param experimentId the experiment ID
     * @param batchSize the number of experiment items to process per batch
     * @return Mono that completes when all aggregations are populated
     */
    public Mono<Void> populateAggregations(@NonNull UUID experimentId, int batchSize) {

        // First, populate experiment-level aggregates
        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get("workspaceId");

            log.info("Starting aggregation population for experiment: '{}' in workspace: '{}', batchSize: '{}'",
                    experimentId, workspaceId, batchSize);

            return experimentAggregatesDAO.populateExperimentAggregate(experimentId)
                    .doOnSuccess(v -> log.info(
                            "Experiment-level aggregates populated for experiment: '{}'", experimentId))
                    .then(Mono.defer(() ->
            // Then, populate experiment item aggregates in batches using cursor pagination
            populateExperimentItemsInBatches(experimentId, null, 0L, batchSize)))
                    .doOnSuccess(v -> log.info(
                            "All aggregations populated successfully for experiment: '{}' in workspace: '{}'",
                            experimentId, workspaceId))
                    .doOnError(error -> log.error(
                            "Failed to populate aggregations for experiment: '{}' in workspace: '{}'",
                            experimentId, workspaceId, error));
        });
    }

    /**
     * Recursively populate experiment item aggregates in batches using cursor pagination.
     *
     * @param experimentId the experiment ID
     * @param cursor the current cursor (null for first batch)
     * @param totalProcessed the total number of items processed so far
     * @param batchSize the number of items to process per batch
     * @return Mono that completes when all batches are processed
     */
    private Mono<Void> populateExperimentItemsInBatches(
            UUID experimentId,
            UUID cursor,
            Long totalProcessed,
            int batchSize) {

        return experimentAggregatesDAO.populateExperimentItemAggregates(experimentId, cursor, batchSize)
                .flatMap(result -> {
                    var newTotal = totalProcessed + result.processedCount();

                    log.info("Processed '{}' experiment items in this batch, total: '{}' for experiment: '{}'",
                            result.processedCount(), newTotal, experimentId);

                    // If we have a cursor, there might be more items to process
                    if (result.lastCursor() != null) {
                        log.debug("Batch processed with cursor: '{}', continuing with next batch for experiment: '{}'",
                                result.lastCursor(), experimentId);

                        // Recursive call for next batch using the returned cursor
                        return populateExperimentItemsInBatches(experimentId, result.lastCursor(), newTotal, batchSize);
                    } else {
                        // No more items to process
                        log.info("Finished processing all experiment items. Total processed: '{}' for experiment: '{}'",
                                newTotal, experimentId);
                        return Mono.<Void>empty();
                    }
                });
    }

    /**
     * Query experiment_aggregates table directly and construct Experiment from stored aggregated values.
     * Used for testing and verification that aggregated data matches expected values.
     *
     * @param experimentId the experiment ID to query
     * @return Mono containing the Experiment constructed from aggregates table, or empty if not found
     */
    public Mono<Experiment> getExperimentFromAggregates(@NonNull UUID experimentId) {
        return experimentAggregatesDAO.getExperimentFromAggregates(experimentId);
    }

    public Mono<Long> countTotal(@NonNull ExperimentSearchCriteria criteria, @NonNull Set<UUID> targetProjectIds) {
        return experimentAggregatesDAO.countTotal(criteria, targetProjectIds);
    }
}
