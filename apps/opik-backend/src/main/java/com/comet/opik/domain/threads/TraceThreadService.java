package com.comet.opik.domain.threads;

import com.comet.opik.api.ThreadTimestamps;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadBatchUpdate;
import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.resources.v1.events.TraceThreadBufferConfig;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TagOperations;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceConfigurationService;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(TraceThreadServiceImpl.class)
public interface TraceThreadService {

    String THREADS_LOCK = "trace-threads-process";

    Mono<Void> processTraceThreads(Map<String, ThreadTimestamps> threadInfo, UUID projectId);

    Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(Instant now,
            Duration defaultTimeoutToMarkThreadAsInactive,
            Duration minLookback,
            int limit);

    Mono<Void> processProjectWithTraceThreadsPendingClosure(UUID projectId, Instant now,
            Duration defaultTimeoutToMarkThreadAsInactive, Duration minLookback);

    Mono<Boolean> addToPendingQueue(UUID projectId);

    Mono<Void> openThread(UUID projectId, String projectName, String threadId);

    Mono<Void> closeThreads(UUID projectId, String projectName, Set<String> threadIds);

    Mono<UUID> getOrCreateThreadId(UUID projectId, String threadId);

    Mono<Map<String, UUID>> getOrCreateThreadIds(UUID projectId, Set<String> threadIds);

    Mono<UUID> getThreadModelId(UUID projectId, String threadId);

    Mono<Void> updateThreadSampledValue(UUID projectId, List<TraceThreadSampling> threadSamplingPerRule);

    Mono<Void> update(UUID threadModelId, TraceThreadUpdate threadUpdate);

    Mono<Void> batchUpdate(TraceThreadBatchUpdate batchUpdate);

    Mono<Map<UUID, String>> getThreadIdsByThreadModelIds(List<UUID> threadModelIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceThreadServiceImpl implements TraceThreadService {

    private static final Duration LOCK_DURATION = Duration.ofSeconds(5);

    private final @NonNull TraceThreadDAO traceThreadDAO;
    private final @NonNull TraceThreadIdService traceThreadIdService;
    private final @NonNull TraceService traceService;
    private final @NonNull ProjectService projectService;
    private final @NonNull LockService lockService;
    private final @NonNull EventBus eventBus;
    private final @NonNull TraceThreadOnlineScorerPublisher onlineScorePublisher;
    private final @NonNull WorkspaceConfigurationService workspaceConfigurationService;

    public Mono<Void> processTraceThreads(@NonNull Map<String, ThreadTimestamps> threadInfo,
            @NonNull UUID projectId) {

        return processThreadAsync(threadInfo, projectId)
                .collectList()
                .flatMap(traceThreads -> this.saveTraceThreads(projectId, traceThreads))
                .then();
    }

    private Flux<TraceThreadModel> processThreadAsync(Map<String, ThreadTimestamps> threadInfo, UUID projectId) {
        return Flux.deferContextual(context -> Flux.fromIterable(threadInfo.entrySet())
                .flatMap(entry -> {
                    String workspaceId = context.get(RequestContext.WORKSPACE_ID);
                    String userName = context.get(RequestContext.USER_NAME);
                    String threadId = entry.getKey();
                    ThreadTimestamps timestamps = entry.getValue();

                    // Extract timestamp from the earliest trace (first trace in chronological order)
                    var earliestTraceTimestamp = RetentionUtils.extractInstant(timestamps.firstTraceId());

                    return traceThreadIdService
                            .getOrCreateTraceThreadId(workspaceId, projectId, threadId, earliestTraceTimestamp)
                            .map(traceThreadId -> TraceThreadMapper.INSTANCE.mapFromThreadIdModel(
                                    traceThreadId, userName, TraceThreadStatus.ACTIVE,
                                    timestamps.maxLastUpdatedAt(), timestamps.firstTraceSource(),
                                    timestamps.firstTraceEnvironment()));
                }));
    }

    @Override
    public Mono<UUID> getOrCreateThreadId(@NonNull UUID projectId, @NonNull String threadId) {
        return getOrCreateThreadId(projectId, threadId, null);
    }

    private Mono<UUID> getOrCreateThreadId(UUID projectId, String threadId, Instant timestamp) {
        return Mono.deferContextual(context -> traceThreadIdService
                .getOrCreateTraceThreadId(context.get(RequestContext.WORKSPACE_ID), projectId, threadId, timestamp)
                .map(TraceThreadIdModel::id));
    }

    @Override
    public Mono<Map<String, UUID>> getOrCreateThreadIds(@NonNull UUID projectId, Set<String> threadIds) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.just(Map.of());
        }
        // Bulk get-or-create in a single query instead of one round-trip per thread. Null timestamps match
        // getOrCreateThreadId(projectId, threadId): a missing thread's model id is generated from the current time.
        return Mono.deferContextual(context -> {
            Map<String, Instant> threadIdToTimestamp = new HashMap<>();
            threadIds.forEach(threadId -> threadIdToTimestamp.put(threadId, null));
            return traceThreadIdService
                    .getOrCreateTraceThreadIds(context.get(RequestContext.WORKSPACE_ID), projectId, threadIdToTimestamp)
                    .map(models -> models.stream()
                            .collect(Collectors.toMap(TraceThreadIdModel::threadId, TraceThreadIdModel::id,
                                    (existing, duplicate) -> existing)));
        });
    }

    @Override
    public Mono<UUID> getThreadModelId(@NonNull UUID projectId, @NonNull String threadId) {
        return Mono.deferContextual(context -> traceThreadIdService
                .getThreadModelId(context.get(RequestContext.WORKSPACE_ID), projectId, threadId));
    }

    @Override
    public Mono<Void> updateThreadSampledValue(@NonNull UUID projectId,
            @NonNull List<TraceThreadSampling> threadSamplingPerRules) {
        if (threadSamplingPerRules.isEmpty()) {
            log.info("No thread sampling data provided for projectId: '{}'. Skipping update.", projectId);
            return Mono.empty();
        }

        return processUpdateThreadSampledValue(projectId, threadSamplingPerRules);
    }

    private Mono<Void> processUpdateThreadSampledValue(UUID projectId,
            List<TraceThreadSampling> threadSamplingPerRules) {
        return traceThreadDAO.updateThreadSampledValues(projectId, threadSamplingPerRules)
                .doOnSuccess(count -> log.info("Updated '{}' trace threads sampled values for project_id: '{}'", count,
                        projectId))
                .then();
    }

    public Mono<Void> update(@NonNull UUID threadModelId, @NonNull TraceThreadUpdate threadUpdate) {
        return traceThreadIdService.getTraceThreadIdByThreadModelId(threadModelId)
                .switchIfEmpty(Mono.error(failWithNotFound("Thread", threadModelId)))
                .flatMap(traceThreadIdModel -> traceThreadDAO.updateThread(threadModelId,
                        traceThreadIdModel.projectId(), threadUpdate))
                .onErrorResume(TagOperations::mapTagLimitError);
    }

    @Override
    public Mono<Void> batchUpdate(@NonNull TraceThreadBatchUpdate batchUpdate) {
        log.info("Batch updating '{}' threads", batchUpdate.ids().size());

        boolean mergeTags = Boolean.TRUE.equals(batchUpdate.mergeTags());
        List<UUID> threadModelIds = new ArrayList<>(batchUpdate.ids());
        return traceThreadDAO.bulkUpdate(threadModelIds, batchUpdate.update(), mergeTags)
                .doOnSuccess(__ -> log.info("Completed batch update for '{}' threads", batchUpdate.ids().size()))
                .onErrorResume(TagOperations::mapTagLimitError);
    }

    @Override
    public Mono<Map<UUID, String>> getThreadIdsByThreadModelIds(@NonNull List<UUID> threadModelIds) {
        return traceThreadIdService.getTraceThreadIdsByThreadModelIds(threadModelIds);
    }

    private Mono<Void> saveTraceThreads(UUID projectId, List<TraceThreadModel> traceThreads) {

        if (traceThreads.isEmpty()) {
            return Mono.empty();
        }

        List<UUID> ids = traceThreads.stream().map(TraceThreadModel::id).toList();

        var criteria = TraceThreadCriteria.builder()
                .projectId(projectId)
                .ids(ids)
                .build();

        return Mono.deferContextual(context -> getThreadsByIds(traceThreads, criteria)
                .flatMap(existingThreads -> saveThreads(traceThreads, existingThreads)
                        .doOnSuccess(entry -> {
                            List<TraceThreadModel> savedThreads = entry.getValue();
                            Long count = entry.getKey();

                            Set<UUID> existingThreadModelIds = existingThreads.stream()
                                    .map(TraceThreadModel::id)
                                    .collect(Collectors.toSet());

                            List<TraceThreadModel> createdThreadIds = savedThreads
                                    .stream()
                                    .filter(thread -> !existingThreadModelIds.contains(thread.id()))
                                    .toList();

                            if (!createdThreadIds.isEmpty()) {
                                eventBus.post(new TraceThreadsCreated(createdThreadIds, projectId,
                                        context.get(RequestContext.WORKSPACE_ID),
                                        context.get(RequestContext.USER_NAME)));
                            }

                            log.info("Saved '{}' trace threads for projectId: '{}'", count, projectId);
                        })
                        .then()));
    }

    private Mono<Map.Entry<Long, List<TraceThreadModel>>> saveThreads(List<TraceThreadModel> traceThreads,
            List<TraceThreadModel> existingThreads) {

        Map<UUID, TraceThreadModel> threadModelMap = existingThreads.stream()
                .collect(Collectors.toMap(TraceThreadModel::id, Function.identity()));

        List<TraceThreadModel> threadsToSave = traceThreads.stream()
                .map(thread -> {
                    TraceThreadModel existingThread = threadModelMap.get(thread.id());

                    if (existingThread != null) {
                        // Update existing thread with new values
                        return existingThread.toBuilder()
                                .status(thread.status())
                                .lastUpdatedBy(thread.lastUpdatedBy())
                                .lastUpdatedAt(thread.lastUpdatedAt())
                                .build();
                    }
                    return thread;
                })
                .toList();

        return traceThreadDAO.save(threadsToSave)
                .map(count -> Map.entry(count, threadsToSave));
    }

    private Mono<List<TraceThreadModel>> getThreadsByIds(List<TraceThreadModel> traceThreads,
            TraceThreadCriteria criteria) {
        return traceThreadDAO.findThreadsByProject(1, traceThreads.size(), criteria)
                .switchIfEmpty(Mono.just(List.of()));
    }

    @Override
    public Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(
            @NonNull Instant now, @NonNull Duration defaultTimeoutToMarkThreadAsInactive,
            Duration minLookback, int limit) {
        return getPendingClosureLookbackBound(now, defaultTimeoutToMarkThreadAsInactive, minLookback)
                .flatMapMany(cachedMaxInactivePeriod -> traceThreadDAO
                        .findProjectsWithPendingClosureThreads(now, defaultTimeoutToMarkThreadAsInactive,
                                cachedMaxInactivePeriod, limit));
    }

    private Mono<Instant> getPendingClosureLookbackBound(Instant now, Duration defaultTimeout, Duration minLookback) {
        return getMaxTimeoutMarkThreadAsInactive(defaultTimeout)
                .map(maxTimeout -> {
                    // max(coalesce(maxTimeout, defaultTimeout) + 1h, 1 day)
                    // Floor at 1 day: cheap enough (minmax index keeps it fast) and covers
                    // edge cases like very short configured timeouts.
                    // The +1h failsafe covers cache staleness (cached for 30min).
                    var withFailsafe = maxTimeout.plus(Duration.ofHours(1));
                    var lookbackPeriod = withFailsafe.compareTo(Duration.ofDays(1)) > 0
                            ? withFailsafe
                            : Duration.ofDays(1);
                    if (minLookback != null && minLookback.compareTo(lookbackPeriod) > 0) {
                        lookbackPeriod = minLookback;
                    }
                    return now.minus(lookbackPeriod);
                });
    }

    private Mono<Duration> getMaxTimeoutMarkThreadAsInactive(Duration defaultTimeout) {
        return workspaceConfigurationService.getMaxTimeoutMarkThreadAsInactive()
                .map(maxTimeoutSeconds -> {
                    if (maxTimeoutSeconds > 0) {
                        var maxFromDb = Duration.ofSeconds(maxTimeoutSeconds);
                        return maxFromDb.compareTo(defaultTimeout) > 0 ? maxFromDb : defaultTimeout;
                    }
                    return defaultTimeout;
                })
                .defaultIfEmpty(defaultTimeout);
    }

    @Override
    public Mono<Void> processProjectWithTraceThreadsPendingClosure(@NonNull UUID projectId,
            @NonNull Instant now, @NonNull Duration defaultTimeoutToMarkThreadAsInactive, Duration minLookback) {
        return Mono.deferContextual(
                contextView -> closeThreadWith(projectId, now, defaultTimeoutToMarkThreadAsInactive, minLookback,
                        contextView))
                .then();
    }

    private Mono<Long> closeThreadWith(UUID projectId, Instant now, Duration defaultTimeoutToMarkThreadAsInactive,
            Duration minLookback, ContextView ctx) {

        String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

        // The lookback bound must be at least as wide as any window the enqueuing scan
        // (getProjectsWithPendingClosureThreads) may have used, so minLookback here should be the
        // cold-start lookback: a thread visible to the enqueuer but outside this window would be
        // re-enqueued on every job run and never closed. Both scans read the same Redis-backed
        // cached max timeout, so their windows stay consistent; the clamp below additionally
        // guarantees a well-formed window (lookback before cutoff) even if that cached value lags
        // a workspace timeout increase for up to its TTL.
        return getWorkspaceTimeout(now, defaultTimeoutToMarkThreadAsInactive)
                .zipWith(getPendingClosureLookbackBound(now, defaultTimeoutToMarkThreadAsInactive, minLookback))
                .flatMapMany(bounds -> {
                    Instant lastUpdatedAt = bounds.getT1();
                    Instant lookbackBound = bounds.getT2().isBefore(lastUpdatedAt) ? bounds.getT2() : lastUpdatedAt;
                    return traceThreadDAO.streamPendingClosureThreads(projectId, lastUpdatedAt, lookbackBound);
                })
                .flatMap(threads -> {

                    if (threads.isEmpty()) {
                        log.info("No pending trace threads to close for projectId: '{}' on workspaceId '{}'",
                                projectId, workspaceId);
                        return Mono.just((long) threads.size());
                    }

                    Set<String> threadIds = threads.stream()
                            .map(TraceThreadModel::threadId)
                            .collect(Collectors.toSet());

                    return traceThreadDAO.closeThread(projectId, threadIds)
                            .doOnSuccess(count -> log.info(
                                    "Closed '{}' trace threads for projectId: '{}' on workspaceId '{}'",
                                    count, projectId, workspaceId))
                            .flatMap(count -> checkAndTriggerOnlineScoring(projectId, threads)
                                    .thenReturn(count));
                })
                .reduce(0L, Long::sum)
                .doOnError(ex -> log.error(
                        "Error when processing closure of pending trace threads  for project: '{}' workspaceId '{}'",
                        projectId, workspaceId, ex))
                .flatMap(count -> {
                    var lock = new LockService.Lock(TraceThreadBufferConfig.BUFFER_SET_NAME, projectId.toString());
                    return lockService.unlockUsingToken(lock).thenReturn(count);
                });
    }

    private Mono<Instant> getWorkspaceTimeout(Instant now, Duration defaultTimeoutToMarkThreadAsInactive) {
        // A workspace configuration may exist with a null timeoutToMarkThreadAsInactive (e.g. only
        // truncationOnTables is set). mapNotNull drops that null so it falls through to the default,
        // instead of map() emitting null and Reactor throwing "mapper returned a null value".
        return workspaceConfigurationService.getConfiguration()
                .mapNotNull(WorkspaceConfiguration::timeoutToMarkThreadAsInactive)
                .defaultIfEmpty(defaultTimeoutToMarkThreadAsInactive)
                .map(now::minus);
    }

    private Mono<Void> checkAndTriggerOnlineScoring(UUID projectId, List<TraceThreadModel> closedThreads) {
        if (closedThreads.isEmpty()) {
            log.info("No closed trace threads to process for projectId: '{}'. Skipping online scoring.", projectId);
            return Mono.empty();
        }

        // Publish closed threads for online scoring
        return onlineScorePublisher.publish(projectId, closedThreads);
    }

    @Override
    public Mono<Boolean> addToPendingQueue(@NonNull UUID projectId) {
        var lock = new LockService.Lock(TraceThreadBufferConfig.BUFFER_SET_NAME, projectId.toString());
        return lockService.lockUsingToken(lock, LOCK_DURATION);
    }

    @Override
    public Mono<Void> openThread(UUID projectId, String projectName, @NonNull String threadId) {
        return projectService.resolveProjectIdAndVerifyVisibility(projectId, projectName)
                .flatMap(verifiedProjectId -> getOrCreateThreadId(verifiedProjectId, threadId)
                        .then(Mono.defer(() -> lockService.executeWithLockCustomExpire(
                                new LockService.Lock(verifiedProjectId, TraceThreadService.THREADS_LOCK),
                                Mono.defer(() -> traceThreadDAO.openThread(verifiedProjectId, threadId)),
                                LOCK_DURATION)))
                        .doOnSuccess(_ -> log.info("Opened thread for threadId '{}' and projectId: '{}'", threadId,
                                verifiedProjectId))
                        .then());
    }

    @Override
    public Mono<Void> closeThreads(UUID projectId, String projectName, @NonNull Set<String> threadIds) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.empty();
        }

        return projectService.resolveProjectIdAndVerifyVisibility(projectId, projectName)
                .flatMap(verifiedProjectId -> verifyAndCreateThreadsIfNeeded(verifiedProjectId, threadIds)
                        // Lock only the non-idempotent op: the closure write itself.
                        .then(Mono.defer(() -> lockService.executeWithLockCustomExpire(
                                new LockService.Lock(verifiedProjectId, TraceThreadService.THREADS_LOCK),
                                Mono.defer(() -> traceThreadDAO.closeThread(verifiedProjectId, threadIds))
                                        .doOnSuccess(count -> log.info(
                                                "Closed count '{}' for threadIds '{}' and projectId: '{}'",
                                                count, threadIds, verifiedProjectId)),
                                LOCK_DURATION)))
                        // The post-close read (idempotent) and online-scoring publish run outside the lock.
                        .then(Mono.defer(() -> traceThreadDAO.findThreadsByProject(1, threadIds.size(),
                                TraceThreadCriteria.builder()
                                        .projectId(verifiedProjectId)
                                        .threadIds(threadIds)
                                        .build())))
                        .flatMap(threadModels -> checkAndTriggerOnlineScoring(verifiedProjectId, threadModels)));
    }

    private Mono<Void> verifyAndCreateThreadsIfNeeded(UUID projectId, Set<String> threadIds) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.empty();
        }

        return verifyAndCreateThreadIfNeed(projectId, threadIds);
    }

    private Mono<Void> verifyAndCreateThreadIfNeed(UUID projectId, Set<String> threadIds) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return traceService.getMinimalThreadInfoByIds(projectId, threadIds)
                    .flatMap(existingThreads -> validateIfAllThreadsExist(threadIds, existingThreads))
                    .flatMap(existingThreads -> resolveThreadModelIds(projectId, workspaceId, existingThreads))
                    .flatMap(threadsWithIds -> createMissingThreads(projectId, workspaceId, userName, threadsWithIds))
                    .then();
        });
    }

    // Bulk-resolve a trace-thread-model id for every thread, allocating ids only for those missing one.
    private Mono<List<TraceThread>> resolveThreadModelIds(UUID projectId, String workspaceId,
            List<TraceThread> threads) {
        Map<String, Instant> needIds = threads.stream()
                .filter(thread -> thread.threadModelId() == null)
                // On a duplicate threadId, keep the earliest createdAt — the thread-model id is derived
                // from the first trace's timestamp.
                .collect(Collectors.toMap(TraceThread::id, TraceThread::createdAt,
                        (a, b) -> a.isBefore(b) ? a : b));

        if (needIds.isEmpty()) {
            return Mono.just(threads);
        }

        return traceThreadIdService.getOrCreateTraceThreadIds(workspaceId, projectId, needIds)
                .map(models -> {
                    Map<String, UUID> idByThreadId = models.stream()
                            .collect(Collectors.toMap(TraceThreadIdModel::threadId, TraceThreadIdModel::id));
                    return threads.stream()
                            .map(thread -> thread.threadModelId() != null
                                    ? thread
                                    : thread.toBuilder().threadModelId(idByThreadId.get(thread.id())).build())
                            .toList();
                });
    }

    // Bulk-check which threads already exist in trace_threads and create the missing ones in a single write + event.
    private Mono<Void> createMissingThreads(UUID projectId, String workspaceId, String userName,
            List<TraceThread> threads) {
        List<UUID> modelIds = threads.stream().map(TraceThread::threadModelId).toList();
        // OPIK-7035: also constrain by thread_id so the (workspace_id, project_id, thread_id, id) primary key
        // can seek instead of scanning the whole project's trace_threads. Filtering by id alone leaves thread_id
        // (the 3rd PK column) unbound, forcing a full-project scan (~750K rows in prod to find a handful).
        // thread_model_id maps 1:1 to thread_id, so this excludes no matching row.
        Set<String> threadIds = threads.stream().map(TraceThread::id).collect(Collectors.toSet());
        var criteria = TraceThreadCriteria.builder().projectId(projectId).ids(modelIds).threadIds(threadIds).build();

        return traceThreadDAO.findThreadsByProject(1, modelIds.size(), criteria)
                .flatMap(existing -> {
                    Set<UUID> existingIds = existing.stream()
                            .map(TraceThreadModel::id)
                            .collect(Collectors.toSet());

                    List<TraceThreadModel> toCreate = threads.stream()
                            .filter(thread -> !existingIds.contains(thread.threadModelId()))
                            .map(thread -> TraceThreadModel.builder()
                                    .projectId(projectId)
                                    .threadId(thread.id())
                                    .id(thread.threadModelId())
                                    .status(TraceThreadStatus.ACTIVE)
                                    .createdBy(thread.createdBy())
                                    .lastUpdatedBy(userName)
                                    .createdAt(thread.createdAt())
                                    .lastUpdatedAt(Instant.now())
                                    .environment(thread.environment())
                                    .build())
                            .toList();

                    if (toCreate.isEmpty()) {
                        return Mono.empty();
                    }

                    return traceThreadDAO.save(toCreate)
                            .doOnSuccess(__ -> {
                                eventBus.post(new TraceThreadsCreated(toCreate, projectId, workspaceId, userName));
                                log.info("Created '{}' new trace threads for projectId: '{}'", toCreate.size(),
                                        projectId);
                            })
                            .then();
                });
    }

    private Mono<List<TraceThread>> validateIfAllThreadsExist(Set<String> threadIds,
            List<TraceThread> existingThreads) {
        Set<String> existingThreadIdIds = existingThreads.stream()
                .map(TraceThread::id)
                .collect(Collectors.toSet());

        // Find threadIds that do not exist in the trace table
        Set<String> missingThreadIds = threadIds.stream()
                .filter(threadId -> !existingThreadIdIds.contains(threadId))
                .collect(Collectors.toSet());

        if (!missingThreadIds.isEmpty()) {
            return Mono.error(new NotFoundException("Thread '%s' not found:".formatted(missingThreadIds)));
        }

        // If all threadIds exist, return any of them to continue the flow
        return Mono.just(existingThreads);
    }
}
