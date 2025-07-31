package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.AggregationData;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupEnrichInfoHolder;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.GroupContentWithAggregations;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
public class ExperimentService {

    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ExperimentItemDAO experimentItemDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull NameGenerator nameGenerator;
    private final @NonNull EventBus eventBus;
    private final @NonNull PromptService promptService;
    private final @NonNull ExperimentSortingFactory sortingFactory;

    public static final String DELETED_DATASET = "__DELETED";

    @WithSpan
    public Mono<ExperimentPage> find(
            int page, int size, @NonNull ExperimentSearchCriteria experimentSearchCriteria) {
        log.info("Finding experiments by '{}', page '{}', size '{}'", experimentSearchCriteria, page, size);

        if (experimentSearchCriteria.datasetDeleted()) {
            return experimentDAO.findAllDatasetIds(DatasetCriteria.builder()
                    .promptId(experimentSearchCriteria.promptId())
                    .build())
                    .map(datasetIds -> datasetIds
                            .stream()
                            .map(DatasetEventInfoHolder::datasetId)
                            .collect(Collectors.toSet()))
                    .flatMap(datasetIds -> makeMonoContextAware((userName, workspaceId) -> {

                        if (datasetIds.isEmpty()) {
                            return Mono.just(ExperimentPage.empty(page, sortingFactory.getSortableFields()));
                        }

                        return getDeletedDatasetAndBuildCriteria(experimentSearchCriteria, datasetIds, workspaceId)
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(criteria -> {
                                    if (criteria.datasetIds().isEmpty()) {
                                        return Mono
                                                .just(ExperimentPage.empty(page, sortingFactory.getSortableFields()));
                                    }

                                    return fetchExperimentPage(page, size, criteria);
                                });
                    }));
        }

        return fetchExperimentPage(page, size, experimentSearchCriteria);
    }

    private Mono<ExperimentSearchCriteria> getDeletedDatasetAndBuildCriteria(
            ExperimentSearchCriteria experimentSearchCriteria, Set<UUID> datasetIds, String workspaceId) {
        return Mono.fromCallable(() -> {
            Set<UUID> existingDatasetIds = datasetService.exists(datasetIds, workspaceId);

            Set<UUID> deletedDatasetIds = datasetIds.stream()
                    .filter(datasetId -> !existingDatasetIds.contains(datasetId))
                    .collect(Collectors.toUnmodifiableSet());

            return experimentSearchCriteria.toBuilder()
                    .datasetIds(deletedDatasetIds)
                    .build();
        });
    }

    private Mono<ExperimentPage> fetchExperimentPage(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria) {
        return experimentDAO.find(page, size, experimentSearchCriteria)
                .flatMap(experimentPage -> enrichExperiments(experimentPage.content())
                        .map(experiments -> experimentPage.toBuilder().content(experiments).build()));
    }

    private Mono<List<Experiment>> enrichExperiments(List<Experiment> experiments) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            var ids = experiments.stream().map(Experiment::datasetId).collect(Collectors.toUnmodifiableSet());
            return Mono.zip(
                    promptService.getVersionsCommitByVersionsIds(getPromptVersionIds(experiments)),
                    Mono.fromCallable(() -> datasetService.findByIds(ids, workspaceId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(this::getDatasetMap))
                    .map(tuple -> experiments.stream()
                            .map(experiment -> experiment.toBuilder()
                                    .datasetName(Optional
                                            .ofNullable(tuple.getT2().get(experiment.datasetId()))
                                            .map(Dataset::name)
                                            .orElse(null))
                                    .promptVersion(buildPromptVersion(tuple.getT1(), experiment))
                                    .promptVersions(buildPromptVersions(tuple.getT1(), experiment))
                                    .build())
                            .toList());
        });
    }

    private Map<UUID, Dataset> getDatasetMap(List<Dataset> datasets) {
        return datasets.stream().collect(Collectors.toMap(Dataset::id, Function.identity()));
    }

    private List<PromptVersionLink> buildPromptVersions(Map<UUID, String> promptVersions, Experiment experiment) {
        if (hasPromptVersionLinks(experiment)) {

            Stream<PromptVersionLink> promptVersionLinks = Optional.ofNullable(experiment.promptVersions())
                    .orElseGet(List::of)
                    .stream()
                    .map(version -> new PromptVersionLink(
                            version.id(),
                            promptVersions.get(version.id()),
                            version.promptId()));

            Stream<PromptVersionLink> promptVersionLink = Optional.ofNullable(experiment.promptVersion())
                    .stream()
                    .map(version -> new PromptVersionLink(
                            version.id(),
                            promptVersions.get(version.id()),
                            version.promptId()));

            List<PromptVersionLink> versionLinks = Stream.concat(promptVersionLinks, promptVersionLink).distinct()
                    .toList();

            return versionLinks.isEmpty() ? null : versionLinks;
        }

        return null;
    }

    private PromptVersionLink buildPromptVersion(Map<UUID, String> promptVersions, Experiment experiment) {
        if (hasPromptVersionLinks(experiment)) {

            PromptVersionLink versionLink = experiment.promptVersion();

            if (versionLink != null) {
                return new PromptVersionLink(
                        versionLink.id(),
                        promptVersions.get(versionLink.id()),
                        versionLink.promptId());
            } else {
                return Optional.ofNullable(experiment.promptVersions())
                        .stream()
                        .flatMap(List::stream)
                        .findFirst()
                        .map(version -> new PromptVersionLink(
                                version.id(),
                                promptVersions.get(version.id()),
                                version.promptId()))
                        .orElse(null);
            }
        }

        return null;
    }

    private Set<UUID> getPromptVersionIds(List<Experiment> experiments) {
        return experiments.stream()
                .flatMap(experiment -> {

                    // to be deprecated soon
                    var promptVersion = Optional.ofNullable(experiment.promptVersion())
                            .map(PromptVersionLink::id)
                            .stream();

                    var promptVersions = Optional.ofNullable(experiment.promptVersions())
                            .stream()
                            .flatMap(List::stream)
                            .map(PromptVersionLink::id);

                    return Stream.concat(promptVersion, promptVersions).distinct();
                })
                .collect(Collectors.toSet());
    }

    public Flux<Experiment> findByName(String name) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "Argument 'name' must not be blank");
        log.info("Finding experiments by name '{}'", name);
        return experimentDAO.findByName(name);
    }

    @WithSpan
    public Mono<ExperimentGroupResponse> findGroups(@NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups by criteria '{}'", criteria);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return experimentDAO.findGroups(criteria)
                    .collectList()
                    .flatMap(groupItems -> {

                        // fetch datasets using the IDs
                        return getEnrichInfoHolder(groupItems, criteria.groups(), workspaceId)
                                .map(enrichInfoHolder -> buildGroupResponse(groupItems, enrichInfoHolder,
                                        criteria.groups()));
                    });
        });
    }

    @WithSpan
    public Mono<ExperimentGroupAggregationsResponse> findGroupsAggregations(@NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups aggregations by criteria '{}'", criteria);

        return experimentDAO.findGroupsAggregations(criteria)
                .collectList()
                .map(this::buildGroupAggregationsResponse);
    }

    private Mono<ExperimentGroupEnrichInfoHolder> getEnrichInfoHolder(List<ExperimentGroupItem> groupItems,
            List<GroupBy> groups, String workspaceId) {
        // Check if we group by dataset, and if yes, get nesting level
        int nestingIdx = groups.stream().filter(g -> DATASET_ID.equals(g.field()))
                .findFirst()
                .map(groups::indexOf)
                .orElse(-1);

        // extract IDs from groupItems
        Set<UUID> datasetIds = nestingIdx == -1
                ? Set.of()
                : groupItems.stream()
                        .map(experimentGroupItem -> experimentGroupItem.groupValues().get(nestingIdx))
                        .filter(Objects::nonNull)
                        .map(UUID::fromString)
                        .collect(Collectors.toSet());

        return Mono.fromCallable(() -> datasetService.findByIds(datasetIds, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::getDatasetMap)
                .map(datasetMap -> ExperimentGroupEnrichInfoHolder.builder()
                        .datasetMap(datasetMap)
                        .build());
    }

    private ExperimentGroupResponse buildGroupResponse(List<ExperimentGroupItem> groupItems,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder, List<GroupBy> groups) {
        var contentMap = new HashMap<String, ExperimentGroupResponse.GroupContent>();

        for (ExperimentGroupItem item : groupItems) {
            buildNestedGroups(contentMap, item.groupValues(), 0, enrichInfoHolder, groups);
        }

        return ExperimentGroupResponse.builder()
                .content(contentMap)
                .build();
    }

    private void buildNestedGroups(Map<String, ExperimentGroupResponse.GroupContent> parentLevel,
            List<String> groupValues, int depth, ExperimentGroupEnrichInfoHolder enrichInfoHolder,
            List<GroupBy> groups) {
        if (depth >= groupValues.size()) {
            return;
        }

        String groupingValue = groupValues.get(depth);
        if (groupingValue == null) {
            return;
        }

        ExperimentGroupResponse.GroupContent currentLevel = parentLevel.computeIfAbsent(
                groupingValue,
                // We have to enrich with the dataset name if it's for dataset
                key -> buildGroupNode(
                        key,
                        enrichInfoHolder,
                        groups.get(depth)));

        // Recursively build nested groups
        buildNestedGroups(currentLevel.groups(), groupValues, depth + 1, enrichInfoHolder, groups);
    }

    private ExperimentGroupResponse.GroupContent buildGroupNode(String groupingValue,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder, GroupBy group) {
        return switch (group.field()) {
            case DATASET_ID ->
                ExperimentGroupResponse.GroupContent.builder()
                        .label(Optional.ofNullable(enrichInfoHolder.datasetMap().get(UUID.fromString(groupingValue)))
                                .map(Dataset::name)
                                .orElse(DELETED_DATASET))
                        .groups(new HashMap<>())
                        .build();

            default -> ExperimentGroupResponse.GroupContent.builder()
                    .groups(new HashMap<>())
                    .build();
        };
    }

    private ExperimentGroupAggregationsResponse buildGroupAggregationsResponse(
            List<ExperimentGroupAggregationItem> groupItems) {
        var contentMap = new HashMap<String, GroupContentWithAggregations>();

        for (ExperimentGroupAggregationItem item : groupItems) {
            buildNestedGroupsWithAggregations(contentMap, item, 0);
        }

        // Calculate recursive aggregations for parent levels
        var updatedContentMap = new HashMap<String, GroupContentWithAggregations>();
        for (Map.Entry<String, GroupContentWithAggregations> entry : contentMap.entrySet()) {
            updatedContentMap.put(entry.getKey(), calculateRecursiveAggregations(entry.getValue()));
        }

        return ExperimentGroupAggregationsResponse.builder()
                .content(updatedContentMap)
                .build();
    }

    private void buildNestedGroupsWithAggregations(Map<String, GroupContentWithAggregations> parentLevel,
            ExperimentGroupAggregationItem item, int depth) {
        if (depth >= item.groupValues().size()) {
            return;
        }

        String groupingValue = item.groupValues().get(depth);
        if (groupingValue == null) {
            return;
        }

        GroupContentWithAggregations currentLevel = parentLevel.computeIfAbsent(
                groupingValue,
                key -> {
                    // For leaf nodes (last level), include actual aggregation data
                    if (depth == item.groupValues().size() - 1) {
                        return GroupContentWithAggregations.builder()
                                .aggregations(buildAggregationData(item))
                                .groups(new HashMap<>())
                                .build();
                    } else {
                        // For intermediate nodes, initialize with empty aggregations
                        return GroupContentWithAggregations.builder()
                                .aggregations(AggregationData.builder()
                                        .experimentCount(0L)
                                        .traceCount(0L)
                                        .totalEstimatedCost(BigDecimal.ZERO)
                                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                                        .duration(null)
                                        .feedbackScores(List.of())
                                        .build())
                                .groups(new HashMap<>())
                                .build();
                    }
                });

        // Recursively build nested groups
        buildNestedGroupsWithAggregations(currentLevel.groups(), item, depth + 1);
    }

    private AggregationData buildAggregationData(ExperimentGroupAggregationItem item) {
        return AggregationData.builder()
                .experimentCount(item.experimentCount())
                .traceCount(item.traceCount())
                .totalEstimatedCost(item.totalEstimatedCost())
                .totalEstimatedCostAvg(item.totalEstimatedCostAvg())
                .duration(item.duration())
                .feedbackScores(item.feedbackScores())
                .build();
    }

    private GroupContentWithAggregations calculateRecursiveAggregations(GroupContentWithAggregations content) {
        if (content.groups().isEmpty()) {
            // Leaf node - return as-is
            return content;
        }

        // Recursively calculate aggregations for all child groups first
        var updatedChildGroups = new HashMap<String, GroupContentWithAggregations>();
        for (Map.Entry<String, GroupContentWithAggregations> entry : content.groups().entrySet()) {
            updatedChildGroups.put(entry.getKey(), calculateRecursiveAggregations(entry.getValue()));
        }

        // Calculate aggregated values from all children
        long totalExperimentCount = 0;
        long totalTraceCount = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        // For weighted averages
        BigDecimal weightedCostSum = BigDecimal.ZERO;
        BigDecimal p50Sum = BigDecimal.ZERO;
        BigDecimal p90Sum = BigDecimal.ZERO;
        BigDecimal p99Sum = BigDecimal.ZERO;

        // For feedback scores - group by name and calculate weighted averages
        Map<String, BigDecimal> feedbackScoreSums = new HashMap<>();
        Map<String, Long> feedbackScoreCounts = new HashMap<>();

        for (GroupContentWithAggregations child : updatedChildGroups.values()) {
            AggregationData childAgg = child.aggregations();
            long expCount = childAgg.experimentCount();

            totalExperimentCount += childAgg.experimentCount();
            totalTraceCount += childAgg.traceCount();
            totalCost = totalCost.add(childAgg.totalEstimatedCost());

            // For weighted cost average
            if (childAgg.totalEstimatedCostAvg() != null) {
                weightedCostSum = weightedCostSum.add(
                        childAgg.totalEstimatedCostAvg().multiply(BigDecimal.valueOf(expCount)));
            }

            // For duration percentiles (weighted average)
            if (childAgg.duration() != null) {
                if (childAgg.duration().p50() != null) {
                    p50Sum = p50Sum.add(childAgg.duration().p50().multiply(BigDecimal.valueOf(expCount)));
                }
                if (childAgg.duration().p90() != null) {
                    p90Sum = p90Sum.add(childAgg.duration().p90().multiply(BigDecimal.valueOf(expCount)));
                }
                if (childAgg.duration().p99() != null) {
                    p99Sum = p99Sum.add(childAgg.duration().p99().multiply(BigDecimal.valueOf(expCount)));
                }
            }

            // For feedback scores (weighted average per name)
            if (childAgg.feedbackScores() != null) {
                for (FeedbackScoreAverage score : childAgg.feedbackScores()) {
                    String name = score.name();
                    BigDecimal value = score.value();

                    if (value != null && name != null) {
                        feedbackScoreSums.merge(name, value.multiply(BigDecimal.valueOf(expCount)), BigDecimal::add);
                        feedbackScoreCounts.merge(name, expCount, Long::sum);
                    }
                }
            }
        }

        // Calculate averages
        BigDecimal avgCost = totalExperimentCount > 0
                ? weightedCostSum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        PercentageValues avgDuration = totalExperimentCount > 0
                ? new PercentageValues(
                        p50Sum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP),
                        p90Sum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP),
                        p99Sum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP))
                : null;

        List<FeedbackScoreAverage> avgFeedbackScores = feedbackScoreSums.entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    BigDecimal sum = entry.getValue();
                    Long count = feedbackScoreCounts.get(name);
                    BigDecimal avg = count > 0
                            ? sum.divide(BigDecimal.valueOf(count), 9, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new FeedbackScoreAverage(name, avg);
                })
                .toList();

        // Build updated aggregation data
        AggregationData updatedAggregations = AggregationData.builder()
                .experimentCount(totalExperimentCount)
                .traceCount(totalTraceCount)
                .totalEstimatedCost(totalCost)
                .totalEstimatedCostAvg(avgCost)
                .duration(avgDuration)
                .feedbackScores(avgFeedbackScores)
                .build();

        // Return new GroupContentWithAggregations with calculated aggregations
        return GroupContentWithAggregations.builder()
                .aggregations(updatedAggregations)
                .groups(updatedChildGroups)
                .build();
    }

    @WithSpan
    public Mono<Experiment> getById(@NonNull UUID id) {
        log.info("Getting experiment by id '{}'", id);
        return enrichExperiment(experimentDAO.getById(id), "Not found experiment with id '%s'".formatted(id));
    }

    @WithSpan
    public Flux<Experiment> get(@NonNull ExperimentStreamRequest request) {
        log.info("Getting experiments by '{}'", request);
        return experimentDAO.get(request)
                .collectList()
                .flatMap(this::enrichExperiments)
                .flatMapMany(Flux::fromIterable);
    }

    private Mono<Experiment> enrichExperiment(Mono<Experiment> experimentMono, String errorMsg) {
        return experimentMono
                .switchIfEmpty(Mono.defer(() -> Mono.error(newNotFoundException(errorMsg))))
                .flatMap(experiment -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    Set<UUID> promptVersionIds = getPromptVersionIds(experiment);

                    return Mono.zip(
                            promptService.getVersionsCommitByVersionsIds(promptVersionIds),
                            Mono.fromCallable(() -> datasetService.getById(experiment.datasetId(), workspaceId))
                                    .subscribeOn(Schedulers.boundedElastic()))
                            .map(tuple -> experiment.toBuilder()
                                    .promptVersion(buildPromptVersion(tuple.getT1(), experiment))
                                    .promptVersions(buildPromptVersions(tuple.getT1(), experiment))
                                    .datasetName(tuple.getT2()
                                            .map(Dataset::name)
                                            .orElse(null))
                                    .build());
                }));
    }

    private Set<UUID> getPromptVersionIds(Experiment experiment) {
        if (hasPromptVersionLinks(experiment)) {

            // to be deprecated soon
            var promptVersion = Optional.ofNullable(experiment.promptVersion())
                    .map(PromptVersionLink::id)
                    .map(Set::of)
                    .orElse(Set.of());

            var promptVersions = Optional.ofNullable(experiment.promptVersions())
                    .stream()
                    .flatMap(List::stream)
                    .map(PromptVersionLink::id)
                    .collect(Collectors.toSet());

            return SetUtils.union(promptVersion, promptVersions);
        }

        return Set.of();
    }

    public Mono<UUID> create(@NonNull Experiment experiment) {
        var id = experiment.id() == null ? idGenerator.generateId() : experiment.id();
        IdGenerator.validateVersion(id, "Experiment");
        var name = StringUtils.getIfBlank(experiment.name(), nameGenerator::generateName);
        return datasetService.getOrCreateDataset(experiment.datasetName())
                .flatMap(datasetId -> {
                    if (hasPromptVersionLinks(experiment)) {
                        return validatePromptVersion(experiment).flatMap(promptVersionMap -> {
                            var builder = experiment.toBuilder();
                            // add prompt versions to new prompt version map field
                            builder.promptVersions(promptVersionMap.values().stream()
                                    .map(promptVersion -> PromptVersionLink.builder()
                                            .id(promptVersion.id())
                                            .commit(promptVersion.commit())
                                            .promptId(promptVersion.promptId())
                                            .build())
                                    .toList());
                            // add prompt version to old prompt version field (to be deprecated soon)
                            if (experiment.promptVersion() != null) {
                                var promptVersion = promptVersionMap.get(experiment.promptVersion().id());
                                builder.promptVersion(PromptVersionLink.builder()
                                        .id(promptVersion.id())
                                        .commit(promptVersion.commit())
                                        .promptId(promptVersion.promptId())
                                        .build());
                            }
                            return create(builder.build(), id, name, datasetId);
                        });
                    }
                    return create(experiment, id, name, datasetId);
                })
                // If a conflict occurs, we just return the id of the existing experiment.
                // If any other error occurs, we throw it. The event is not posted for both cases.
                .onErrorResume(throwable -> handleCreateError(throwable, id));
    }

    private boolean hasPromptVersionLinks(Experiment experiment) {
        return experiment.promptVersion() != null || CollectionUtils.isNotEmpty(experiment.promptVersions());
    }

    private Mono<Map<UUID, PromptVersion>> validatePromptVersion(Experiment experiment) {

        Set<UUID> versionIds = getPromptVersionIds(experiment);

        return promptService.findVersionByIds(versionIds)
                .onErrorResume(e -> {
                    if (e instanceof NotFoundException) {
                        return Mono
                                .error(new ClientErrorException("Prompt version not found", Response.Status.CONFLICT));
                    }

                    return Mono.error(e);
                });
    }

    private Mono<UUID> create(Experiment experiment, UUID id, String name, UUID datasetId) {
        var newExperiment = experiment.toBuilder()
                .id(id)
                .name(name)
                .datasetId(datasetId)
                // The createdAt field is set to later post the ExperimentCreated event, but it is not persisted in the
                // database as the default now64(9) is used instead.
                .createdAt(Instant.now())
                .build();
        log.info("Inserting experiment with id '{}', name '{}', datasetId '{}', datasetName '{}'",
                newExperiment.id(), newExperiment.name(), newExperiment.datasetId(), newExperiment.datasetName());
        return makeMonoContextAware((userName, workspaceId) -> experimentDAO.insert(newExperiment)
                .thenReturn(newExperiment.id())
                // The event is posted only when the experiment is successfully created.
                .doOnSuccess(experimentId -> postExperimentCreatedEvent(newExperiment, workspaceId, userName)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void postExperimentCreatedEvent(Experiment partialExperiment, String workspaceId, String userName) {
        log.info("Posting experiment created event for experiment id '{}', datasetId '{}', workspaceId '{}'",
                partialExperiment.id(), partialExperiment.datasetId(), workspaceId);
        eventBus.post(new ExperimentCreated(
                partialExperiment.id(),
                partialExperiment.datasetId(),
                // The createdAt field is not exactly the one persisted in the DB, but it doesn't matter:
                // - The experiment.createdAt field in ClickHouse has precision 9,
                // whereas for dataset.lastCreatedExperimentAt in MySQL has precision 6.
                // - It's approximated enough for the event.
                // - At the moment of writing this comment, the dataset.lastCreatedExperimentAt field is only used
                // to optionally sort the datasets returned by the find datasets endpoint. There are no other usages
                // in the UI or elsewhere.
                partialExperiment.createdAt(),
                workspaceId,
                userName,
                Optional.ofNullable(partialExperiment.type()).orElse(ExperimentType.REGULAR)));
        log.info("Posted experiment created event for experiment id '{}', datasetId '{}', workspaceId '{}'",
                partialExperiment.id(), partialExperiment.datasetId(), workspaceId);
    }

    private Mono<UUID> handleCreateError(Throwable throwable, UUID id) {
        if (throwable instanceof ClickHouseException
                && throwable.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && throwable.getMessage().contains("_CAST(id, FixedString(36))")) {
            log.warn("Already exists experiment with id '{}'", id);
            return Mono.just(id);
        }
        log.error("Unexpected exception creating experiment with id '{}'", id);
        return Mono.error(throwable);
    }

    private NotFoundException newNotFoundException(String message) {
        log.info(message);
        return new NotFoundException(message);
    }

    public Mono<Boolean> validateExperimentWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> experimentIds) {
        if (experimentIds.isEmpty()) {
            return Mono.just(true);
        }

        return experimentDAO.getExperimentWorkspaces(experimentIds)
                .all(experimentWorkspace -> workspaceId.equals(experimentWorkspace.workspaceId()));
    }

    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return experimentDAO.getExperimentsDatasetInfo(ids)
                .flatMap(experimentDatasetInfo -> Mono.deferContextual(ctx -> experimentDAO.delete(ids)
                        .then(Mono.defer(() -> experimentItemDAO.deleteByExperimentIds(ids)))
                        .doOnSuccess(unused -> eventBus.post(new ExperimentsDeleted(
                                experimentDatasetInfo,
                                ctx.get(RequestContext.WORKSPACE_ID),
                                ctx.get(RequestContext.USER_NAME))))))
                .then();
    }

    @WithSpan
    public Flux<DatasetLastExperimentCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return experimentDAO.getMostRecentCreatedExperimentFromDatasets(datasetIds);
    }

    @WithSpan
    public Mono<BiInformationResponse> getExperimentBIInformation() {
        log.info("Getting experiment BI events daily data");
        return experimentDAO.getExperimentBIInformation()
                .collectList()
                .flatMap(items -> Mono.just(
                        BiInformationResponse.builder()
                                .biInformation(items)
                                .build()))
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));

    }

    @WithSpan
    public Mono<Long> getDailyCreatedCount() {
        return experimentDAO.getDailyCreatedCount();
    }
}
