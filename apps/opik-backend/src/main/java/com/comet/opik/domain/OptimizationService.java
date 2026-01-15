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
import com.comet.opik.domain.optimization.OptimizationLogSyncService;
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
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(OptimizationServiceImpl.class)
public interface OptimizationService {

    Mono<UUID> upsert(@NonNull Optimization optimization);

    Mono<Optimization> getById(UUID id);

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
    private final @NonNull OptimizationLogSyncService logSyncService;
    private final @NonNull RedissonReactiveClient redisClient;

    // Redis key pattern for cancellation signals (Python worker checks this)
    private static final String CANCEL_KEY_PATTERN = "opik:cancel:%s";
    // Statuses that can be cancelled
    private static final Set<OptimizationStatus> CANCELLABLE_STATUSES = EnumSet.of(
            OptimizationStatus.INITIALIZED,
            OptimizationStatus.RUNNING);

    @Override
    @WithSpan
    public Mono<Optimization> getById(@NonNull UUID id) {
        log.info("Getting optimization by id '{}'", id);
        return optimizationDAO.getById(id)
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    var enriched = enrichOptimizations(List.of(optimization), workspaceId).getFirst();
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
                    return Mono.just(optimizationPage.toBuilder()
                            .content(enrichedOptimizations).build());
                }));
    }

    @Override
    @WithSpan
    public Mono<UUID> upsert(@NonNull Optimization optimization) {
        UUID id = optimization.id() == null ? idGenerator.generateId() : optimization.id();
        IdGenerator.validateVersion(id, "Optimization");

        // Detect if this is a Studio optimization (has studioConfig in the request)
        boolean isStudioOptimization = optimization.studioConfig() != null;

        return datasetService.getOrCreateDataset(optimization.datasetName())
                .flatMap(datasetId -> makeMonoContextAware((userName, workspaceId) -> Mono.deferContextual(ctx -> {

                    // Check if optimization already exists to preserve certain fields
                    return optimizationDAO.getById(id)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(existingOpt -> {
                                var builder = optimization.toBuilder()
                                        .id(id)
                                        .datasetId(datasetId);

                                // Preserve existing fields when updating (SDK doesn't know about studioConfig)
                                if (existingOpt.isPresent()) {
                                    var existing = existingOpt.get();
                                    log.info("Optimization '{}' already exists, preserving studioConfig", id);

                                    // Preserve studioConfig if not provided in update
                                    if (optimization.studioConfig() == null && existing.studioConfig() != null) {
                                        builder.studioConfig(existing.studioConfig());
                                    }

                                    // Preserve original name only if incoming name is blank
                                    // (SDK sends blank name, but explicit updates should be honored)
                                    if (StringUtils.isBlank(optimization.name())) {
                                        builder.name(existing.name());
                                    } else {
                                        builder.name(optimization.name());
                                    }

                                    // Don't re-enqueue job for existing optimizations
                                } else {
                                    // New optimization: generate name if not provided
                                    var name = StringUtils.getIfBlank(optimization.name(),
                                            nameGenerator::generateName);
                                    builder.name(name);
                                }

                                // Force INITIALIZED status for NEW Studio optimizations only
                                if (isStudioOptimization && existingOpt.isEmpty()) {
                                    builder.status(OptimizationStatus.INITIALIZED);
                                    log.info("Force INITIALIZED (was '{}') status for NEW Studio optimization id '{}'",
                                            optimization.status(), id);
                                }

                                var newOptimization = builder.build();
                                boolean shouldEnqueueJob = isStudioOptimization && existingOpt.isEmpty();

                                return optimizationDAO.upsert(newOptimization)
                                        .thenReturn(newOptimization.id())
                                        .doOnSuccess(__ -> {
                                            postOptimizationCreatedEvent(newOptimization, workspaceId, userName);

                                            // Only enqueue job for NEW Studio optimizations
                                            if (shouldEnqueueJob) {
                                                String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME,
                                                        null);
                                                if (StringUtils.isBlank(workspaceName)) {
                                                    try {
                                                        workspaceName = workspaceNameService.getWorkspaceName(
                                                                workspaceId,
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

                                                enqueueStudioOptimizationJob(newOptimization, workspaceId,
                                                        workspaceName, opikApiKey);
                                            }
                                        });
                            });
                }))
                        .subscribeOn(Schedulers.boundedElastic()))
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
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    // Validate cancellation request for Studio optimizations
                    boolean isStudioCancellation = update.status() == OptimizationStatus.CANCELLED
                            && optimization.studioConfig() != null;
                    boolean isNotCancellable = !CANCELLABLE_STATUSES.contains(optimization.status());

                    if (isStudioCancellation && isNotCancellable) {
                        return Mono.error(new ClientErrorException(
                                "Cannot cancel optimization with status '%s'. Only optimizations with status %s can be cancelled."
                                        .formatted(optimization.status(), CANCELLABLE_STATUSES),
                                Response.Status.CONFLICT));
                    }

                    return signalCancellationIfNeeded(id, optimization, update)
                            .then(optimizationDAO.update(id, update))
                            .doOnSuccess(result -> {
                                // Sync logs when optimization reaches terminal status
                                // Safe to call multiple times - just syncs and reduces TTL
                                if (update.status() != null && update.status().isTerminal()) {
                                    finalizeLogsAsync(workspaceId, id);
                                }
                            });
                }));
    }

    /**
     * Signals cancellation to Redis if this is a valid cancellation request for a Studio optimization.
     * The Python worker polls this Redis key to detect cancellation requests.
     *
     * @param id The optimization ID
     * @param optimization The current optimization state
     * @param update The requested update
     * @return Mono that completes when the signal is set, or empty if no signal is needed
     */
    private Mono<Void> signalCancellationIfNeeded(UUID id, Optimization optimization, OptimizationUpdate update) {
        boolean isStudioCancellation = update.status() == OptimizationStatus.CANCELLED
                && optimization.studioConfig() != null;
        boolean isCancellable = CANCELLABLE_STATUSES.contains(optimization.status());

        if (!isStudioCancellation || !isCancellable) {
            return Mono.empty();
        }

        log.info("Signalling cancellation for Studio optimization '{}' (current status: '{}')",
                id, optimization.status());

        String cancelKey = String.format(CANCEL_KEY_PATTERN, id);
        long ttlSeconds = config.getOptimizationLogs().getCancellationKeyTtlSeconds();

        return redisClient.getBucket(cancelKey)
                .set("1", ttlSeconds, TimeUnit.SECONDS)
                .doOnSuccess(__ -> log.debug("Set cancellation signal in Redis for optimization '{}'", id))
                .then();
    }

    private void finalizeLogsAsync(String workspaceId, UUID optimizationId) {
        logSyncService.finalizeLogsOnCompletion(workspaceId, optimizationId)
                .doOnError(error -> log.error("Failed to finalize logs for optimization '{}'",
                        optimizationId, error))
                .subscribe();
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

        // Build job message (use workspace name for SDK, workspace ID for log storage)
        var jobMessage = OptimizationStudioJobMessage.builder()
                .optimizationId(optimization.id())
                .workspaceId(workspaceId)
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

            // Build S3 key using the shared method from OptimizationLogSyncService
            String s3Key = OptimizationLogSyncService.formatS3Key(workspaceId, optimizationId);

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
