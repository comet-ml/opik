package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.api.events.ThreadsReopened;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.resources.v1.events.TraceThreadBufferConfig;
import com.comet.opik.domain.TraceService;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.time.Instant;
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

    Mono<Void> processTraceThreads(Map<String, Instant> threadIdAndLastUpdateAts, UUID projectId);

    Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(Instant now,
            Duration timeoutToMarkThreadAsInactive,
            int limit);

    Mono<Void> processProjectWithTraceThreadsPendingClosure(UUID projectId, Instant now,
            Duration timeoutToMarkThreadAsInactive);

    Mono<Boolean> addToPendingQueue(UUID projectId);

    Mono<Void> openThread(UUID projectId, String threadId);

    Mono<Void> closeThread(UUID projectId, String threadId);

    Mono<UUID> getOrCreateThreadId(UUID projectId, String threadId);

    Mono<UUID> getThreadModelId(UUID projectId, String threadId);

    Mono<Void> updateThreadSampledValue(UUID projectId, List<TraceThreadSampling> threadSamplingPerRule);

    Mono<Void> update(UUID threadModelId, TraceThreadUpdate threadUpdate);

    Mono<Void> setScoredAt(UUID projectId, List<String> threadIds, Instant scoredAt);
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

    public Mono<Void> processTraceThreads(@NonNull Map<String, Instant> threadIdAndLastUpdateAts,
            @NonNull UUID projectId) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> processThreadAsync(threadIdAndLastUpdateAts, projectId)
                        .collectList()
                        .flatMap(traceThreads -> this.saveTraceThreads(projectId, traceThreads))
                        .then()),
                LOCK_DURATION);
    }

    private Flux<TraceThreadModel> processThreadAsync(Map<String, Instant> threadIdAndLastUpdateAts, UUID projectId) {
        return Flux.deferContextual(context -> Flux.fromIterable(threadIdAndLastUpdateAts.entrySet())
                .flatMap(threadIdAndLastUpdateAt -> {
                    String workspaceId = context.get(RequestContext.WORKSPACE_ID);
                    String userName = context.get(RequestContext.USER_NAME);
                    String threadId = threadIdAndLastUpdateAt.getKey();
                    Instant lastUpdatedAt = threadIdAndLastUpdateAt.getValue();

                    return traceThreadIdService.getOrCreateTraceThreadId(workspaceId, projectId, threadId)
                            .map(traceThreadId -> mapToModel(traceThreadId, userName, lastUpdatedAt));
                }));
    }

    @Override
    public Mono<UUID> getOrCreateThreadId(@NonNull UUID projectId, @NonNull String threadId) {
        return Mono.deferContextual(context -> traceThreadIdService
                .getOrCreateTraceThreadId(context.get(RequestContext.WORKSPACE_ID), projectId, threadId)
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
                .doOnSuccess(count -> log.info("Updated '{}' trace threads sampled values for projectId: '{}'", count,
                        projectId))
                .then();
    }

    public Mono<Void> update(@NonNull UUID threadModelId, @NonNull TraceThreadUpdate threadUpdate) {
        return traceThreadIdService.getTraceThreadIdByThreadModelId(threadModelId)
                .switchIfEmpty(Mono.error(failWithNotFound("Thread", threadModelId)))
                .flatMap(traceThreadIdModel -> traceThreadDAO.updateThread(threadModelId,
                        traceThreadIdModel.projectId(), threadUpdate));
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
                                count -> {

                                    Set<UUID> existingThreadModelIds = existingThreads.stream()
                                            .map(TraceThreadModel::id)
                                            .collect(Collectors.toSet());

                                    List<UUID> createdThreadIds = traceThreads
                                            .stream()
                                            .map(TraceThreadModel::id)
                                            .filter(id -> !existingThreadModelIds.contains(id))
                                            .toList();

                                    if (!createdThreadIds.isEmpty()) {
                                        eventBus.post(new TraceThreadsCreated(createdThreadIds, projectId,
                                                context.get(RequestContext.WORKSPACE_ID),
                                                context.get(RequestContext.USER_NAME)));
                                    }

                                    log.info("Saved '{}' trace threads for projectId: '{}'", count, projectId);
                                })
                        .flatMap(count -> Mono.fromCallable(() -> {
                            List<TraceThreadModel> reopenedThreads = existingThreads.stream()
                                    .filter(TraceThreadModel::isInactive)
                                    .toList();

                            if (reopenedThreads.isEmpty()) {
                                return Mono.empty();
                            }
                            log.info("Reopened '{}' trace threads for projectId: '{}'", reopenedThreads.size(),
                                    projectId);

                            eventBus.post(new ThreadsReopened(
                                    reopenedThreads.stream().map(TraceThreadModel::id).collect(Collectors.toSet()),
                                    projectId,
                                    context.get(RequestContext.WORKSPACE_ID),
                                    context.get(RequestContext.USER_NAME)));

                            return null;
                        }).subscribeOn(Schedulers.boundedElastic())))
                .then());
    }

    private Mono<Long> saveThreads(List<TraceThreadModel> traceThreads, List<TraceThreadModel> existingThreads) {
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

        return traceThreadDAO.save(threadsToSave);
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
            @NonNull Instant now, @NonNull Duration timeoutToMarkThreadAsInactive, int limit) {
        return traceThreadDAO.findProjectsWithPendingClosureThreads(now, timeoutToMarkThreadAsInactive, limit);
    }

    @Override
    public Mono<Void> processProjectWithTraceThreadsPendingClosure(@NonNull UUID projectId,
            @NonNull Instant now, @NonNull Duration timeoutToMarkThreadAsInactive) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.deferContextual(
                        contextView -> closeThreadWith(projectId, now, timeoutToMarkThreadAsInactive, contextView)),
                LOCK_DURATION).then();
    }

    private Mono<Long> closeThreadWith(UUID projectId, Instant now, Duration timeoutToMarkThreadAsInactive,
            ContextView ctx) {
        String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
        return traceThreadDAO.closeThreadWith(projectId, now, timeoutToMarkThreadAsInactive)
                .flatMap(count -> {
                    var lock = new LockService.Lock(TraceThreadBufferConfig.BUFFER_SET_NAME, projectId.toString());
                    return lockService.unlockUsingToken(lock).thenReturn(count);
                })
                .doOnSuccess(count -> log.info("Closed '{}' trace threads for projectId: '{}' on workspaceId '{}'",
                        count, projectId, workspaceId))
                .doOnError(ex -> log.error(
                        "Error when processing closure of pending trace threads  for project: '%s' workspaceId '%s'"
                                .formatted(projectId, workspaceId),
                        ex))
                .flatMap(count -> checkAndTriggerOnlineScoring(projectId, count));

    }

    private Mono<Long> checkAndTriggerOnlineScoring(UUID projectId, Long count) {
        if (count <= 0) {
            log.info("No closed trace threads to process for projectId: '{}'. Skipping online scoring.", projectId);
            return Mono.just(count);
        }

        return getClosedThreadsByProject(projectId)
                .flatMap(closedThreads -> {
                    if (!closedThreads.isEmpty()) {
                        // Publish closed threads for online scoring
                        return onlineScorePublisher.publish(projectId, closedThreads)
                                .thenReturn(count);
                    }

                    log.info("No closed threads found for projectId: '{}'. Skipping online scoring.", projectId);
                    return Mono.just(count);
                });
    }

    private Mono<List<TraceThreadModel>> getClosedThreadsByProject(UUID projectId) {
        return traceThreadDAO.streamClosedThreads(projectId)
                .collectList()
                .switchIfEmpty(Mono.just(List.of()));
    }

    @Override
    public Mono<Boolean> addToPendingQueue(@NonNull UUID projectId) {
        var lock = new LockService.Lock(TraceThreadBufferConfig.BUFFER_SET_NAME, projectId.toString());
        return lockService.lockUsingToken(lock, LOCK_DURATION);
    }

    @Override
    public Mono<Void> openThread(@NonNull UUID projectId, @NonNull String threadId) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> traceThreadDAO.openThread(projectId, threadId)).then(),
                LOCK_DURATION);
    }

    @Override
    public Mono<Void> closeThread(@NonNull UUID projectId, @NonNull String threadId) {
        return verifyAndCreateThreadIfNeed(projectId, threadId)
                // Once we have all, we can close the thread
                .then(Mono.defer(() -> lockService.executeWithLockCustomExpire(
                        new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                        Mono.defer(() -> traceThreadDAO.closeThread(projectId, threadId))
                                .doOnSuccess(
                                        count -> log.info("Closed count '{}' for threadId '{}' and  projectId: '{}'",
                                                count, threadId, projectId))
                                .flatMap(count -> checkAndTriggerOnlineScoring(projectId, count))
                                .then(),
                        LOCK_DURATION)));
    }

    private Mono<UUID> verifyAndCreateThreadIfNeed(UUID projectId, String threadId) {
        return traceService.getThreadById(projectId, threadId)
                .switchIfEmpty(Mono.error(new NotFoundException("Thread '%s' not found:".formatted(threadId))))
                // If the trace thread exists on the trace table, let's check if it has a trace thread model id
                .flatMap(traceThread -> getOrCreateThreadId(projectId, threadId)
                        .map(threadModelId -> traceThread.toBuilder().threadModelId(threadModelId).build()))
                // If it has a trace thread model id, check if the trace thread entity exists in the database
                .flatMap(traceThread -> traceThreadDAO.findByThreadModelId(traceThread.threadModelId(), projectId)
                        .map(TraceThreadModel::id)
                        //If it does not exist, create a new one
                        .switchIfEmpty(Mono.deferContextual(ctx -> {
                            String userName = ctx.get(RequestContext.USER_NAME);
                            return createTraceThread(projectId, threadId, traceThread, userName);
                        })));
    }

    private Mono<UUID> createTraceThread(UUID projectId, String threadId, TraceThread traceThread, String userName) {
        log.warn("Creating a new thread with id '{}' for threadId '{}' and projectId: '{}'",
                traceThread.threadModelId(), threadId, projectId);

        return traceThreadDAO
                .save(List.of(TraceThreadModel.builder()
                        .projectId(projectId)
                        .threadId(threadId)
                        .id(traceThread.threadModelId())
                        .status(TraceThreadStatus.ACTIVE)
                        .createdBy(traceThread.createdBy())
                        .lastUpdatedBy(userName)
                        .createdAt(traceThread.createdAt())
                        .lastUpdatedAt(Instant.now())
                        .build()))
                .thenReturn(traceThread.threadModelId())
                .doOnSuccess(
                        id -> {
                            eventBus.post(new TraceThreadsCreated(List.of(id), projectId, traceThread.workspaceId(),
                                    userName));
                            log.info("Created new trace thread with id '{}' for threadId '{}' and projectId: '{}'",
                                    id, threadId, projectId);
                        });
    }
}
