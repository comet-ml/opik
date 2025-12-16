package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioLog;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.events.OptimizationCreated;
import com.comet.opik.api.events.OptimizationsDeleted;
import com.comet.opik.domain.attachment.PreSignerService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.queues.Queue;
import com.comet.opik.infrastructure.queues.QueueProducer;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(OptimizationServiceImpl.class)
public interface OptimizationService {

    Mono<UUID> upsert(@NonNull Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<Optimization> getById(UUID id, boolean includeStudioConfig);

    Mono<Optimization.OptimizationPage> find(int page, int size, OptimizationSearchCriteria searchCriteria);

    Mono<Void> delete(@NonNull Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds);

    Mono<Long> update(UUID commentId, OptimizationUpdate update);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);

    // Studio methods
    Mono<OptimizationStudioLog> generateStudioLogsResponse(UUID optimizationId);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class OptimizationServiceImpl implements OptimizationService {

    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull NameGenerator nameGenerator;
    private final @NonNull EventBus eventBus;
    private final @NonNull PreSignerService preSignerService;
    private final @NonNull QueueProducer queueProducer;
    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull OpikConfiguration config;

    @Override
    @WithSpan
    public Mono<Optimization> getById(@NonNull UUID id) {
        return getById(id, false);
    }

    @Override
    @WithSpan
    public Mono<Optimization> getById(@NonNull UUID id, boolean includeStudioConfig) {
        log.info("Getting optimization by id '{}', includeStudioConfig: '{}'", id, includeStudioConfig);
        return optimizationDAO.getById(id)
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    var enriched = enrichOptimizations(List.of(optimization), workspaceId).getFirst();

                    // Scrub studioConfig unless explicitly requested
                    if (!includeStudioConfig) {
                        enriched = enriched.toBuilder().studioConfig(null).build();
                    }

                    return Mono.just(enriched);
                }))
                .switchIfEmpty(Mono.defer(
                        () -> Mono.error(new NotFoundException("Not found optimization with id '%s'".formatted(id)))));
    }

    @Override
    @WithSpan
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return optimizationDAO.find(page, size, searchCriteria)
                .flatMap(optimizationPage -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    var enrichedOptimizations = enrichOptimizations(optimizationPage.content(), workspaceId);

                    // Scrub studioConfig unless explicitly requesting Studio-only optimizations
                    var finalOptimizations = Boolean.TRUE.equals(searchCriteria.studioOnly())
                            ? enrichedOptimizations
                            : enrichedOptimizations.stream()
                                    .map(opt -> opt.toBuilder().studioConfig(null).build())
                                    .toList();

                    return Mono.just(optimizationPage.toBuilder()
                            .content(finalOptimizations).build());
                }));
    }

    @Override
    @WithSpan
    public Mono<UUID> upsert(@NonNull Optimization optimization) {
        UUID id = optimization.id() == null ? idGenerator.generateId() : optimization.id();
        IdGenerator.validateVersion(id, "Optimization");
        var name = StringUtils.getIfBlank(optimization.name(), nameGenerator::generateName);

        // Detect if this is a Studio optimization
        boolean isStudioOptimization = optimization.studioConfig() != null;

        return datasetService.getOrCreateDataset(optimization.datasetName())
                .flatMap(datasetId -> {
                    var builder = optimization.toBuilder()
                            .id(id)
                            .name(name)
                            .datasetId(datasetId);

                    // Force INITIALIZED status for Studio optimizations
                    if (isStudioOptimization) {
                        builder.status(OptimizationStatus.INITIALIZED);
                        log.info("Force INITIALIZED (was '{}') status for Studio optimization id '{}'",
                                optimization.status(), id);
                    }

                    var newOptimization = builder.build();

                    return makeMonoContextAware((userName, workspaceId) -> Mono.deferContextual(ctx -> {

                        return optimizationDAO.upsert(newOptimization)
                                .thenReturn(newOptimization.id())
                                // The event is posted only when the experiment is successfully created.
                                .doOnSuccess(experimentId -> {
                                    postOptimizationCreatedEvent(newOptimization, workspaceId, userName);

                                    // If Studio optimization, enqueue job to Redis RQ
                                    if (isStudioOptimization) {
                                        String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, null);
                                        if (StringUtils.isBlank(workspaceName)) {
                                            try {
                                                workspaceName = workspaceNameService.getWorkspaceName(workspaceId,
                                                        config.getAuthentication().getReactService().url());
                                            } catch (Exception e) {
                                                log.warn(
                                                        "Failed to get workspace name for workspaceId '{}', using workspaceId as name: {}",
                                                        workspaceId, e.getMessage());
                                                workspaceName = workspaceId;
                                            }
                                        }

                                        String opikApiKey = newOptimization.studioConfig() != null
                                                ? newOptimization.studioConfig().opikApiKey()
                                                : null;

                                        enqueueStudioOptimizationJob(newOptimization, workspaceId, workspaceName,
                                                opikApiKey);
                                    }
                                });
                    }))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                // If a conflict occurs, we just return the id of the existing experiment.
                // If any other error occurs, we throw it. The event is not posted for both cases.
                .onErrorResume(throwable -> handleCreateError(throwable, id));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return optimizationDAO.getOptimizationDatasetIds(ids)
                .flatMap(optimizationDatasetIds -> Mono.deferContextual(ctx -> optimizationDAO.delete(ids)
                        .doOnSuccess(unused -> eventBus.post(new OptimizationsDeleted(
                                optimizationDatasetIds.stream()
                                        .map(DatasetEventInfoHolder::datasetId)
                                        .collect(Collectors.toSet()),
                                ctx.get(RequestContext.WORKSPACE_ID),
                                ctx.get(RequestContext.USER_NAME))))))
                .then();
    }

    @Override
    @WithSpan
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return optimizationDAO.getMostRecentCreatedExperimentFromDatasets(datasetIds);
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        if (update.name() == null && update.status() == null) {
            return Mono.empty();
        }

        return optimizationDAO.getById(id)
                .switchIfEmpty(Mono.error(failWithNotFound("Optimization", id)))
                .then(Mono.defer(() -> optimizationDAO.update(id, update)));
    }

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Mono.empty();
        }

        return optimizationDAO.updateDatasetDeleted(datasetIds);
    }

    private Mono<UUID> handleCreateError(Throwable throwable, UUID id) {
        if (throwable instanceof ClickHouseException
                && throwable.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && throwable.getMessage().contains("_CAST(id, FixedString(36))")) {
            log.warn("Already exists optimization with id '{}'", id);
            return Mono.just(id);
        }
        log.error("Unexpected exception creating optimization with id '{}'", id);
        return Mono.error(throwable);
    }

    private void postOptimizationCreatedEvent(Optimization newOptimization, String workspaceId, String userName) {
        log.info("Posting optimization created event for optimization id '{}', datasetId '{}', workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
        eventBus.post(new OptimizationCreated(
                newOptimization.id(),
                newOptimization.datasetId(),
                Instant.now(),
                workspaceId,
                userName));
        log.info("Posted optimization created event for optimization id '{}', datasetId '{}', workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
    }

    private void enqueueStudioOptimizationJob(Optimization optimization, String workspaceId, String workspaceName,
            String opikApiKey) {
        if (workspaceName == null) {
            log.error(
                    "Cannot enqueue Studio optimization job for id: '{}' - workspaceName is null, marking as CANCELLED",
                    optimization.id());
            cancelOptimization(optimization.id(), workspaceId);
            return;
        }

        log.info("Enqueuing Optimization Studio job for id: '{}', workspace: '{}' (name: '{}')",
                optimization.id(), workspaceId, workspaceName);

        // Build job message (use workspace name for SDK)
        var jobMessage = OptimizationStudioJobMessage.builder()
                .optimizationId(optimization.id())
                .workspaceName(workspaceName)
                .config(optimization.studioConfig())
                .opikApiKey(opikApiKey)
                .build();

        // Enqueue to Redis RQ
        queueProducer.enqueue(Queue.OPTIMIZER_CLOUD, jobMessage)
                .doOnSuccess(
                        jobId -> log.info("Studio optimization job enqueued successfully for id: '{}', jobId: '{}'",
                                optimization.id(), jobId))
                .doOnError(error -> {
                    log.error("Failed to enqueue Studio optimization job for id: '{}', marking as CANCELLED",
                            optimization.id(), error);
                    cancelOptimization(optimization.id(), workspaceId);
                })
                .subscribe();
    }

    private void cancelOptimization(UUID optimizationId, String workspaceId) {
        var optimizationUpdate = OptimizationUpdate.builder()
                .status(OptimizationStatus.CANCELLED)
                .build();

        update(optimizationId, optimizationUpdate)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> log.info("Cancelled optimization '{}'", optimizationId),
                        error -> log.error("Failed to cancel optimization '{}'", optimizationId, error));
    }

    private List<Optimization> enrichOptimizations(List<Optimization> optimizations, String workspaceId) {
        var ids = optimizations.stream().map(Optimization::datasetId).collect(Collectors.toUnmodifiableSet());
        var datasetMap = datasetService.findByIds(ids, workspaceId)
                .stream().collect(Collectors.toMap(Dataset::id, Function.identity()));

        return optimizations.stream()
                .map(optimization -> optimization.toBuilder()
                        .datasetName(Optional
                                .ofNullable(datasetMap.get(optimization.datasetId()))
                                .map(Dataset::name)
                                .orElse(null))
                        .build())
                .toList();
    }

    // ==================== Studio Methods ====================

    @Override
    public Mono<OptimizationStudioLog> generateStudioLogsResponse(@NonNull UUID optimizationId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            log.debug("Generating logs response for Studio optimization: '{}' in workspace: '{}'", optimizationId,
                    workspaceId);

            // Build S3 key from workspace_id and optimization_id
            String s3Key = String.format("logs/%s/%s.log", workspaceId, optimizationId);

            // TODO: Check if log file exists in S3 and get last modified
            // For now, return null for lastModified (file doesn't exist yet for new optimizations)
            Instant lastModified = null;

            // Generate presigned URL and calculate expiration
            String presignedUrl = preSignerService.presignDownloadUrl(s3Key);
            long expirationSeconds = preSignerService.getPresignedUrlExpirationSeconds();
            Instant expiresAt = Instant.now().plus(Duration.ofSeconds(expirationSeconds));

            return Mono.just(OptimizationStudioLog.builder()
                    .url(presignedUrl)
                    .lastModified(lastModified)
                    .expiresAt(expiresAt)
                    .build());
        });
    }
}
