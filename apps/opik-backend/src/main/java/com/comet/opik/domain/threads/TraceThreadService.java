package com.comet.opik.domain.threads;

import com.comet.opik.api.ThreadTimestamps;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadBatchUpdate;
import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.api.events.ThreadsReopened;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.resources.v1.events.TraceThreadBufferConfig;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TagOperations;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceConfigurationService;
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
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(Instant now,
            Duration defaultTimeoutToMarkThreadAsInactive,
            int limit);

    Mono<Void> processProjectWithTraceThreadsPendingClosure(UUID projectId, Instant now,
            Duration defaultTimeoutToMarkThreadAsInactive);

    Mono<Boolean> addToPendingQueue(UUID projectId);

    Mono<Void> openThread(UUID projectId, String threadId);

    Mono<Void> closeThreads(UUID projectId, Set<String> threadIds);

    Mono<UUID> getOrCreateThreadId(UUID projectId, String threadId);

    Mono<UUID> getOrCreateThreadId(UUID projectId, String threadId, Instant timestamp);

    Mono<UUID> getThreadModelId(UUID projectId, String threadId);

    Mono<Void> updateThreadSampledValue(UUID projectId, List<TraceThreadSampling> threadSamplingPerRule);

    Mono<Void> update(UUID threadModelId, TraceThreadUpdate threadUpdate);

    Mono<Void> batchUpdate(TraceThreadBatchUpdate batchUpdate);

    Mono<Void> setScoredAt(UUID projectId, List<String> threadIds, Instant scoredAt);

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
    private final @NonNull LockService lockService;
    private final @NonNull EventBus eventBus;
    private final @NonNull TraceThreadOnlineScorerPublisher onlineScorePublisher;
    private final @NonNull WorkspaceConfigurationService workspaceConfigurationService;

    public Mono<Void> processTraceThreads(@NonNull Map<String, ThreadTimestamps> threadInfo,
            @NonNull UUID projectId) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> processThreadAsync(threadInfo, projectId)
                        .collectList()
                        .flatMap(traceThreads -> this.saveTraceThreads(projectId, traceThreads))
                        .then()),
                LOCK_DURATION);
    }

    private Flux<TraceThreadModel> processThreadAsync(Map<String, ThreadTimestamps> threadInfo, UUID projectId) {
        return Flux.deferContextual(context -> Flux.fromIterable(threadInfo.entrySet())
                .flatMap(entry -> {
                    String workspaceId = context.get(RequestContext.WORKSPACE_ID);
                    String userName = context.get(RequestContext.USER_NAME);
                    String threadId = entry.getKey();
                    ThreadTimestamps timestamps = entry.getValue();

                    // Extract timestamp from earliest trace (first trace in chronological order)
                    Instant earliestTraceTimestamp = IdGenerator.extractTimestampFromUUIDv7(timestamps.firstTraceId());

                    return traceThreadIdService
                            .getOrCreateTraceThreadId(workspaceId, projectId, threadId, earliestTraceTimestamp)
                            .map(traceThreadId -> mapToModel(traceThreadId, userName, timestamps.lastUpdatedAt()));
                }));
    }

    @Override
    public Mono<UUID> getOrCreateThreadId(@NonNull UUID projectId, @NonNull String threadId) {
        return getOrCreateThreadId(projectId, threadId, null);
    }

    @Override
    public Mono<UUID> getOrCreateThreadId(@NonNull UUID projectId, @NonNull String threadId, Instant timestamp) {
        return Mono.deferContextual(context -> traceThreadIdService
                .getOrCreateTraceThreadId(context.get(RequestContext.WORKSPACE_ID), projectId, threadId, timestamp)
                .map(TraceThreadIdModel::id));
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

        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> processUpdateThreadSampledValue(projectId, threadSamplingPerRules)),
                LOCK_DURATION);
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
    public Mono<Void> setScoredAt(@NonNull UUID projectId, @NonNull List<String> threadIds, @NonNull Instant scoredAt) {
        if (threadIds.isEmpty()) {
            log.info("No threadIds provided for setting scored at. For projectId: '{}', skipping update.", projectId);
            return Mono.empty();
        }

        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> traceThreadDAO.setScoredAt(projectId, threadIds, scoredAt))
                        .doOnSuccess(count -> log.info("Set scoredAt '{}' for threadIds'[{}]' in projectId: '{}'",
                                scoredAt, threadIds, projectId))
                        .doOnError(ex -> log.error(
                                "Error setting scoredAt for threadIds: '[{}]' in projectId: '{}' with error: {}",
                                scoredAt, threadIds, projectId, ex))
                        .then(),
                LOCK_DURATION);
    }

    @Override
    public Mono<Map<UUID, String>> getThreadIdsByThreadModelIds(@NonNull List<UUID> threadModelIds) {
        return traceThreadIdService.getTraceThreadIdsByThreadModelIds(threadModelIds);
    }

    private TraceThreadModel mapToModel(TraceThreadIdModel traceThread, String userName, Instant lastUpdatedAt) {
        return TraceThreadMapper.INSTANCE.mapFromThreadIdModel(traceThread, userName, TraceThreadStatus.ACTIVE,
                lastUpdatedAt);
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
                        .doOnSuccess(
                                entry -> {
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
                        .flatMap(count -> Mono.fromCallable(() -> {
                            // Trigger ThreadsReopened for all existing threads that received new traces,
                            // regardless of their status. This ensures consistent behavior for score deletion
                            // and other side effects, independent of the thread status concept.
                            // Note: The event is named "Reopened" because internally threads still have
                            // an active/inactive status, even though this is hidden from the UI.
                            if (existingThreads.isEmpty()) {
                                return Mono.empty();
                            }
                            log.info("Processing '{}' threads with new traces for projectId: '{}'",
                                    existingThreads.size(), projectId);

                            eventBus.post(new ThreadsReopened(
                                    existingThreads.stream().map(TraceThreadModel::id).collect(Collectors.toSet()),
                                    projectId,
                                    context.get(RequestContext.WORKSPACE_ID),
                                    context.get(RequestContext.USER_NAME)));

                            return null;
                        }).subscribeOn(Schedulers.boundedElastic())))
                .then());
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
                                .scoredAt(null) // Reset scoredAt to null for reopened threads
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
    public Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, @NonNull TraceThreadCriteria criteria) {
        return traceThreadDAO.findThreadsByProject(page, size, criteria)
                .switchIfEmpty(Mono.just(List.of()));
    }

    @Override
    public Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(
            @NonNull Instant now, @NonNull Duration defaultTimeoutToMarkThreadAsInactive, int limit) {
        return traceThreadDAO.findProjectsWithPendingClosureThreads(now, defaultTimeoutToMarkThreadAsInactive, limit);
    }

    @Override
    public Mono<Void> processProjectWithTraceThreadsPendingClosure(@NonNull UUID projectId,
            @NonNull Instant now, @NonNull Duration defaultTimeoutToMarkThreadAsInactive) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.deferContextual(
                        contextView -> closeThreadWith(projectId, now, defaultTimeoutToMarkThreadAsInactive,
                                contextView)),
                LOCK_DURATION).then();
    }

    private Mono<Long> closeThreadWith(UUID projectId, Instant now, Duration defaultTimeoutToMarkThreadAsInactive,
            ContextView ctx) {

        String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

        return getWorkspaceTimeout(now, defaultTimeoutToMarkThreadAsInactive)
                .flatMapMany(lastUpdatedAt -> traceThreadDAO.streamPendingClosureThreads(projectId, lastUpdatedAt))
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
                        "Error when processing closure of pending trace threads  for project: '%s' workspaceId '%s'"
                                .formatted(projectId, workspaceId),
                        ex))
                .flatMap(count -> {
                    var lock = new LockService.Lock(TraceThreadBufferConfig.BUFFER_SET_NAME, projectId.toString());
                    return lockService.unlockUsingToken(lock).thenReturn(count);
                });
    }

    private Mono<Instant> getWorkspaceTimeout(Instant now, Duration defaultTimeoutToMarkThreadAsInactive) {
        return workspaceConfigurationService.getConfiguration()
                .map(WorkspaceConfiguration::timeoutToMarkThreadAsInactive)
                .switchIfEmpty(Mono.just(defaultTimeoutToMarkThreadAsInactive))
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
    public Mono<Void> openThread(@NonNull UUID projectId, @NonNull String threadId) {
        return Mono.deferContextual(ctx -> lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> traceThreadDAO.openThread(projectId, threadId))
                        .then(Mono.defer(() -> getOrCreateThreadId(projectId, threadId)))
                        .doOnSuccess(threadModelId -> publishReopenedEvent(projectId, threadId, ctx, threadModelId))
                        .then(),
                LOCK_DURATION));
    }

    private void publishReopenedEvent(UUID projectId, String threadId, ContextView ctx, UUID threadModelId) {
        log.info("Opened thread for threadId '{}' and projectId: '{}'", threadId, projectId);

        eventBus.post(new ThreadsReopened(
                Set.of(threadModelId), projectId, ctx.get(RequestContext.WORKSPACE_ID),
                ctx.get(RequestContext.USER_NAME)));
    }

    @Override
    public Mono<Void> closeThreads(@NonNull UUID projectId, @NonNull Set<String> threadIds) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.empty();
        }

        return verifyAndCreateThreadsIfNeeded(projectId, threadIds)
                .then(Mono.defer(() -> lockService.executeWithLockCustomExpire(
                        new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                        Mono.defer(() -> traceThreadDAO.closeThread(projectId, threadIds))
                                .doOnSuccess(count -> log.info(
                                        "Closed count '{}' for threadIds '{}' and projectId: '{}'",
                                        count, threadIds, projectId))
                                .then(Mono.defer(() -> traceThreadDAO.findThreadsByProject(1, threadIds.size(),
                                        TraceThreadCriteria.builder()
                                                .projectId(projectId)
                                                .threadIds(threadIds)
                                                .build())))
                                .flatMap(threadModels -> checkAndTriggerOnlineScoring(projectId, threadModels)),
                        LOCK_DURATION)));
    }

    private Mono<Void> verifyAndCreateThreadsIfNeeded(UUID projectId, Set<String> threadIds) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.empty();
        }

        return verifyAndCreateThreadIfNeed(projectId, threadIds);
    }

    private Mono<Void> verifyAndCreateThreadIfNeed(UUID projectId, Set<String> threadIds) {
        return traceService.getMinimalThreadInfoByIds(projectId, threadIds)
                .flatMap(existingThreads -> validateIfAllThreadsExist(threadIds, existingThreads))
                .flatMapMany(Flux::fromIterable)
                // If the trace thread exists on the trace table, let's check if it has a trace thread model id
                .flatMap(traceThread -> {
                    if (traceThread.threadModelId() != null) {
                        return Mono.just(traceThread);
                    }
                    // If it does not have a trace thread model id, create a new one using the minimum trace timestamp
                    return getOrCreateThreadId(projectId, traceThread.id(), traceThread.createdAt())
                            .map(id -> traceThread.toBuilder().threadModelId(id).build());
                })
                // If it has a trace thread model id, check if the trace thread entity exists in the database
                .flatMap(traceThread -> traceThreadDAO.findByThreadModelId(traceThread.threadModelId(), projectId)
                        .map(TraceThreadModel::id)
                        //If it does not exist, create a new one
                        .switchIfEmpty(Mono.deferContextual(ctx -> {
                            String userName = ctx.get(RequestContext.USER_NAME);
                            return createTraceThread(projectId, traceThread.id(), traceThread, userName);
                        })))
                .then();
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

    private Mono<UUID> createTraceThread(UUID projectId, String threadId, TraceThread traceThread, String userName) {
        log.warn("Creating a new thread with id '{}' for threadId '{}' and projectId: '{}'",
                traceThread.threadModelId(), threadId, projectId);

        List<TraceThreadModel> traceThreads = List.of(TraceThreadModel.builder()
                .projectId(projectId)
                .threadId(threadId)
                .id(traceThread.threadModelId())
                .status(TraceThreadStatus.ACTIVE)
                .createdBy(traceThread.createdBy())
                .lastUpdatedBy(userName)
                .createdAt(traceThread.createdAt())
                .lastUpdatedAt(Instant.now())
                .build());

        return traceThreadDAO
                .save(traceThreads)
                .thenReturn(traceThread.threadModelId())
                .doOnSuccess(
                        id -> {
                            eventBus.post(new TraceThreadsCreated(traceThreads, projectId, traceThread.workspaceId(),
                                    userName));
                            log.info("Created new trace thread with id '{}' for threadId '{}' and projectId: '{}'",
                                    id, threadId, projectId);
                        });
    }
}
