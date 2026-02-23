package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentBatchUpdate;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupEnrichInfoHolder;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.infrastructure.FeatureFlags;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.AlertEventType.EXPERIMENT_FINISHED;
import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;
import static com.comet.opik.api.grouping.GroupingFactory.PROJECT_ID;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
public class ExperimentService {

    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull ExperimentItemDAO experimentItemDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull DatasetVersionService datasetVersionService;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull NameGenerator nameGenerator;
    private final @NonNull EventBus eventBus;
    private final @NonNull PromptService promptService;
    private final @NonNull ExperimentSortingFactory sortingFactory;
    private final @NonNull ExperimentResponseBuilder responseBuilder;
    private final @NonNull FeatureFlags featureFlags;

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
            var datasetIds = experiments.stream().map(Experiment::datasetId).collect(Collectors.toUnmodifiableSet());
            var versionIds = experiments.stream()
                    .map(Experiment::datasetVersionId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            var projectIds = experiments.stream()
                    .map(Experiment::projectId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());

            return Mono.zip(
                    promptService.getVersionsInfoByVersionsIds(getPromptVersionIds(experiments)),
                    Mono.fromCallable(() -> datasetService.findByIds(datasetIds, workspaceId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(this::getDatasetMap),
                    Mono.fromCallable(() -> datasetVersionService.findByIds(versionIds, workspaceId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(this::getDatasetVersionMap),
                    Mono.fromCallable(() -> projectService.findByIds(workspaceId, projectIds))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(this::getProjectMap))
                    .map(tuple -> experiments.stream()
                            .map(experiment -> experiment.toBuilder()
                                    .datasetName(Optional
                                            .ofNullable(tuple.getT2().get(experiment.datasetId()))
                                            .map(Dataset::name)
                                            .orElse(null))
                                    .datasetVersionSummary(Optional
                                            .ofNullable(experiment.datasetVersionId())
                                            .map(tuple.getT3()::get)
                                            .map(DatasetVersionMapper.INSTANCE::toDatasetVersionSummary)
                                            .orElse(null))
                                    .projectName(Optional
                                            .ofNullable(experiment.projectId())
                                            .map(tuple.getT4()::get)
                                            .map(Project::name)
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

    private Map<UUID, Project> getProjectMap(List<Project> projects) {
        return projects.stream().collect(Collectors.toMap(Project::id, Function.identity()));
    }

    private Map<UUID, DatasetVersion> getDatasetVersionMap(List<DatasetVersion> versions) {
        return versions.stream().collect(Collectors.toMap(DatasetVersion::id, Function.identity()));
    }

    private List<PromptVersionLink> buildPromptVersions(Map<UUID, PromptVersionInfo> promptVersionsInfo,
            Experiment experiment) {
        if (hasPromptVersionLinks(experiment)) {

            Stream<PromptVersionLink> promptVersionLinks = Optional.ofNullable(experiment.promptVersions())
                    .orElseGet(List::of)
                    .stream()
                    .map(version -> enrichPromptVersionLink(version, promptVersionsInfo.get(version.id())));

            Stream<PromptVersionLink> promptVersionLink = Optional.ofNullable(experiment.promptVersion())
                    .stream()
                    .map(version -> enrichPromptVersionLink(version, promptVersionsInfo.get(version.id())));

            List<PromptVersionLink> versionLinks = Stream.concat(promptVersionLinks, promptVersionLink).distinct()
                    .toList();

            return versionLinks.isEmpty() ? null : versionLinks;
        }

        return null;
    }

    private PromptVersionLink buildPromptVersion(Map<UUID, PromptVersionInfo> promptVersionsInfo,
            Experiment experiment) {
        if (hasPromptVersionLinks(experiment)) {

            PromptVersionLink versionLink = experiment.promptVersion();

            if (versionLink != null) {
                return enrichPromptVersionLink(versionLink, promptVersionsInfo.get(versionLink.id()));
            } else {
                return Optional.ofNullable(experiment.promptVersions())
                        .stream()
                        .flatMap(List::stream)
                        .findFirst()
                        .map(version -> enrichPromptVersionLink(version, promptVersionsInfo.get(version.id())))
                        .orElse(null);
            }
        }

        return null;
    }

    private PromptVersionLink enrichPromptVersionLink(PromptVersionLink version, PromptVersionInfo info) {
        return new PromptVersionLink(
                version.id(),
                info != null ? info.commit() : null,
                version.promptId(),
                info != null ? info.promptName() : null);
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

    @WithSpan
    public Mono<ExperimentGroupAggregationsResponse> findGroupsAggregations(@NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups aggregations by criteria '{}'", criteria);

        return experimentDAO.findGroupsAggregations(criteria)
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
            String fieldName) {
        int nestingIdx = groups.stream()
                .filter(g -> fieldName.equals(g.field()))
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

                    // Get dataset version if experiment has a version ID
                    Mono<Optional<DatasetVersion>> versionMono = experiment.datasetVersionId() != null
                            ? Mono.fromCallable(() -> Optional.ofNullable(
                                    datasetVersionService.findByIds(List.of(experiment.datasetVersionId()), workspaceId)
                                            .stream()
                                            .findFirst()
                                            .orElse(null)))
                                    .subscribeOn(Schedulers.boundedElastic())
                            : Mono.just(Optional.empty());

                    // Get project if experiment has a project ID
                    Mono<Optional<Project>> projectMono = experiment.projectId() != null
                            ? Mono.fromCallable(
                                    () -> projectService.findByIds(workspaceId, Set.of(experiment.projectId()))
                                            .stream()
                                            .findFirst())
                                    .subscribeOn(Schedulers.boundedElastic())
                            : Mono.just(Optional.empty());

                    return Mono.zip(
                            promptService.getVersionsInfoByVersionsIds(promptVersionIds),
                            Mono.fromCallable(() -> datasetService.getById(experiment.datasetId(), workspaceId))
                                    .subscribeOn(Schedulers.boundedElastic()),
                            versionMono,
                            projectMono)
                            .map(tuple -> experiment.toBuilder()
                                    .promptVersion(buildPromptVersion(tuple.getT1(), experiment))
                                    .promptVersions(buildPromptVersions(tuple.getT1(), experiment))
                                    .datasetName(tuple.getT2()
                                            .map(Dataset::name)
                                            .orElse(null))
                                    .datasetVersionSummary(tuple.getT3()
                                            .map(DatasetVersionMapper.INSTANCE::toDatasetVersionSummary)
                                            .orElse(null))
                                    .projectName(tuple.getT4()
                                            .map(Project::name)
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
                    // Case 1: Feature toggle OFF - skip version resolution (legacy behavior)
                    if (!featureFlags.isDatasetVersioningEnabled()) {
                        return processExperimentCreation(experiment, id, name, datasetId);
                    }

                    // Case 2: Feature toggle ON - resolve version and link experiment
                    return resolveDatasetVersion(experiment, datasetId)
                            .flatMap(resolvedVersionId -> {
                                var experimentWithVersion = experiment.toBuilder()
                                        .datasetVersionId(resolvedVersionId)
                                        .build();
                                return processExperimentCreation(experimentWithVersion, id, name, datasetId);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // No version found - proceed with null dataset_version_id
                                log.info(
                                        "No dataset version found for dataset '{}', creating experiment with null dataset_version_id",
                                        datasetId);
                                var experimentWithNullVersion = experiment.toBuilder()
                                        .datasetVersionId(null)
                                        .build();
                                return processExperimentCreation(experimentWithNullVersion, id, name, datasetId);
                            }));
                })
                // If a conflict occurs, we just return the id of the existing experiment.
                // If any other error occurs, we throw it. The event is not posted for both cases.
                .onErrorResume(throwable -> handleCreateError(throwable, id));
    }

    /**
     * Processes experiment creation by validating prompt versions (if present) and persisting the experiment.
     * This logic is shared between versioned and unversioned experiment creation flows.
     *
     * @param experiment the experiment to create (with datasetVersionId already resolved)
     * @param id the experiment ID
     * @param name the experiment name
     * @param datasetId the dataset ID
     * @return Mono emitting the created experiment ID
     */
    private Mono<UUID> processExperimentCreation(Experiment experiment, UUID id, String name, UUID datasetId) {
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
                    var promptVersion = promptVersionMap
                            .get(experiment.promptVersion().id());
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
    }

    /**
     * Resolves the dataset version ID for an experiment using 2-tier logic.
     * <p>
     * This method should only be called when the feature toggle is ON.
     * The resolution logic is:
     * 1. If experiment.datasetVersionId is explicitly provided, validate and use it
     * 2. Otherwise, use the latest version ID (always available after migration)
     *
     * @param experiment the experiment being created
     * @param datasetId the dataset ID
     * @return Mono emitting the resolved version ID
     */
    private Mono<UUID> resolveDatasetVersion(Experiment experiment, UUID datasetId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Case 1: Version ID explicitly provided - validate and use it
            if (experiment.datasetVersionId() != null) {
                log.info("Validating explicitly provided dataset version ID '{}' for experiment on dataset '{}'",
                        experiment.datasetVersionId(), datasetId);
                return Mono.fromCallable(() -> {
                    // Validate the version exists and belongs to this dataset
                    var version = datasetVersionService.getVersionById(workspaceId, datasetId,
                            experiment.datasetVersionId());

                    // Verify the version actually belongs to the specified dataset
                    if (!version.datasetId().equals(datasetId)) {
                        throw new NotFoundException(
                                "Version '%s' does not belong to dataset '%s'"
                                        .formatted(experiment.datasetVersionId(), datasetId));
                    }

                    log.info("Using validated dataset version ID '{}' for experiment on dataset '{}'",
                            version.id(), datasetId);
                    return version.id();
                }).subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            if (e instanceof NotFoundException) {
                                log.warn("Dataset version not found: '{}'", e.getMessage(), e);
                                return Mono.error(new ClientErrorException("Dataset version not found",
                                        Response.Status.CONFLICT));
                            }
                            return Mono.error(e);
                        });
            }

            // Case 2: No version specified - use latest version
            return Mono.fromCallable(() -> {
                var latestVersion = datasetVersionService.getLatestVersion(datasetId, workspaceId);
                if (latestVersion.isPresent()) {
                    log.info("No version specified, using latest version '{}' for experiment on dataset '{}'",
                            latestVersion.get().id(), datasetId);
                    return latestVersion.get().id();
                }
                // This should not happen after migration, but handle gracefully
                log.warn("No latest version found for dataset '{}', experiment will have null dataset_version_id",
                        datasetId);
                return null;
            }).subscribeOn(Schedulers.boundedElastic());
        });
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

    @WithSpan
    public Mono<Void> update(@NonNull UUID id, @NonNull ExperimentUpdate experimentUpdate) {
        log.info("Updating experiment with id '{}'", id);
        return experimentDAO.getById(id)
                .switchIfEmpty(Mono.error(newNotFoundException("Experiment not found: '%s'".formatted(id))))
                .then(experimentDAO.update(id, experimentUpdate))
                .doOnSuccess(unused -> log.info("Successfully updated experiment with id '{}'", id))
                .onErrorResume(TagOperations::mapTagLimitError)
                .onErrorResume(throwable -> {
                    log.error("Failed to update experiment with id '{}'", id, throwable);
                    return Mono.error(throwable);
                });
    }

    public Mono<Void> batchUpdate(@NonNull ExperimentBatchUpdate batchUpdate) {
        log.info("Batch updating '{}' experiments", batchUpdate.ids().size());

        boolean mergeTags = batchUpdate.mergeTags();
        return experimentDAO.update(batchUpdate.ids(), batchUpdate.update(), mergeTags)
                .doOnSuccess(__ -> log.info("Completed batch update for '{}' experiments", batchUpdate.ids().size()))
                .onErrorResume(TagOperations::mapTagLimitError)
                .onErrorResume(throwable -> {
                    log.error("Failed to complete batch update of the '{}' experiments", batchUpdate.ids().size(),
                            throwable);
                    return Mono.error(throwable);
                });
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
    public Mono<Void> finishExperiments(@NonNull Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String workspaceName = ctx.get(RequestContext.WORKSPACE_NAME);
            String userName = ctx.get(RequestContext.USER_NAME);

            log.info("Finishing experiments, count '{}', workspaceId '{}'", ids.size(), workspaceId);

            return experimentDAO.getByIds(ids)
                    .collectList()
                    .doOnNext(experiments -> {
                        if (CollectionUtils.isNotEmpty(experiments)) {
                            log.info("Raising alert event for finished experiments, count '{}'", experiments.size());
                            eventBus.post(AlertEvent.builder()
                                    .eventType(EXPERIMENT_FINISHED)
                                    .workspaceId(workspaceId)
                                    .workspaceName(workspaceName)
                                    .userName(userName)
                                    .payload(experiments)
                                    .build());
                        }
                    })
                    .then();
        });
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
