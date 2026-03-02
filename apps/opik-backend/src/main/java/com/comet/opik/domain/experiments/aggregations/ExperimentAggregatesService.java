package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.BatchResult;

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

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            log.info("Starting aggregation population for experiment: '{}' in workspace: '{}', batchSize: '{}'",
                    experimentId, workspaceId, batchSize);

            return experimentAggregatesDAO.populateExperimentAggregate(experimentId)
                    .doOnSuccess(v -> log.info(
                            "Experiment-level aggregates populated for experiment: '{}'", experimentId))
                    .then(Mono.defer(() ->
            // Then, populate experiment item aggregates in batches using cursor pagination
            populateExperimentItemsInBatches(experimentId, batchSize)))
                    .doOnSuccess(v -> log.info(
                            "All aggregations populated successfully for experiment: '{}' in workspace: '{}'",
                            experimentId, workspaceId))
                    .doOnError(error -> log.error(
                            "Failed to populate aggregations for experiment: '{}' in workspace: '{}'",
                            experimentId, workspaceId, error));
        });
    }

    /**
     * Populate experiment item aggregates in batches using cursor pagination with {@code Mono.expand()}.
     *
     * @param experimentId the experiment ID
     * @param batchSize the number of items to process per batch
     * @return Mono that completes when all batches are processed
     */
    private Mono<Void> populateExperimentItemsInBatches(UUID experimentId, int batchSize) {
        return experimentAggregatesDAO.populateExperimentItemAggregates(experimentId, null, batchSize)
                .expand(result -> {
                    log.info("Processed '{}' experiment items in this batch for experiment: '{}'",
                            result.processedCount(), experimentId);
                    if (result.lastCursor() == null) {
                        return Mono.empty();
                    }
                    log.debug("Batch processed with cursor: '{}', continuing with next batch for experiment: '{}'",
                            result.lastCursor(), experimentId);
                    return experimentAggregatesDAO.populateExperimentItemAggregates(experimentId, result.lastCursor(),
                            batchSize);
                })
                .map(BatchResult::processedCount)
                .reduce(0L, Long::sum)
                .doOnSuccess(total -> log.info(
                        "Finished processing all experiment items. Total processed: '{}' for experiment: '{}'",
                        total, experimentId))
                .then();
    }

    /**
     * Query experiment_aggregates table directly and construct Experiment from stored aggregated values.
     *
     * @param experimentId the experiment ID to query
     * @return Mono containing the Experiment constructed from aggregates table, or empty if not found
     */
    public Mono<Experiment> getExperimentFromAggregates(@NonNull UUID experimentId) {
        return experimentAggregatesDAO.getExperimentFromAggregates(experimentId);
    }

    public Mono<Long> countTotal(@NonNull ExperimentSearchCriteria criteria, @NonNull Set<UUID> targetProjectIds) {
        log.info("Counting experiments from aggregates by '{}'", criteria);
        return experimentAggregatesDAO.countTotal(criteria, targetProjectIds);
    }
}
