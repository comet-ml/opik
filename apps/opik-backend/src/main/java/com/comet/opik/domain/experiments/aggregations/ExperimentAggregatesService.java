package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupEnrichInfoHolder;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.ExperimentGroupEnricher;
import com.comet.opik.domain.ExperimentResponseBuilder;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.BatchResult;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ExperimentAggregatesService {

    private final @NonNull ExperimentAggregatesDAO experimentAggregatesDAO;
    private final @NonNull OpikConfiguration config;
    private final @NonNull ExperimentResponseBuilder responseBuilder;
    private final @NonNull ExperimentGroupEnricher experimentGroupEnricher;
    private final @NonNull DatasetVersionService versionService;

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
    private Mono<Void> populateAggregations(@NonNull UUID experimentId, int batchSize) {

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

    public Mono<Long> countTotal(@NonNull ExperimentSearchCriteria criteria) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            log.info("Counting total experiments from aggregates for workspace: '{}'", workspaceId);
            return experimentAggregatesDAO.countTotal(criteria);
        });
    }

    /**
     * Count dataset items with experiment items from experiment_item_aggregates table.
     * This method replicates the contract of DatasetItemVersionDAO.countDatasetItemsWithExperimentItems()
     * using the aggregated data instead of joining multiple tables.
     *
     * @param criteria the dataset item search criteria
     * @return Mono containing the count of dataset items
     */
    public Mono<Long> countDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria) {
        log.info("Counting dataset items with experiment items from aggregates for dataset: '{}'",
                criteria.datasetId());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return resolveVersionIdForCriteria(criteria, workspaceId)
                    .map(versionId -> experimentAggregatesDAO
                            .countDatasetItemsWithExperimentItemsFromAggregates(criteria, versionId))
                    .orElse(Mono.just(0L));
        });
    }

    /**
     * Get dataset items with experiment items from experiment_item_aggregates table.
     * This method replicates the contract of DatasetItemVersionDAO.getDatasetItemsWithExperimentItems()
     * using the aggregated data instead of joining multiple tables.
     *
     * @param criteria the dataset item search criteria
     * @param page the page number
     * @param size the page size
     * @return Mono containing the dataset item page
     */
    public Mono<DatasetItem.DatasetItemPage> getDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria, int page, int size) {
        log.info("Getting dataset items with experiment items from aggregates for dataset: '{}'",
                criteria.datasetId());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return resolveVersionIdForCriteria(criteria, workspaceId)
                    .map(versionId -> experimentAggregatesDAO
                            .getDatasetItemsWithExperimentItemsFromAggregates(criteria, versionId, page, size))
                    .orElse(Mono.just(new DatasetItem.DatasetItemPage(List.of(), page, size, 0, Set.of(), List.of())));
        });
    }

    /**
     * Query experiment groups from experiment_aggregates table and return as ExperimentGroupResponse.
     * This method replicates the contract of ExperimentService.findGroups().
     *
     * @param criteria the experiment group criteria
     * @return Mono containing ExperimentGroupResponse with enriched data
     */
    public Mono<ExperimentGroupResponse> findGroups(@NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups from aggregates by criteria '{}'", criteria);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return experimentAggregatesDAO.findGroups(criteria)
                    .collectList()
                    .flatMap(groupItems -> {
                        var allGroupValues = groupItems.stream()
                                .map(ExperimentGroupItem::groupValues)
                                .toList();
                        return getEnrichInfoHolder(allGroupValues, criteria.groups(), workspaceId)
                                .map(enrichInfoHolder -> responseBuilder.buildGroupResponse(groupItems,
                                        enrichInfoHolder,
                                        criteria.groups()));
                    });
        });
    }

    /**
     * Query experiment groups aggregations from experiment_aggregates table and return as ExperimentGroupAggregationsResponse.
     * This method replicates the contract of ExperimentService.findGroupsAggregations().
     *
     * @param criteria the experiment group criteria
     * @return Mono containing ExperimentGroupAggregationsResponse with enriched data
     */
    public Mono<ExperimentGroupAggregationsResponse> findGroupsAggregations(
            @NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups aggregations from aggregates by criteria '{}'", criteria);

        return experimentAggregatesDAO.findGroupsAggregations(criteria)
                .collectList()
                .flatMap(groupItems -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    var allGroupValues = groupItems.stream()
                            .map(ExperimentGroupAggregationItem::groupValues)
                            .toList();
                    return getEnrichInfoHolder(allGroupValues, criteria.groups(), workspaceId)
                            .map(enrichInfoHolder -> responseBuilder.buildGroupAggregationsResponse(
                                    groupItems, enrichInfoHolder, criteria.groups()));
                }));
    }

    private Mono<ExperimentGroupEnrichInfoHolder> getEnrichInfoHolder(List<List<String>> allGroupValues,
            List<GroupBy> groups, String workspaceId) {
        return experimentGroupEnricher.getEnrichInfoHolder(allGroupValues, groups, workspaceId);
    }

    private Optional<UUID> resolveVersionIdForCriteria(DatasetItemSearchCriteria criteria, String workspaceId) {
        if (StringUtils.isNotBlank(criteria.versionHashOrTag())) {
            return Optional.of(
                    versionService.resolveVersionId(workspaceId, criteria.datasetId(), criteria.versionHashOrTag()));
        }

        return resolveLatestVersionId(criteria.datasetId(), workspaceId);
    }

    private Optional<UUID> resolveLatestVersionId(UUID datasetId, String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);
        if (latestVersion.isEmpty()) {
            log.warn("No versions found for dataset: '{}', workspace: '{}'", datasetId, workspaceId);
        }
        return latestVersion.map(DatasetVersion::id);
    }

    /**
     * Get experiment items stats from experiment_item_aggregates table.
     * This method replicates the contract of DatasetItemVersionDAO.getExperimentItemsStats()
     * using the aggregated data instead of joining multiple tables.
     *
     * @param datasetId the dataset ID
     * @param experimentIds the set of experiment IDs
     * @param filters the list of filters
     * @return Mono containing project stats
     */
    public Mono<ProjectStats> getExperimentItemsStatsFromAggregates(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("Getting experiment items stats from aggregates for dataset: '{}'", datasetId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return resolveLatestVersionId(datasetId, workspaceId)
                    .map(versionId -> experimentAggregatesDAO.getExperimentItemsStatsFromAggregates(datasetId,
                            versionId, experimentIds, filters))
                    .orElse(Mono.just(new ProjectStats(List.of())));
        });
    }
}
