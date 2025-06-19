package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.api.resources.v1.events.TraceThreadBufferConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(TraceThreadServiceImpl.class)
public interface TraceThreadService {

    String THREADS_LOCK = "trace-threads-process";

    Mono<Void> processTraceThreads(Map<String, Instant> threadIdAndLastUpdateAts, UUID projectId);

    Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(Instant lastUpdatedUntil,
            int limit);

    Mono<Void> processProjectWithTraceThreadsPendingClosure(UUID projectId, Instant lastUpdatedUntil);

    Mono<Boolean> addToPendingQueue(UUID projectId);

    Mono<Void> openThread(UUID projectId, String threadId);

    Mono<Void> closeThread(UUID projectId, String threadId);

    Mono<UUID> getOrCreateThreadId(UUID projectId, String threadId);

    Mono<UUID> getThreadModelId(UUID projectId, String threadId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceThreadServiceImpl implements TraceThreadService {

    private static final Duration LOCK_DURATION = Duration.ofSeconds(5);

    private final @NonNull TraceThreadDAO traceThreadDAO;
    private final @NonNull TraceThreadIdService traceThreadIdService;
    private final @NonNull LockService lockService;

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

    private TraceThreadModel mapToModel(TraceThreadIdModel traceThread, String userName, Instant lastUpdatedAt) {
        return TraceThreadMapper.INSTANCE.mapFromThreadIdModel(traceThread, userName, TraceThreadStatus.ACTIVE, lastUpdatedAt);
    }

    private Mono<Void> saveTraceThreads(UUID projectId, List<TraceThreadModel> traceThreads) {

        if (traceThreads.isEmpty()) {
            return Mono.empty();
        }

        List<UUID> ids = traceThreads.stream().map(TraceThreadModel::id).toList();

        var criteria = TraceThreadCriteria.builder()
                .projectId(projectId)
                .status(TraceThreadStatus.INACTIVE)
                .ids(ids)
                .build();

        return getReopenedThreads(traceThreads, criteria)
                .flatMap(reopenedThreads -> traceThreadDAO.save(traceThreads)
                        .doOnSuccess(
                                count -> log.info("Saved '{}' trace threads for projectId: '{}'", count, projectId)))
                //TODO: Next we will publish the event to notify about reopened threads
                .then();
    }

    private Mono<Set<UUID>> getReopenedThreads(List<TraceThreadModel> traceThreads,
            TraceThreadCriteria criteria) {
        return traceThreadDAO.findThreadsByProject(1, traceThreads.size(), criteria)
                .map(existingThreads -> existingThreads
                        .stream()
                        .map(TraceThreadModel::id)
                        .collect(Collectors.toSet()));
    }

    @Override
    public Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, @NonNull TraceThreadCriteria criteria) {
        return traceThreadDAO.findThreadsByProject(page, size, criteria)
                .switchIfEmpty(Mono.just(List.of()));
    }

    @Override
    public Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(
            @NonNull Instant lastUpdatedUntil, int limit) {
        return traceThreadDAO.findProjectsWithPendingClosureThreads(lastUpdatedUntil, limit);
    }

    @Override
    public Mono<Void> processProjectWithTraceThreadsPendingClosure(@NonNull UUID projectId,
            @NonNull Instant lastUpdatedUntil) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.deferContextual(contextView -> closeThreadWith(projectId, lastUpdatedUntil, contextView)),
                LOCK_DURATION).then();
    }

    private Mono<Long> closeThreadWith(UUID projectId, Instant lastUpdatedUntil, ContextView contextView) {
        return traceThreadDAO.closeThreadWith(projectId, lastUpdatedUntil)
                .flatMap(count -> {
                    var lock = new LockService.Lock(TraceThreadBufferConfig.BUFFER_SET_NAME, projectId.toString());
                    return lockService.unlockUsingToken(lock).thenReturn(count);
                })
                .doOnSuccess(count -> log.info("Closed '{}' trace threads for projectId: '{}' on workspaceId: '{}'",
                        count, projectId, contextView.get(RequestContext.WORKSPACE_ID)))
                .doOnError(ex -> log.error("Error when processing closure of pending trace threads  for project: '%s'"
                        .formatted(projectId), ex));
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
    public Mono<Void> closeThread(@NonNull UUID projectId, @NotBlank String threadId) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> traceThreadDAO.closeThread(projectId, threadId)).then(),
                LOCK_DURATION);
    }

}
