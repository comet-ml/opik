package com.comet.opik.domain.threads;

import com.comet.opik.api.events.TraceThreadsReopened;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.EventBus;
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

    public static String THREADS_LOCK = "trace-threads-process";

    Mono<Void> processTraceThreads(Set<String> threadIds, UUID projectId);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceThreadServiceImpl implements TraceThreadService {

    private final @NonNull TraceThreadDAO traceThreadDAO;
    private final @NonNull TraceThreadIdService traceThreadIdService;
    private final @NonNull EventBus eventBus;
    private final @NonNull LockService lockService;

    public Mono<Void> processTraceThreads(Set<String> threadIds, UUID projectId) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(projectId, TraceThreadService.THREADS_LOCK),
                Mono.defer(() -> Flux.deferContextual(context -> processThreadAsync(threadIds, projectId, context))
                        .collectList()
                        .flatMap(traceThreads -> this.saveTraceThreads(projectId, traceThreads))
                        .then()),
                Duration.ofMillis(1));
    }

    private Flux<TraceThreadModel> processThreadAsync(Set<String> threadIds, UUID projectId, ContextView context) {
        return Flux.fromIterable(threadIds)
                .flatMap(threadId -> {
                    String workspaceId = context.get(RequestContext.WORKSPACE_ID);
                    String userName = context.get(RequestContext.USER_NAME);

                    return traceThreadIdService.getOrCreateTraceThreadId(workspaceId, projectId, threadId)
                            .map(traceThreadId -> mapToModel(traceThreadId, userName));
                });
    }

    private TraceThreadModel mapToModel(TraceThreadIdModel traceThread, String userName) {
        return TraceThreadModel.builder()
                .id(traceThread.id())
                .projectId(traceThread.projectId())
                .threadId(traceThread.threadId())
                .status(TraceThreadModel.Status.ACTIVE)
                .createdBy(traceThread.createdBy())
                .lastUpdatedBy(userName)
                .createdAt(traceThread.createdAt())
                .lastUpdatedAt(Instant.now())
                .build();
    }

    private Mono<Void> saveTraceThreads(UUID projectId, List<TraceThreadModel> traceThreads) {

        if (traceThreads.isEmpty()) {
            return Mono.empty();
        }

        List<UUID> ids = traceThreads
                .stream()
                .map(TraceThreadModel::id)
                .toList();

        var criteria = TraceThreadCriteria.builder()
                .projectId(projectId)
                .ids(ids)
                .build();

        return getReopenedThreads(traceThreads, criteria)
                .flatMap(reopenedThreads -> traceThreadDAO.save(traceThreads).thenReturn(reopenedThreads))
                .flatMap(reopenedThreads -> {
                    if (!reopenedThreads.isEmpty()) {
                        log.info("Reopened threads for project {}: '[{}]'", projectId, reopenedThreads);
                        eventBus.post(new TraceThreadsReopened(reopenedThreads));
                    }

                    return Mono.empty();
                })
                .then();
    }

    private Mono<Set<UUID>> getReopenedThreads(List<TraceThreadModel> traceThreads,
            TraceThreadCriteria criteria) {
        return traceThreadDAO.findThreadsByProject(1, traceThreads.size(), criteria)
                .map(existingThreads -> existingThreads
                        .stream()
                        .filter(TraceThreadModel::isInactive)
                        .map(TraceThreadModel::id)
                        .collect(Collectors.toSet()));
    }

}
