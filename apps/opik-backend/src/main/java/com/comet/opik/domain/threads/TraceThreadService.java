package com.comet.opik.domain.threads;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(TraceThreadServiceImpl.class)
public interface TraceThreadService {

    String THREADS_LOCK = "trace-threads-process";

    Mono<Void> processTraceThreads(Set<String> threadIds, UUID projectId);

    Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> getProjectsWithPendingClosureThreads(Instant lastUpdatedUntil,
            int limit);

    Mono<Void> processProjectWithTraceThreadsPendingClosure(UUID projectId, Instant lastUpdatedUntil);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceThreadServiceImpl implements TraceThreadService {

    private final @NonNull TraceThreadDAO traceThreadDAO;
    private final @NonNull TraceThreadIdService traceThreadIdService;
    private final @NonNull LockService lockService;

    @Override
    public Mono<Void> processTraceThreads(@NonNull Set<String> threadIds, @NonNull UUID projectId) {

        if (threadIds.isEmpty()) {
            log.info("No thread IDs provided for projectId: '{}'. Skipping processing.", projectId);
            return Mono.empty();
        }

        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> processThreadAsync(threadIds, projectId)
                        .collectList()
                        .flatMap(traceThreads -> this.saveTraceThreads(projectId, traceThreads))
                        .then()),
                Duration.ofMillis(1));
    }

    private Flux<TraceThreadModel> processThreadAsync(Set<String> threadIds, UUID projectId) {
        return Flux.deferContextual(context -> Flux.fromIterable(threadIds)
                .flatMap(threadId -> {
                    String workspaceId = context.get(RequestContext.WORKSPACE_ID);
                    String userName = context.get(RequestContext.USER_NAME);

                    return traceThreadIdService.getOrCreateTraceThreadId(workspaceId, projectId, threadId)
                            .map(traceThreadId -> mapToModel(traceThreadId, userName));
                }));
    }

    private TraceThreadModel mapToModel(TraceThreadIdModel traceThread, String userName) {
        return TraceThreadMapper.INSTANCE.mapFromThreadIdModel(traceThread, userName, TraceThreadModel.Status.ACTIVE,
                Instant.now());
    }

    private Mono<Void> saveTraceThreads(UUID projectId, List<TraceThreadModel> traceThreads) {

        if (traceThreads.isEmpty()) {
            return Mono.empty();
        }

        List<UUID> ids = traceThreads.stream().map(TraceThreadModel::id).toList();

        var criteria = TraceThreadCriteria.builder()
                .projectId(projectId)
                .status(TraceThreadModel.Status.INACTIVE)
                .ids(ids)
                .build();

        return getReopenedThreads(traceThreads, criteria)
                .flatMap(reopenedThreads -> traceThreadDAO.save(traceThreads)
                        .doOnSuccess(
                                count -> log.info("Saved '{}' trace threads for projectId: '{}'", count, projectId)))
                //TODO: Handle the case where threads are reopened
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
    public Mono<List<TraceThreadModel>> getThreadsByProject(int page, int size, TraceThreadCriteria criteria) {
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
                Duration.ofSeconds(5))
                .then();
    }

    private Mono<Long> closeThreadWith(UUID projectId, Instant lastUpdatedUntil, ContextView contextView) {
        return traceThreadDAO.closeThreadWith(projectId, lastUpdatedUntil)
                .doOnSuccess(count -> log.info("Closed '{}' trace threads for projectId: '{}' on workspaceId: '{}'",
                        count, projectId, contextView.get(RequestContext.WORKSPACE_ID)))
                .doOnError(ex -> log.error("Error when processing closure of pending trace threads  for project: '%s'"
                        .formatted(projectId), ex));
    }
}
