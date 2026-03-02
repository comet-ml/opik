package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.Dataset;
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
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.ExperimentResponseBuilder;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;
import static com.comet.opik.api.grouping.GroupingFactory.PROJECT_ID;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.BatchResult;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ExperimentAggregatesService {

    private final @NonNull ExperimentAggregatesDAO experimentAggregatesDAO;
    private final @NonNull OpikConfiguration config;
    private final @NonNull ExperimentResponseBuilder responseBuilder;
    private final @NonNull DatasetService datasetService;
    private final @NonNull ProjectService projectService;
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
        return experimentAggregatesDAO.countTotal(criteria);
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
        Set<UUID> datasetIds = extractUuidsFromGroupValues(allGroupValues, groups, DATASET_ID);
        Set<UUID> projectIds = extractUuidsFromGroupValues(allGroupValues, groups, PROJECT_ID);

        Mono<Map<UUID, Dataset>> datasetsMono = loadEntityMap(
                () -> datasetService.findByIds(datasetIds, workspaceId),
                this::getDatasetMap);

        Mono<Map<UUID, Project>> projectsMono = loadEntityMap(
                () -> projectService.findByIds(workspaceId, projectIds),
                this::getProjectMap);

        return Mono.zip(datasetsMono, projectsMono)
                .map(tuple -> ExperimentGroupEnrichInfoHolder.builder()
                        .datasetMap(tuple.getT1())
                        .projectMap(tuple.getT2())
                        .build());
    }

    private <T, R> Mono<Map<UUID, R>> loadEntityMap(
            Callable<List<T>> serviceCall,
            Function<List<T>, Map<UUID, R>> mapper) {
        return Mono.fromCallable(serviceCall)
                .subscribeOn(Schedulers.boundedElastic())
                .map(mapper);
    }

    private Set<UUID> extractUuidsFromGroupValues(List<List<String>> allGroupValues, List<GroupBy> groups,
            String targetField) {
        int nestingIdx = groups.stream()
                .filter(g -> targetField.equals(g.field()))
                .findFirst()
                .map(groups::indexOf)
                .orElse(-1);

        if (nestingIdx == -1) {
            return Set.of();
        }

        return allGroupValues.stream()
                .map(groupValues -> groupValues.get(nestingIdx))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .filter(s -> !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(s))
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    private Optional<UUID> resolveVersionIdForCriteria(DatasetItemSearchCriteria criteria, String workspaceId) {
        if (StringUtils.isNotBlank(criteria.versionHashOrTag())) {
            return Optional.of(
                    versionService.resolveVersionId(workspaceId, criteria.datasetId(), criteria.versionHashOrTag()));
        }

        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(criteria.datasetId(), workspaceId);
        if (latestVersion.isEmpty()) {
            log.warn("No versions found for dataset: '{}', workspace: '{}'", criteria.datasetId(), workspaceId);
        }
        return latestVersion.map(DatasetVersion::id);
    }

    private Map<UUID, Dataset> getDatasetMap(List<Dataset> datasets) {
        return datasets.stream()
                .collect(Collectors.toMap(Dataset::id, Function.identity()));
    }

    private Map<UUID, Project> getProjectMap(List<Project> projects) {
        return projects.stream()
                .collect(Collectors.toMap(Project::id, Function.identity()));
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
            final UUID versionId;

            Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);
            if (latestVersion.isEmpty()) {
                log.warn("No versions found for dataset: '{}', workspace: '{}'", datasetId, workspaceId);
                return Mono.just(new ProjectStats(List.of()));
            } else {
                versionId = latestVersion.get().id();
            }

            return experimentAggregatesDAO.getExperimentItemsStatsFromAggregates(datasetId, versionId, experimentIds,
                    filters);
        });
    }
}
