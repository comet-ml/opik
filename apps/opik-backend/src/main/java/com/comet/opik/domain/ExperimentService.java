package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetCriteria;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
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
