package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetCriteria;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.events.ExperimentCreated;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
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
                            .map(ExperimentDatasetId::datasetId)
                            .collect(Collectors.toSet()))
                    .flatMap(datasetIds -> AsyncUtils.makeMonoContextAware((userName, workspaceId) -> {

                        if (datasetIds.isEmpty()) {
                            return Mono.just(ExperimentPage.empty(page));
                        }

                        return getDeletedDatasetAndBuildCriteria(experimentSearchCriteria, datasetIds, workspaceId)
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(criteria -> {
                                    if (criteria.datasetIds().isEmpty()) {
                                        return Mono.just(ExperimentPage.empty(page));
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

    private Mono<ExperimentPage> fetchExperimentPage(int page, int size,
            ExperimentSearchCriteria experimentSearchCriteria) {
        return experimentDAO.find(page, size, experimentSearchCriteria)
                .flatMap(experimentPage -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    var ids = experimentPage.content().stream()
                            .map(Experiment::datasetId)
                            .collect(Collectors.toUnmodifiableSet());

                    return Mono.zip(
                            promptService
                                    .getVersionsCommitByVersionsIds(getPromptVersionIds(experimentPage)),
                            Mono.fromCallable(() -> datasetService.findByIds(ids, workspaceId))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .map(this::getDatasetMap))
                            .map(tuple -> experimentPage.toBuilder()
                                    .content(experimentPage.content().stream()
                                            .map(experiment -> experiment.toBuilder()
                                                    .datasetName(Optional
                                                            .ofNullable(tuple.getT2().get(experiment.datasetId()))
                                                            .map(Dataset::name)
                                                            .orElse(null))
                                                    .promptVersion(
                                                            buildPromptVersion(tuple.getT1(), experiment))
                                                    .promptVersions(buildPromptVersions(tuple.getT1(), experiment))
                                                    .build())
                                            .toList())
                                    .build());
                }));
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

    private Set<UUID> getPromptVersionIds(ExperimentPage experimentPage) {
        return experimentPage.content().stream()
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
    public Mono<Experiment> getByName(@NonNull String name) {
        log.info("Getting experiment by name '{}'", name);
        return enrichExperiment(experimentDAO.getByName(name), "Not found experiment with name '%s'".formatted(name));
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

    public Mono<Experiment> create(@NonNull Experiment experiment) {
        return Mono.deferContextual(ctx -> {

            var id = experiment.id() == null ? idGenerator.generateId() : experiment.id();
            IdGenerator.validateVersion(id, "Experiment");
            var name = StringUtils.getIfBlank(experiment.name(), nameGenerator::generateName);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return getOrCreateDataset(experiment.datasetName())
                    .onErrorResume(e -> handleDatasetCreationError(e, experiment.datasetName()).map(Dataset::id))
                    .flatMap(datasetId -> {

                        if (hasPromptVersionLinks(experiment)) {
                            return validatePromptVersion(experiment).flatMap(promptVersions -> {

                                var builder = experiment.toBuilder();

                                // add prompt versions to new prompt version map field
                                builder.promptVersions(
                                        promptVersions.values().stream()
                                                .map(promptVersion -> PromptVersionLink.builder()
                                                        .id(promptVersion.id())
                                                        .commit(promptVersion.commit())
                                                        .promptId(promptVersion.promptId())
                                                        .build())
                                                .toList());

                                // add prompt version to old prompt version field (to be deprecated soon)
                                if (experiment.promptVersion() != null) {
                                    PromptVersion promptVersion = promptVersions.get(experiment.promptVersion().id());
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
                    .onErrorResume(exception -> handleCreateError(exception, id))
                    .then(Mono.defer(() -> getById(id)))
                    .doOnSuccess(newExperiment -> eventBus.post(new ExperimentCreated(
                            newExperiment.id(),
                            newExperiment.datasetId(),
                            newExperiment.createdAt(),
                            workspaceId,
                            userName)));

        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static boolean hasPromptVersionLinks(Experiment experiment) {
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

    private Mono<UUID> getOrCreateDataset(String datasetName) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> datasetService.getOrCreate(workspaceId, datasetName, userName))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private Mono<Experiment> create(Experiment experiment, UUID id, String name, UUID datasetId) {
        experiment = experiment.toBuilder().id(id).name(name).datasetId(datasetId).build();
        return experimentDAO.insert(experiment).thenReturn(experiment);
    }

    private Mono<Dataset> handleDatasetCreationError(Throwable throwable, String datasetName) {
        if (throwable instanceof EntityAlreadyExistsException) {
            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                return Mono.fromCallable(() -> datasetService.findByName(workspaceId, datasetName))
                        .subscribeOn(Schedulers.boundedElastic());
            });
        }
        return Mono.error(throwable);
    }

    private Mono<Experiment> handleCreateError(Throwable throwable, UUID id) {
        if (throwable instanceof ClickHouseException
                && throwable.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && throwable.getMessage().contains("_CAST(id, FixedString(36))")) {
            return Mono.error(newConflictException(id));
        }
        return Mono.error(throwable);
    }

    private ClientErrorException newConflictException(UUID id) {
        String message = "Already exists experiment with id '%s'".formatted(id);
        log.info(message);
        return new ClientErrorException(message, Response.Status.CONFLICT);
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

        return experimentDAO.getExperimentsDatasetIds(ids)
                .flatMap(experimentDatasetIds -> Mono.deferContextual(ctx -> experimentDAO.delete(ids)
                        .then(Mono.defer(() -> experimentItemDAO.deleteByExperimentIds(ids)))
                        .doOnSuccess(unused -> eventBus.post(new ExperimentsDeleted(
                                experimentDatasetIds.stream()
                                        .map(ExperimentDatasetId::datasetId)
                                        .collect(Collectors.toSet()),
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
